package dev.notecore.document

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore
import dev.notecore.library.LibraryFolderTrashRulesTest
import dev.notecore.library.LibraryTrashRulesTest
import dev.notecore.library.LibraryBatchRulesTest

/** Isolated library-test host; consumes the original common tests, never the user's preview data. */
class DocumentOrderInstrumentation : Instrumentation() {
    private var suite = "document-order"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        suite = arguments?.getString("suite") ?: "document-order"
        start()
    }
    override fun onStart() {
        val output = Bundle()
        try {
            val selected = when (suite) {
                "document-order" -> arrayOf(ObjectOrderRulesTest::class.java, ObjectOrderAuthorityTest::class.java,
                    DocumentHistoryTest::class.java, DocumentAuthorityTest::class.java, RetiredEditingStateTest::class.java)
                "text-payload" -> arrayOf(TextPayloadDraftTest::class.java)
                "document-reader" -> arrayOf(PreviewDocumentReaderTest::class.java, PreviewRecoveryReaderTest::class.java)
                "folder-trash" -> arrayOf(LibraryFolderTrashRulesTest::class.java, LibraryTrashRulesTest::class.java,
                    LibraryBatchRulesTest::class.java)
                else -> error("Unknown document test suite: $suite")
            }
            val result = JUnitCore.runClasses(*selected)
            output.putString("document_suite", suite)
            output.putInt("document_tests", result.runCount)
            output.putInt("document_ignored", result.ignoreCount)
            check(result.wasSuccessful()) { result.failures.joinToString("\n") }
            output.putString("document_status", "passed")
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("document_status", "failed")
            output.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
