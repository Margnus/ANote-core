package dev.notecore.document

/** Unfrozen preview metadata sizing, not a heap measurement or production paged catalog.
 * Platform enumerators/URL internals remain outside this declared source-storage model.
 */
object PreviewCatalogBudget {
    val maxDiskRows: Int = 2000
    val maxOwnerRows: Int = 2008 // Original ledger can overlay at most eight retained documents.
    val resultByteLimit: Long = 2L * 1024 * 1024

    /** 512 per row covers declared summary/container/sort-reference metadata; text is UTF-16.
     * Caller validates individual IDs <=128 and displayed titles <=125 before allocation/adoption.
     */
    fun resultByteCharge(rows: Int, textCodeUnits: Long): Long {
        require(rows in 0..maxOwnerRows && textCodeUnits in 0..rows.toLong() * 253)
        return if (rows == 0) 0L else 512L + rows * 512L + textCodeUnits * 2L
    }

    /** Reserve before enumeration: bounded path/URL/ID staging, one header workspace,
     * and old/new owner result overlap. Long arithmetic is safe for every nonnegative Int.
     * The original worker rejects work above its own maximum; no new queue is created.
     */
    fun workByteCharge(directoryPathCodeUnits: Int): Long {
        require(directoryPathCodeUnits >= 0)
        return 64L * 1024 + resultByteLimit * 2 + maxDiskRows *
            (2048L + 8L * (directoryPathCodeUnits.toLong() + 133))
    }
}
