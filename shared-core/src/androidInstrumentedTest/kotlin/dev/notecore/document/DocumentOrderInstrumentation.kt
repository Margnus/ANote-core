package dev.notecore.document

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Isolated library-test host; consumes the original common tests, never the user's preview data. */
class DocumentOrderInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val result = JUnitCore.runClasses(ObjectOrderRulesTest::class.java, ObjectOrderAuthorityTest::class.java,
                DocumentHistoryTest::class.java, DocumentAuthorityTest::class.java, RetiredEditingStateTest::class.java)
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
