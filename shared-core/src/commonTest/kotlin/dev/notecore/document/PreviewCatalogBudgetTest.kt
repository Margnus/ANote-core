package dev.notecore.document

import kotlin.test.*

class PreviewCatalogBudgetTest {
    @Test fun completeDiskAndRetainedOverlayFitDeclaredResultQuotaWithoutTruncation() {
        val count = 2000 + 8
        val charge = 512L + count * (512L + 2L * (128 + 125))
        assertEquals(charge, PreviewCatalogBudget.resultByteCharge(count, count.toLong() * 253))
        assertTrue(charge <= 2L * 1024 * 1024)
        assertEquals(0L, PreviewCatalogBudget.resultByteCharge(0, 0))
        assertEquals(512L + 512 + 2 * (9 + 5), PreviewCatalogBudget.resultByteCharge(1, 14))
    }
    @Test fun pathReservationCoversBothResultSnapshotsAndChangesBeforeWorkerAdmission() {
        val short = PreviewCatalogBudget.workByteCharge(100)
        val long = PreviewCatalogBudget.workByteCharge(1100)
        assertEquals(65536L + 2 * 2097152L + 2000 * (2048L + 8 * (100 + 133)), short)
        assertEquals(2000L * 8 * 1000, long - short)
        assertTrue(short > 2L * 1024 * 1024)
        assertTrue(PreviewCatalogBudget.workByteCharge(Int.MAX_VALUE) > 256L * 1024 * 1024)
    }
    @Test fun invalidCountsAndTextNeverProduceAChargeForPartialCatalogs() {
        for (count in listOf(-1, 2009, Int.MAX_VALUE))
            assertFailsWith<IllegalArgumentException> { PreviewCatalogBudget.resultByteCharge(count, 0) }
        assertFailsWith<IllegalArgumentException> { PreviewCatalogBudget.resultByteCharge(1, -1) }
        assertFailsWith<IllegalArgumentException> { PreviewCatalogBudget.resultByteCharge(1, 254) }
        assertFailsWith<IllegalArgumentException> { PreviewCatalogBudget.resultByteCharge(0, 1) }
        assertFailsWith<IllegalArgumentException> { PreviewCatalogBudget.workByteCharge(-1) }
    }
}
