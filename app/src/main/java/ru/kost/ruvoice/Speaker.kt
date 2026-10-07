package ru.kost.ruvoice

/** Голос по имени из Prefs или от читалки: штатный («xenia») или из пака («cis_ru/ru_alexandr»).
 * В сервисе класс android.speech.tts.Voice, поэтому не Voice. */
class Speaker(val name: String, val pack: Pack?, val id: Int, val sym: Symbols, val types: Boolean) {
    companion object {
        const val DEFAULT = "baya"
        /** Пак штатной модели для сборки lite: голые имена («xenia» из старых prefs) ищем в нём. */
        const val RU_PACK = "ru"
        /** Есть ли модель в APK (сборка full). Тесты подменяют. */
        var builtin = BuildConfig.BUILTIN_MODEL
        private fun packName(pack: Pack, speaker: String) = "${pack.id}/$speaker"
        private fun of(pack: Pack, speaker: String): Speaker? = pack.speakers[speaker]?.let { Speaker(packName(pack, speaker), pack, it, pack.sym, pack.types) }

        fun resolve(name: String?, d: SileroData, packs: List<Pack>): Speaker? {
            if (name == null || name !in FEMALE) return null
            if (builtin) {
                return d.speakers[name]?.let { Speaker(name, null, it, d.sym, true) }
            }
            return packs.firstOrNull { it.id == RU_PACK }?.let { of(it, name) }
        }

        /** Голос по умолчанию: «xenia» (штатная или из пака ru), иначе первый голос первого пака; null — голосов нет. */
        fun default(d: SileroData, packs: List<Pack>): Speaker? =
            resolve(DEFAULT, d, packs) ?: names(d, packs).firstOrNull()?.let { resolve(it, d, packs) }

        /** Есть ли хоть один голос: встроенная модель или пак с голосами. Без разбора silero_ru.json. */
        fun hasVoices(packs: List<Pack>) = builtin || packs.any { it.speakers.isNotEmpty() }

        /** Есть ли голос — то же, что resolve(...) != null, но по одним именам штатных голосов (SileroModels.speakers):
         * без разбора всего silero_ru.json. */
        fun exists(name: String?, builtinNames: Set<String>, packs: List<Pack>): Boolean {
            if (name == null || name !in FEMALE) return false
            return if (builtin) {
                name in builtinNames
            } else {
                packs.firstOrNull { it.id == RU_PACK }?.speakers?.containsKey(name) == true
            }
        }

        /** Dream Pulse build: expose only the three female stock voices.
         * Male stock voices and additional packs are intentionally hidden. */
        private val FEMALE = listOf("baya", "kseniya", "xenia")

        fun names(d: SileroData, packs: List<Pack>): List<String> =
            names(d.speakers.keys, packs)

        fun names(builtinNames: Set<String>, packs: List<Pack>): List<String> =
            FEMALE.filter { name ->
                if (builtin) name in builtinNames
                else packs.firstOrNull { it.id == RU_PACK }?.speakers?.containsKey(name) == true
            }

        /** Голоса того же движка, что [main] — для прямой речи: в памяти одна тройка моделей,
         * перегружать 90 МБ на каждую реплику нельзя. */
        fun sameEngine(main: Speaker, d: SileroData, packs: List<Pack>): List<String> =
            names(d, packs)

        /** Имя для TTS API читалок: «xenia-ru», «marat-ru-cis». Без «/» и «_» (AlReaderX такие не опознаёт),
         * «ru» один раз: голос, язык, пак; в lite «ru/xenia» — «xenia-ru», как в full. */
        fun ttsName(name: String): String {
            val (pack, speaker) = name.split('/').let { if (it.size == 2) it else listOf("", it[0]) }
            fun parts(s: String) = s.split('_').filter { it.isNotEmpty() && it != "ru" }
            return (parts(speaker) + "ru" + parts(pack)).joinToString("-")
        }
        /** Обратно из [ttsName]; имя в старой форме «ru-ru-cis_ru/ru_marat» (до 0.15) — тоже. */
        fun fromTtsName(tts: String?, d: SileroData, packs: List<Pack>): String? = fromTtsName(tts, names(d, packs))
        fun fromTtsName(tts: String?, names: List<String>): String? =
            tts?.let { t -> names.firstOrNull { ttsName(it) == t } ?: t.removePrefix("ru-ru-") }

        /** Подпись в списке: «ru_alexandr (cis_ru)», штатные как есть. */
        fun label(name: String): String = name.indexOf('/').let { i -> if (i < 0) name else "${name.substring(i + 1)} (${name.substring(0, i)})" }
    }
}
