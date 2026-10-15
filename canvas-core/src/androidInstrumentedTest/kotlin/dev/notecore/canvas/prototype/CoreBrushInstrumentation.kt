package dev.notecore.canvas.prototype

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Test-only library host: actual common algorithms on ART, with no app UI or SDK probe API. */
class CoreBrushInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val output = Bundle()
        try {
            val result = JUnitCore.runClasses(BrushEnvelopePocTest::class.java, BrushPathPocTest::class.java,
                BrushReductionPocTest::class.java, ChunkedBrushPocTest::class.java)
            check(result.wasSuccessful() && result.runCount == 26 && result.ignoreCount == 0) {
                "run=${result.runCount} ignored=${result.ignoreCount} failures=${result.failures.joinToString { it.trace }}"
            }
            output.putInt("common_brush_tests", result.runCount)
            output.putInt("common_brush_ignored", result.ignoreCount)
            output.putString("common_brush_status", "passed")
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("common_brush_status", "failed")
            output.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
