package dev.notecore.app

import dev.notecore.document.AndroidPreviewTextCodec
import dev.notecore.document.PreviewReadFailure
import dev.notecore.document.PreviewRecoveryCodec
import java.io.Reader
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AndroidPreviewTextCodecTest {
    private val base = "NCPREVIEW\t2\nD\tnote\t标题😀\\t末尾\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
    private val ink = "NCPREVIEW\t2\nD\tnote\t\t1\t1\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\n" +
        "S\tstroke\tlayer\t1\tMONOLINE\t1\t-1\t2.5\t1\t1.0\t0.0\t0.0\t1.0\t0.0\t0.0\nP\t1.0\t2.0\t0.5\t1\nEND\n"
    private val metadata = base.substringAfter('\n').substringBefore('\n')
    private val framing = "${base.length}\t${ink.length}\t006f\n"
    private val checkpoint = "NCRECOVERY\t1\n$metadata\n$framing$base${ink}RECOVERY-END\n"

    @Test fun streamsTheExistingUtf16FrameAndUtf8Bytes() {
        val original = PreviewRecoveryCodec.decode(checkpoint)
        val reopened = assertNotNull(AndroidPreviewTextCodec.readCheckpoint(checkpoint.reader()).snapshot)
        assertTrue(original.sameContent(reopened))
        var declaredBytes = 0
        val streamed = buildString { AndroidPreviewTextCodec.write(reopened, { declaredBytes = it }) { append(it) } }
        assertEquals(checkpoint, streamed)
        assertEquals(checkpoint.toByteArray(Charsets.UTF_8).size, declaredBytes)
        assertEquals(PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT,
            AndroidPreviewTextCodec.readDocument(checkpoint.reader()).failure)
    }

    @Test fun rejectsTruncatedAndShiftedSectionsWithoutReturningTheBase() {
        for (bad in listOf(
            checkpoint.dropLast(1),
            checkpoint + "x",
            checkpoint.replace(framing, "${base.length + 1}\t${ink.length - 1}\t006f\n"),
            checkpoint.replaceBefore(framing, "NCRECOVERY\t1\nwrong\n"),
            checkpoint.replace(framing, "${base.toByteArray().size}\t${ink.length}\t006f\n")
        )) {
            assertEquals(PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT,
                AndroidPreviewTextCodec.readCheckpoint(bad.reader()).failure)
        }
    }

    @Test fun validationDoesNotReadAgainAfterEof() {
        val source = base.reader()
        val reader = object : Reader() {
            private var eof = false
            override fun read(cbuf: CharArray, off: Int, len: Int): Int {
                check(!eof)
                return source.read(cbuf, off, len).also { eof = it < 0 }
            }
            override fun close() = source.close()
        }
        assertNotNull(AndroidPreviewTextCodec.readDocument(reader).snapshot)
    }

    @Test fun frameAndMalformedTailAreCheckedAcrossReaderChunks() {
        val original = PreviewRecoveryCodec.decode(checkpoint)
        for (size in listOf(1, 2, 3, 7, 17, 64)) {
            fun source(text: String) = object : Reader() {
                val reader = text.reader()
                override fun read(buffer: CharArray, offset: Int, length: Int): Int =
                    reader.read(buffer, offset, minOf(size, length))
                override fun close() = reader.close()
            }
            val reopened = assertNotNull(AndroidPreviewTextCodec.readCheckpoint(source(checkpoint)).snapshot)
            assertTrue(original.sameContent(reopened))
            assertEquals(PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT,
                AndroidPreviewTextCodec.readCheckpoint(source(checkpoint + "\n")).failure)
        }
    }
}
