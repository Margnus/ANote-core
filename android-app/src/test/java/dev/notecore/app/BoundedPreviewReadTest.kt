package dev.notecore.app

import dev.notecore.document.PagedPreviewCodec
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Test
import kotlin.test.*

/** Synthetic streams on host JVM, not ContentProvider/ART or filesystem acceptance. */
class BoundedPreviewReadTest {
    private class ZeroProgress(private val prefix: ByteArray = byteArrayOf()) : InputStream() {
        var calls = 0
        var closed = false
        override fun read(): Int = error("Bulk read expected")
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            calls++
            if (calls == 1 && prefix.isNotEmpty()) { prefix.copyInto(b, off); return prefix.size }
            if (calls > (if (prefix.isEmpty()) 1 else 2)) error("Retried a non-progressing stream")
            return 0
        }
        override fun close() { closed = true }
    }
    @Test fun zeroProgressIsIoFailureOnFirstObservationAndCallerClosesStream() {
        val input = ZeroProgress()
        assertFailsWith<IOException> { readBoundedPreviewBytes(input) }
        assertEquals(1, input.calls); assertFalse(input.closed)
        input.close(); assertTrue(input.closed)
    }

    @Test fun partialPrefixIsNeverReturnedAfterZeroProgressAndUseClosesOnFailure() {
        val input = ZeroProgress(byteArrayOf(1, 2, 3))
        assertFailsWith<IOException> { input.use(::readBoundedPreviewBytes) }
        assertEquals(2, input.calls); assertTrue(input.closed)
    }

    @Test fun fragmentedReadsAndEmptyEofPreserveEveryByte() {
        val bytes = ByteArray(25001) { (it % 251).toByte() }
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(7, len))
        }
        assertContentEquals(bytes, readBoundedPreviewBytes(input))
        assertContentEquals(byteArrayOf(), readBoundedPreviewBytes(ByteArrayInputStream(byteArrayOf())))
    }

    @Test fun actualByteCapacityAllowsExactLimitAndRefusesTheNextByte() {
        fun generated(size: Int) = object : InputStream() {
            var remaining = size
            override fun read(): Int = error("Bulk read expected")
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (remaining == 0) return -1
                val count = minOf(remaining, len); b.fill(0x5a, off, off + count); remaining -= count; return count
            }
        }
        val bytes = readBoundedPreviewBytes(generated(PagedPreviewCodec.MAX_UTF8_BYTES))
        assertEquals(PagedPreviewCodec.MAX_UTF8_BYTES, bytes.size)
        assertEquals(0x5a.toByte(), bytes.first()); assertEquals(0x5a.toByte(), bytes.last())
        val refusal = assertFailsWith<IllegalArgumentException> { readBoundedPreviewBytes(generated(PagedPreviewCodec.MAX_UTF8_BYTES + 1)) }
        assertEquals("BYTE_CAPACITY", refusal.message)
    }

    @Test fun originalReadExceptionIsPropagatedWithoutRetryOrPartialSuccess() {
        val failure = IOException("provider failed")
        val input = object : InputStream() {
            override fun read(): Int = throw failure
            override fun read(b: ByteArray, off: Int, len: Int): Int = throw failure
        }
        assertSame(failure, assertFailsWith<IOException> { readBoundedPreviewBytes(input) })
        assertContentEquals(byteArrayOf(4), readBoundedPreviewBytes(ByteArrayInputStream(byteArrayOf(4))))
    }
}
