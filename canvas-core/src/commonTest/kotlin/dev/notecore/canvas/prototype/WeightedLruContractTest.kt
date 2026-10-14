package dev.notecore.canvas.prototype

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightedLruContractTest {
    @Test fun repeatedKeyLegacyCounterexampleStaysBounded() {
        val cache = WeightedLruCache<String, String>(1) { 1 }
        cache.put("A", "first"); cache.put("A", "replacement"); cache.put("B", "b"); cache.put("C", "c")
        assertEquals(1, cache.size); assertEquals(1L, cache.residentBytes)
        assertEquals("c", cache.get("C")); assertNull(cache.get("A")); assertNull(cache.get("B"))
    }

    @Test fun readsChangeEvictionOrder() {
        val cache = WeightedLruCache<String, Int>(2) { 1 }
        cache.put("A", 1); cache.put("B", 2); cache.get("A")
        val result = assertIs<CacheAdmission.Stored<String, Int>>(cache.put("C", 3))
        assertEquals(listOf("B"), result.displaced.map { it.key })
        assertEquals(1, cache.get("A")); assertNull(cache.get("B"))
    }

    @Test fun oversizedReplacementDoesNotDestroyExistingEntry() {
        val cache = WeightedLruCache<String, Long>(8) { it }
        cache.put("A", 4)
        assertIs<CacheAdmission.TooLarge<String, Long>>(cache.put("A", 9))
        assertEquals(4L, cache.get("A")); assertEquals(4L, cache.residentBytes)
    }

    @Test fun invalidWeightIsRejectedBeforeMutation() {
        val cache = WeightedLruCache<String, Long>(8) { it }
        cache.put("A", 4)
        assertIs<CacheAdmission.InvalidWeight<String, Long>>(cache.put("A", -1))
        assertEquals(4L, cache.get("A"))
    }

    @Test fun admissionArithmeticDoesNotOverflow() {
        val cache = WeightedLruCache<String, Long>(Long.MAX_VALUE) { it }
        cache.put("A", Long.MAX_VALUE - 1); cache.put("B", 2)
        assertEquals(2L, cache.residentBytes); assertNull(cache.get("A"))
    }

    @Test fun seededRepeatedUpdatesNeverExceedBudget() {
        val cache = WeightedLruCache<Int, Long>(64) { it }
        val random = Random(20261014)
        repeat(10_000) {
            val key = random.nextInt(0, 16)
            if (random.nextBoolean()) cache.get(key) else cache.put(key, random.nextLong(1, 80))
            assertTrue(cache.residentBytes in 0L..64L)
            assertTrue(cache.size <= 16)
        }
        val cleared = cache.clear()
        assertEquals(cleared.size, cleared.map { it.key }.toSet().size)
        assertEquals(0L, cache.residentBytes); assertEquals(0, cache.size)
    }
}
