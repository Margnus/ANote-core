package dev.notecore.document

/** UTF-16 scalar validity, not normalization or a change to the stored character sequence. */
internal fun String.hasValidUnicode(): Boolean {
    var index = 0
    while (index < length) {
        val c = this[index++]
        if (c.isHighSurrogate()) {
            if (index == length || !this[index++].isLowSurrogate()) return false
        } else if (c.isLowSurrogate()) return false
    }
    return true
}
