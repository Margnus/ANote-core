package dev.notecore.app

import android.content.Context
import dev.notecore.document.PenSlotRules
import dev.notecore.document.PenSlotState

/** Preview-only host persistence for the active pen slots; never document content. */
internal class PenSlotPreferences(context: Context) {
    private val values = context.applicationContext.getSharedPreferences("notecore-preview-pens", Context.MODE_PRIVATE)

    fun load(): PenSlotState = PenSlotRules.decode(values.getString(KEY, null)) ?: PenSlotRules.defaults()

    fun save(state: PenSlotState) {
        val encoded = PenSlotRules.encode(state) ?: return
        values.edit().putString(KEY, encoded).apply()
    }

    private companion object {
        const val KEY = "slots-v1"
    }
}
