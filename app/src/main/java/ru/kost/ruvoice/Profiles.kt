package ru.kost.ruvoice

import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.service.quicksettings.TileService
import org.json.JSONArray
import org.json.JSONObject

/**
 * Профили настроек (вкладка «Профили», плитка в шторке). Рабочие настройки как были — в prefs «ruvoice»,
 * весь остальной код читает их оттуда и о профилях не знает. Профиль — снимок этих prefs: при
 * переключении снимок текущих уходит в активный профиль, prefs заполняются снимком нового.
 * В снимок идёт всё, кроме служебного и состояния экрана ([ProfileData.isProfileKey]) — новые настройки
 * попадают в профили сами. Словари (файлы) общие, в профиле — какие из них выключены.
 *
 * Хранилище — prefs «ruvoice_profiles»: список JSON, id активного и счётчик переключений [gen]. Пустое
 * хранилище (первый запуск после обновления со старой версии) — один профиль «Основной» из текущих
 * настроек, prefs не меняются.
 */
class Profiles(private val context: Context) {
    class Profile(val id: String, val name: String, val data: Map<String, Any>) {
        val main get() = id == MAIN
    }

    private val live = context.getSharedPreferences("ruvoice", Context.MODE_PRIVATE)
    private val store = context.getSharedPreferences("ruvoice_profiles", Context.MODE_PRIVATE)

    fun list(): List<Profile> = synchronized(LOCK) { read() }
    fun active(): Profile = synchronized(LOCK) { read().let { l -> l.firstOrNull { it.id == activeId() } ?: l.first() } }
    /** Счётчик переключений: открытые страницы настроек по нему понимают, что их поля — от другого профиля. */
    val gen: Int get() = store.getInt("gen", 0)

    /** Имя свободно (без учёта регистра); [except] — профиль, который переименовывают. */
    fun nameFree(name: String, except: String? = null) = list().none { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }

    /** Новый профиль — копия текущих настроек, сразу активный. */
    fun add(name: String): Profile = synchronized(LOCK) {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim(), capture())
        write(read().map { if (it.id == activeId()) Profile(it.id, it.name, p.data) else it } + p, p.id)
        notifyTile(); p
    }

    fun rename(id: String, name: String) = synchronized(LOCK) {
        write(read().map { if (it.id == id) Profile(it.id, name.trim(), it.data) else it }, activeId()); notifyTile()
    }

    /** «Основной» не удаляется; удалили активный — включается «Основной». */
    fun delete(id: String) = synchronized(LOCK) {
        if (id == MAIN) return@synchronized
        if (id == activeId()) switchTo(MAIN)
        write(read().filter { it.id != id }, activeId()); notifyTile()
    }

    fun switchTo(id: String) = synchronized(LOCK) {
        val list = read()
        val target = list.firstOrNull { it.id == id } ?: return@synchronized
        if (id == activeId()) return@synchronized
        val cur = activeId()
        val saved = list.map { if (it.id == cur) Profile(it.id, it.name, capture()) else it }
        write(saved, id)
        load(target.data)
        notifyTile()
    }

    /** Следующий по списку — для плитки при двух профилях. */
    fun next(): Profile = list().let { l -> l[(l.indexOfFirst { it.id == activeId() } + 1) % l.size] }

    /** Для экспорта: все профили, у активного — текущие prefs. */
    fun snapshot(): List<Profile> = synchronized(LOCK) {
        read().map { if (it.id == activeId()) Profile(it.id, it.name, capture()) else it }
    }

    /**
     * Импорт профилей из файла: «Основной» файла — в «Основной», прочие — в одноимённые или новые;
     * [exact] (отмена импорта) — ещё и удалить те, которых в файле нет. Потом включается [activeName]
     * файла, prefs заполняются его снимком (без сохранения текущих поверх — их только что заменили).
     */
    fun import(entries: List<SettingsJson.ProfileEntry>, activeName: String?, exact: Boolean) = synchronized(LOCK) {
        val cur = activeId()
        var list = read().map { if (it.id == cur) Profile(it.id, it.name, capture()) else it }
        if (exact) list = list.filter { p -> p.main || entries.any { !it.main && it.name.equals(p.name, ignoreCase = true) } }
        var n = 0
        for (e in entries) {
            val name = e.name.trim()
            if (name.isEmpty()) continue
            val i = list.indexOfFirst { if (e.main) it.main else !it.main && it.name.equals(name, ignoreCase = true) }
            list = if (i >= 0) list.toMutableList().also { it[i] = Profile(it[i].id, name, e.prefs) }
                else list + Profile("p" + System.currentTimeMillis().toString(36) + (n++), name, e.prefs)
        }
        // одноимённые после импорта (файл назвал «Основным» другой профиль) — второму номер
        val seen = HashSet<String>()
        list = list.map { p -> var nm = p.name; var k = 2; while (!seen.add(nm.lowercase())) nm = "${p.name} ${k++}"; Profile(p.id, nm, p.data) }
        val target = entries.firstOrNull { it.name.trim() == activeName?.trim() }
            ?.let { e -> list.firstOrNull { if (e.main) it.main else it.name.equals(e.name.trim(), ignoreCase = true) } }
            ?: list.firstOrNull { it.id == cur } ?: list.first()
        write(list, target.id)
        load(target.data)
        notifyTile()
    }

    private fun activeId(): String = store.getString("active", MAIN)!!

    private fun read(): List<Profile> {
        val raw = store.getString("list", null)
        if (raw != null) runCatching {
            val arr = JSONArray(raw)
            val l = (0 until arr.length()).map { arr.getJSONObject(it) }.map {
                Profile(it.getString("id"), it.getString("name"), ProfileData.decode(it.optJSONObject("prefs") ?: JSONObject()))
            }
            if (l.any { it.main }) return l
        }
        // обновление со старой версии (или повреждённое хранилище): «Основной» из текущих настроек
        val l = listOf(Profile(MAIN, context.getString(R.string.profile_main), capture()))
        write(l, MAIN)
        return l
    }

    private fun write(list: List<Profile>, active: String) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("prefs", ProfileData.encode(it.data))) }
        val gen = if (active != activeId()) store.getInt("gen", 0) + 1 else store.getInt("gen", 0)
        store.edit().putString("list", arr.toString()).putString("active", active).putInt("gen", gen).commit()
    }

    private fun capture(): Map<String, Any> = live.all.filterKeys(ProfileData::isProfileKey).mapNotNull { (k, v) -> v?.let { k to it } }.toMap()

    /** Заполнить prefs снимком: ключи профиля, которых в снимке нет, сбрасываются к умолчанию. Значение
     * не того типа, что уже лежит в prefs (чужой или повреждённый файл), пропускается — геттер Prefs упал бы. */
    private fun load(data: Map<String, Any>) {
        val cur = live.all
        val e = live.edit()
        cur.keys.filter { ProfileData.isProfileKey(it) && it !in data }.forEach { e.remove(it) }
        for ((k, v) in data) {
            if (!ProfileData.isProfileKey(k)) continue
            val old = cur[k]
            if (old != null && old::class != v::class && !(old is Set<*> && v is Set<*>)) continue
            when (v) {
                is String -> e.putString(k, v)
                is Boolean -> e.putBoolean(k, v)
                is Int -> e.putInt(k, v)
                is Long -> e.putLong(k, v)
                is Float -> e.putFloat(k, v)
                is Set<*> -> e.putStringSet(k, v.map { it.toString() }.toSet())
            }
        }
        e.commit()
    }

    private fun notifyTile() {
        if (Build.VERSION.SDK_INT >= 24) runCatching {
            TileService.requestListeningState(context, ComponentName(context, ProfileTileService::class.java))
        }
    }

    companion object {
        const val MAIN = "main"
        private val LOCK = Any()
    }
}

