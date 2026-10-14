package dev.notecore.canvas.prototype

import kotlin.test.*

class SceneCompositionTest {
    private val id = SceneId("document")
    private fun objectAt(key: String = "stroke", revision: Long = 1): StrokeObject = StrokeObject(ObjectId(key), revision, LayerId("layer"),
        BrushSpec(BrushKind.MONOLINE, 1, 2.0, 0xff000000.toInt()),
        StrokeSamples.copyOf(doubleArrayOf(1.0), doubleArrayOf(2.0), floatArrayOf(0.5f), longArrayOf(1)))
    @Test fun metadataOnlyPatchAdvancesOneCanonicalRevisionAndKeepsPriorSnapshotImmutable() {
        val initial = SceneSnapshot.empty(id)
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(initial, CommandId("page-order"), emptyList())).patch
        val next = assertIs<SceneApplication.Applied>(SceneReducer.apply(initial, patch)).snapshot
        assertEquals(0L, initial.revision); assertEquals(1L, next.revision); assertEquals(0, next.objectCount)
        assertIs<SceneApplication.Rejected>(SceneReducer.apply(next, patch)).also { assertEquals(Rejection.STALE_SCENE, it.reason) }
    }
    @Test fun multipleObjectChangesApplyAtomicallyAndDefensivelyOwnTheProposedList() {
        val initial = SceneSnapshot.empty(id); val a = objectAt("a"); val b = objectAt("b")
        val changes = mutableListOf(ObjectChange(null, a), ObjectChange(null, b))
        val patch = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(initial, CommandId("copy-page"), changes)).patch
        changes.clear()
        val next = assertIs<SceneApplication.Applied>(SceneReducer.apply(initial, patch)).snapshot
        assertEquals(2, next.objectCount); assertEquals(1L, next.revision); assertNull(initial.find(a.id)); assertSame(a, next.find(a.id))
        val deletion = assertIs<PrepareResult.Prepared>(SceneReducer.prepareChanges(next, CommandId("delete-page"), listOf(ObjectChange(a, null), ObjectChange(b, null)))).patch
        val empty = assertIs<SceneApplication.Applied>(SceneReducer.apply(next, deletion)).snapshot
        assertEquals(0, empty.objectCount); assertEquals(2, next.objectCount); assertEquals(2L, empty.revision)
    }
    @Test fun invalidChangeSetNeverPartiallyAppliesOrAcceptsObjectRevisionRollback() {
        val a = objectAt(); val scene = SceneSnapshot(id, 4, mapOf(a.id to a))
        listOf(listOf(ObjectChange(a, null), ObjectChange(a, null)), listOf(ObjectChange(null, null)),
            listOf(ObjectChange(a, objectAt("another"))), listOf(ObjectChange(a, objectAt(revision = 1)))).forEach {
            assertIs<PrepareResult.Rejected>(SceneReducer.prepareChanges(scene, CommandId("invalid"), it))
        }
        assertSame(a, scene.find(a.id)); assertEquals(4L, scene.revision)
        val exhausted = SceneSnapshot(id, Long.MAX_VALUE, emptyMap())
        assertEquals(Rejection.REVISION_EXHAUSTED,
            assertIs<PrepareResult.Rejected>(SceneReducer.prepareChanges(exhausted, CommandId("last"), emptyList())).reason)
    }
}
