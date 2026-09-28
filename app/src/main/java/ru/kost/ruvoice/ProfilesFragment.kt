package ru.kost.ruvoice

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.RadioButton
import android.widget.RadioGroup
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * Вкладка «Профили»: выбрать, добавить, переименовать, удалить. Своих полей в Prefs у неё нет, поэтому не
 * PageFragment. Выбор, добавление и удаление выбранного меняют prefs целиком — окно пересоздаётся
 * (SettingsActivity.restartForProfile), как после импорта.
 */
class ProfilesFragment : Fragment(R.layout.fragment_profiles) {
    private val profiles by lazy { Profiles(requireContext()) }
    private val host get() = activity as SettingsActivity

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        view.markHeadings()
        view.findViewById<Button>(R.id.profileAdd).setOnClickListener {
            nameDialog(R.string.profile_add_title, "", null, getString(R.string.profile_add_hint)) { name ->
                host.saveAllVisiblePages()
                val p = profiles.add(name)
                host.restartForProfile(getString(R.string.profile_switched, p.name))
            }
        }
        fill(view)
    }

    private fun fill(view: View) {
        val list = view.findViewById<RadioGroup>(R.id.profilesList)
        list.removeAllViews()
        val active = profiles.active().id
        for (p in profiles.list()) {
            val row = layoutInflater.inflate(R.layout.item_profile, list, false)
            row.findViewById<RadioButton>(R.id.profileName).apply {
                text = p.name
                isChecked = p.id == active
                setOnClickListener {
                    if (p.id == profiles.active().id) return@setOnClickListener
                    host.saveAllVisiblePages()
                    profiles.switchTo(p.id)
                    host.restartForProfile(getString(R.string.profile_switched, p.name))
                }
            }
            // TalkBack: у каждой строки свои кнопки — без имени профиля их не различить
            row.findViewById<Button>(R.id.profileRename).apply {
                contentDescription = getString(R.string.profile_rename_named, p.name)
                setOnClickListener {
                    nameDialog(R.string.profile_rename_title, p.name, p.id, null) { name -> profiles.rename(p.id, name); fill(view) }
                }
            }
            row.findViewById<Button>(R.id.profileDelete).apply {
                visibility = if (p.main) View.GONE else View.VISIBLE
                contentDescription = getString(R.string.profile_delete_named, p.name)
                setOnClickListener {
                    MaterialAlertDialogBuilder(requireContext()).setTitle(R.string.profile_delete_title)
                        .setMessage(getString(R.string.profile_delete_confirm, p.name))
                        .setPositiveButton(R.string.delete) { _, _ ->
                            if (p.id == profiles.active().id) {
                                // удаляем выбранный — включится «Основной», правки вкладок уходят в удаляемый
                                host.saveAllVisiblePages()
                                profiles.delete(p.id)
                                host.restartForProfile(getString(R.string.profile_switched, profiles.active().name))
                            } else {
                                profiles.delete(p.id); fill(view)
                                host.snack(getString(R.string.profile_deleted))
                            }
                        }
                        .setNegativeButton(R.string.cancel, null).show()
                }
            }
            list.addView(row)
        }
    }

    /** Имя профиля: непустое и не занятое другим профилем (без учёта регистра). */
    private fun nameDialog(titleRes: Int, initial: String, id: String?, message: String?, onOk: (String) -> Unit) {
        val ctx = requireContext()
        val layout = TextInputLayout(ctx, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = getString(R.string.profile_name_hint)
            val pad = (12 * resources.displayMetrics.density).toInt(); setPadding(pad, pad / 2, pad, 0)
        }
        val field = TextInputEditText(layout.context).apply { setText(initial); setSelection(initial.length) }
        layout.addView(field)
        var posButton: Button? = null
        fun validate() {
            val name = field.text.toString().trim()
            layout.error = if (name.isNotEmpty() && !profiles.nameFree(name, id)) getString(R.string.profile_name_taken) else null
            posButton?.isEnabled = name.isNotEmpty() && name != initial && layout.error == null
        }
        field.doAfterTextChanged { validate() }
        val b = MaterialAlertDialogBuilder(ctx).setTitle(titleRes).setView(layout)
        if (message != null) b.setMessage(message)
        val dialog = b.setPositiveButton(R.string.save) { _, _ -> onOk(field.text.toString().trim()) }
            .setNegativeButton(R.string.cancel, null).create()
        dialog.setOnShowListener { posButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE); validate() }
        field.submits { posButton }
        dialog.show()
    }
}