/** Снимок prefs в JSON с типами (Int/Long/Float иначе не различить после разбора) — без Context, для тестов. */
object ProfileData {
    /** Не настройки: служебное, журнал отправителей, пакеты-чтецы этого телефона, состояние экрана. */
    private val NOT_PROFILE = setOf("recent_callers", "setup_shown", "system_dicts_at", "names_dict_made", "sr_force", "sr_never",
        "preview_text", "dict_preview_text", "audit_sort_alpha", "audit_replace", "replace_stress_open", "replace_sample_open")
    private val NOT_PROFILE_PREFIX = listOf("dict_cur_", "dict_scope_", "accent_book_")

    fun isProfileKey(key: String) = key !in NOT_PROFILE && NOT_PROFILE_PREFIX.none { key.startsWith(it) }

    /** Строка, логическое и набор строк — как есть; числа — {"i":…}, {"l":…}, {"f":…}. */
    fun encode(data: Map<String, Any>): JSONObject = JSONObject().also { o ->
        for ((k, v) in data) o.put(k, when (v) {
            is String, is Boolean -> v
            is Int -> JSONObject().put("i", v)
            is Long -> JSONObject().put("l", v)
            is Float -> JSONObject().put("f", v.toDouble())
            is Set<*> -> JSONArray(v.map { it.toString() }.sorted())
            else -> continue
        })
    }

    fun decode(o: JSONObject): Map<String, Any> = o.keys().asSequence().mapNotNull { k ->
        val v: Any? = when (val raw = o.get(k)) {
            is String, is Boolean -> raw
            is JSONArray -> (0 until raw.length()).map { raw.optString(it) }.toSet()
            is JSONObject -> when {
                raw.has("i") -> raw.optInt("i")
                raw.has("l") -> raw.optLong("l")
                raw.has("f") -> raw.optDouble("f").toFloat()
                else -> null
            }
            else -> null
        }
        v?.let { k to it }
    }.toMap()
}

/** Окно настроек следит за сменой профиля со стороны (плитка в шторке): при возврате в окно или сразу,
 * если окно на экране, зовёт [onChange] — поля на экране от прежнего профиля. */
class ProfileWatch(private val activity: android.app.Activity, private val onChange: () -> Unit) {
    private val store by lazy { activity.getSharedPreferences("ruvoice_profiles", Context.MODE_PRIVATE) }
    private var seen = -1
    private var fired = false
    private val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key -> if (key == "gen") check() }

    fun resume() {
        if (seen < 0) seen = store.getInt("gen", 0)
        check()
        store.registerOnSharedPreferenceChangeListener(listener)
    }

    fun pause() = store.unregisterOnSharedPreferenceChangeListener(listener)

    private fun check() {
        if (fired || store.getInt("gen", 0) == seen || activity.isFinishing) return
        fired = true; onChange()
    }
}
