package dev.notecore.app

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.BaseAdapter
import android.widget.Button
import java.text.DateFormat
import java.util.Date

/** Native visible-row recycling; metadata list is bounded, no view is created per stored document. */
internal class NoteListAdapter(private val rowHeight: Int, private val createRow: () -> Button,
                               private val open: (String) -> Unit) : BaseAdapter() {
    private var notes: List<NoteSummary> = emptyList()
    private val dates = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
    internal var createdRowCount = 0; private set
    fun replace(value: List<NoteSummary>) {
        check(Looper.myLooper() == Looper.getMainLooper())
        require(value.size <= 2008) // 2000 disk rows plus at most 8 not-yet-persisted owner documents.
        notes = value.toList(); notifyDataSetChanged()
    }
    override fun getCount(): Int = notes.size
    override fun getItem(position: Int): NoteSummary = notes[position]
    override fun getItemId(position: Int): Long = position.toLong() // No claim of stable positional IDs.
    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        check(Looper.myLooper() == Looper.getMainLooper())
        val row = (convertView as? Button) ?: createRow().also {
            createdRowCount++; it.layoutParams = AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, rowHeight)
        }
        val note = notes[position]
        row.text = row.context.getString(R.string.note_list_row, note.title, dates.format(Date(note.modified)))
        row.setOnClickListener { open(note.id) } // Rebind immutable ID; recycled positions cannot open the old row.
        return row
    }
}
