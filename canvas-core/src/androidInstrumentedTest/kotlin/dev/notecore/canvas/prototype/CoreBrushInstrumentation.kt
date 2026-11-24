package dev.notecore.canvas.prototype

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Test-only library host: actual common algorithms on ART, with no app UI or SDK probe API. */
class CoreBrushInstrumentation : Instrumentation() {
    private var sweptDiscOnly = false
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        sweptDiscOnly = arguments?.getString("notecore_case") == "swept_disc"
        start()
    }
    override fun onStart() {
        val output = Bundle()
        try {
            if (sweptDiscOnly) {
                val result = JUnitCore.runClasses(SweptDiscGeometryPocTest::class.java,
                    BrushCandidateSeparationTest::class.java, BrushEnvelopePocTest::class.java,
                    BrushGeometryTest::class.java, BrushPathPlanTest::class.java)
                check(result.wasSuccessful() && result.runCount == 39 && result.ignoreCount == 0) {
                    "run=${result.runCount} ignored=${result.ignoreCount} failures=${result.failures.joinToString { it.trace }}"
                }
                output.putInt("swept_disc_tests", result.runCount)
                output.putInt("swept_disc_ignored", result.ignoreCount)
                output.putString("swept_disc_status", "passed")
                finish(Activity.RESULT_OK, output)
                return
            }
            BrushTileBitmapTest.observations.clear()
            val result = JUnitCore.runClasses(GeometryContractTest::class.java, BrushEnvelopePocTest::class.java, BrushPathPocTest::class.java,
                BrushReductionPocTest::class.java, ChunkedBrushPocTest::class.java,
                SpatialIndexPocTest::class.java, IncrementalSpatialIndexPocTest::class.java,
                BrushTileCachePocTest::class.java, ResourceLeaseContractTest::class.java,
                WeightedLruContractTest::class.java, BrushTilePixelsPocTest::class.java, BrushTileBitmapTest::class.java,
                BrushRenderOwnerPocTest::class.java, BrushRenderExecutorPocTest::class.java, AndroidBrushImagePocTest::class.java, RenderViewportTraceTest::class.java,
                InputReplayContractTest::class.java, InputArenaContractTest::class.java, InputScalarReplayTest::class.java)
            check(result.wasSuccessful() && result.runCount == 149 && result.ignoreCount == 0) {
                "run=${result.runCount} ignored=${result.ignoreCount} failures=${result.failures.joinToString { it.trace }}"
            }
            output.putString("render_viewport_trace", checkNotNull(RenderViewportTraceTest.observation))
            output.putInt("common_brush_tests", result.runCount)
            output.putInt("common_brush_ignored", result.ignoreCount)
            output.putString("common_brush_status", "passed")
            output.putString("native_image_observations", BrushTileBitmapTest.observations.joinToString(prefix = "[", postfix = "]"))
            finish(Activity.RESULT_OK, output)
        } catch (failure: Throwable) {
            output.putString("common_brush_status", "failed")
            output.putString("failure", failure.stackTraceToString())
            finish(Activity.RESULT_CANCELED, output)
        }
    }
}
