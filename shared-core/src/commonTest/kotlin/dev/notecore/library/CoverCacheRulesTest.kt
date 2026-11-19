package dev.notecore.library

import kotlin.test.*

class CoverCacheRulesTest {
    private fun derive(noteId: String = "note-1", revision: Long = 5,
                       width: Int = 200, height: Int = 260, scale: Double = 1.0) =
        CoverCacheRules.derive(noteId, revision, width, height, scale)

    @Test fun sameInputsProduceSameKey() {
        val a = assertNotNull(derive().key)
        val b = assertNotNull(derive().key)
        assertEquals(a, b)
        assertFalse(CoverCacheRules.isStale(a, b))
        assertTrue(CoverCacheRules.sameBucket(a, b))
    }

    @Test fun revisionChangeMakesKeyStale() {
        val old = assertNotNull(derive(revision = 5).key)
        val new = assertNotNull(derive(revision = 6).key)
        assertTrue(CoverCacheRules.isStale(old, new))
        assertFalse(CoverCacheRules.sameBucket(old, new))
    }

    @Test fun noteIdChangeMakesKeyStale() {
        val a = assertNotNull(derive(noteId = "note-1").key)
        val b = assertNotNull(derive(noteId = "note-2").key)
        assertTrue(CoverCacheRules.isStale(a, b))
        assertFalse(CoverCacheRules.sameBucket(a, b))
    }

    @Test fun scaleWithinSameBucketSharesKey() {
        val a = assertNotNull(derive(scale = 1.0, width = 200, height = 260).key)
        val b = assertNotNull(derive(scale = 1.1, width = 200, height = 260).key)
        assertEquals(a.bucketWidth, b.bucketWidth)
        assertEquals(a.bucketHeight, b.bucketHeight)
        assertEquals(a, b)
        assertFalse(CoverCacheRules.isStale(a, b))
        assertTrue(CoverCacheRules.sameBucket(a, b))
    }

    @Test fun scaleAcrossBucketBoundaryMakesKeyStale() {
        val a = assertNotNull(derive(scale = 1.0, width = 200, height = 260).key)
        val b = assertNotNull(derive(scale = 2.0, width = 200, height = 260).key)
        assertTrue(a.bucketWidth != b.bucketWidth || a.bucketHeight != b.bucketHeight)
        assertTrue(CoverCacheRules.isStale(a, b))
        assertFalse(CoverCacheRules.sameBucket(a, b))
    }

    @Test fun bucketAlignsToMultipleOfSize() {
        val key = assertNotNull(derive(width = 200, height = 260, scale = 1.0).key)
        assertEquals(0, key.bucketWidth % CoverCacheRules.BUCKET_SIZE)
        assertEquals(0, key.bucketHeight % CoverCacheRules.BUCKET_SIZE)
        assertEquals(256, key.bucketWidth)
        assertEquals(320, key.bucketHeight)
    }

    @Test fun invalidNoteIdDimensionAndScaleAreTyped() {
        for (bad in listOf("", " ", "x".repeat(129), "\uD800")) {
            assertEquals(CoverCacheFailure.INVALID_NOTE_ID, derive(noteId = bad).failure)
        }
        assertEquals(CoverCacheFailure.INVALID_DIMENSIONS, derive(revision = -1).failure)
        for (bad in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY, 9.0)) {
            assertEquals(CoverCacheFailure.INVALID_SCALE, derive(scale = bad).failure)
        }
        assertEquals(CoverCacheFailure.INVALID_DIMENSIONS, derive(width = 0, height = 100).failure)
        assertEquals(CoverCacheFailure.INVALID_DIMENSIONS, derive(width = 100, height = 5000).failure)
        assertEquals(CoverCacheFailure.INVALID_DIMENSIONS, derive(width = 5, height = 100).failure)
    }

    @Test fun differentPageRevisionsNeverShareCache() {
        val keys = (0L..10L).map { assertNotNull(derive(revision = it).key) }
        assertEquals(keys.size, keys.toSet().size)
        for (i in keys.indices) for (j in keys.indices) {
            if (i != j) assertTrue(CoverCacheRules.isStale(keys[i], keys[j]))
        }
    }
}
