package dev.notecore.document

import kotlin.test.*

class ImageDecodeRulesTest {
    private fun plan(w: Int = 257, h: Int = 193, o: Int = 1, rw: Int = 60, rh: Int = 40, bytes: Long = 1_000_000) =
        ImageDecodeRules.plan(w, h, o, rw, rh, Long.MAX_VALUE, bytes)

    @Test fun oddDimensionsUseFloorForQualityAndCeilForMemory() {
        val p = assertNotNull(plan().plan)
        assertEquals(4, p.sampleSize); assertEquals(65, p.maxWidth); assertEquals(49, p.maxHeight)
        assertEquals(65L * 49 * 4, p.argbByteUpperBound); assertFalse(p.sourceLimited)
        assertEquals(1, assertNotNull(plan(rw = 129, rh = 90).plan).sampleSize)
    }
    @Test fun rotatedDisplayRequestUsesSwappedSourceAxes() {
        for (orientation in 5..8) assertEquals(4, assertNotNull(plan(o = orientation, rw = 40, rh = 60).plan).sampleSize)
        assertEquals(2, assertNotNull(plan(o = 1, rw = 40, rh = 60).plan).sampleSize)
    }
    @Test fun exactBudgetAcceptedButOneByteLessDoesNotSilentlyLowerQuality() {
        val p = assertNotNull(plan().plan)
        assertEquals(p, plan(bytes = p.argbByteUpperBound).plan)
        val refused = plan(bytes = p.argbByteUpperBound - 1)
        assertNull(refused.plan); assertEquals(ImageDecodeFailure.DECODE_CAPACITY, refused.failure)
        val limited = assertNotNull(plan(rw = 500, rh = 500).plan)
        assertEquals(1, limited.sampleSize); assertTrue(limited.sourceLimited)
    }
    @Test fun invalidInputsAndOriginalPixelCapacityRefuseBeforePlanning() {
        assertEquals(ImageDecodeFailure.INVALID_DIMENSIONS, plan(w = 0).failure)
        assertEquals(ImageDecodeFailure.INVALID_DIMENSIONS, plan(rw = -1).failure)
        assertEquals(ImageDecodeFailure.UNKNOWN_ORIENTATION, plan(o = 9).failure)
        assertEquals(ImageDecodeFailure.INVALID_BUDGET, plan(bytes = 0).failure)
        assertEquals(ImageDecodeFailure.SOURCE_CAPACITY, ImageDecodeRules.plan(257,193,1,60,40,257L*193-1,1000000).failure)
    }
    @Test fun hugeInputsDoNotOverflowPixelsBytesOrPowerOfTwoSampling() {
        assertEquals(ImageDecodeFailure.DECODE_CAPACITY, plan(Int.MAX_VALUE, Int.MAX_VALUE, rw = Int.MAX_VALUE,
            rh = Int.MAX_VALUE, bytes = Long.MAX_VALUE).failure)
        val small = assertNotNull(plan(Int.MAX_VALUE, Int.MAX_VALUE, rw = 1, rh = 1, bytes = 16).plan)
        assertEquals(1 shl 30, small.sampleSize); assertEquals(2, small.maxWidth); assertEquals(16, small.argbByteUpperBound)
    }
}
