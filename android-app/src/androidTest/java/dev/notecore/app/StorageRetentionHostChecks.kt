package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import dev.notecore.document.*
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** Actual original store/worker/IO; only a uniquely named cache directory is modified. */
internal class StorageRetentionHostChecks(private val test: Instrumentation, private val host: Activity) {
    private fun main(action: () -> Unit) = test.runOnMainSync(action)
    private fun eventually(message: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 8000
        var done = false
        while (!done && SystemClock.uptimeMillis() < deadline) {
            main { done = predicate() }
            if (!done) SystemClock.sleep(20)
        }
        check(done) { message }
    }
    fun verify(): String {
        PendingRecoveryHostChecks(test, host).verify()
        ReadFailureHostChecks(test, host).verify()
        val directory = File(host.cacheDir, "storage-retention-${java.util.UUID.randomUUID()}")
        val moved = File(directory.path + "-saved")
        lateinit var store: NoteStore
        lateinit var note: PagedPreviewNotebook
        var completed = false
        val checks = mutableListOf("derived_text_capacity_checkpoint_save_and_reopen", "read_failure_classification_to_owner", "pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen")
        checks.add("import_refusal_preserves_source_and_retry_remaps_identity")
        main {
            store = NoteStore.isolated(host, directory); note = store.create()
            val original = note.snapshot()
            val creation = PagedPreviewNotebook.createValidated("bad", "title", "checked", width = Double.NaN, ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) })
            check(creation.notebook == null && creation.failure == PreviewOpenFailure.INVALID_PAGE)
            check(store.activeDocumentId == original.id.value && note.snapshot() === original)
            check(note.currentPageId.value.endsWith("-page-0")) // Store create consumed the factory notebook directly.
            val created = PagedPreviewNotebook.createValidated("independent", "合法标题", "checked", paper = PaperKind.GRID, ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) })
            val independent = checkNotNull(created.notebook)
            check(independent.currentPage.paper == PaperKind.GRID && independent.beginStroke(1.0, 2.0, .5f, 1) && independent.endStroke(3.0, 4.0, 1f, 2))
            check(store.activeDocumentId == original.id.value && note.snapshot() === original)
            // Exact same-version comparison must retain order even for separately decoded roots.
            check(independent.beginStroke(5.0,6.0,.5f,3) && independent.endStroke(7.0,8.0,.5f,4))
            val snapshot=independent.capture()
            val deep=PagedPreviewSnapshot.fromRoot(PagedPreviewCodec.decode(snapshot.encode()))
            check(snapshot.sameContent(deep))
            val originalRoot=snapshot.root
            val reverse=PagedPreviewSnapshot.fromRoot(DocumentRoot.compose(originalRoot.title,
                dev.notecore.canvas.prototype.SceneSnapshot.reordered(originalRoot.scene,originalRoot.scene.objects().map { it.id }.reversed()),originalRoot.pages()))
            check(!snapshot.sameContent(reverse))
            checks.add("validated_creation_refusal_and_independent_authority")
            val refused = PagedPreviewNotebook.open(original, "bad nonce", ownerCheck = { check(android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) })
            check(refused.notebook == null && refused.failure == PreviewOpenFailure.INVALID_SESSION_NONCE)
            val capacity = PagedPreviewNotebook.open(original, "checked", DocumentBudget(liveBytes = 0))
            check(capacity.notebook == null && capacity.documentReason == DocumentRejection.LIVE_BYTE_CAPACITY)
            check(note.snapshot() === original && store.activeDocumentId == original.id.value)
            checks.add("validated_open_refusal_preserves_original_store_owner")
            check(store.save(note.capture()) { _, error -> check(error == null); completed = true })
        }
        try {
            eventually("Initial write did not complete") { completed && store.saveStates().isEmpty() }
            check(directory.renameTo(moved)); directory.writeText("blocked directory", Charsets.UTF_8)
            main {
                var unchanged = 0
                check(store.save(note.capture()) { _, error -> check(error == null); unchanged++ })
                check(unchanged == 1 && store.saveStates().isEmpty())
                check(note.rename("Changed while storage unavailable"))
            }
            checks.add("saved_unchanged_skips_io")
            var failed = false
            lateinit var changed: PagedPreviewSnapshot
            main {
                changed = note.capture()
                check(store.save(changed) { _, error -> check(error != null); failed = true })
            }
            eventually("Changed root was incorrectly treated as saved") { failed && store.saveStates().single().retryRequired }
            main { check(store.saveStates().single().snapshot.sameContent(changed)) }
            checks.add("changed_revision_failure_retained")
            var listFailed = false
            main {
                store.list { notes, error ->
                    check(error != null && notes.single().id == changed.documentId)
                    check(notes.single().title == changed.title + "（未保存）")
                    listFailed = true
                }
            }
            eventually("Failed catalog did not expose the retained edit") { listFailed }
            check(directory.delete()); check(moved.renameTo(directory))
            completed = false
            main { check(store.save(changed) { _, error -> check(error == null); completed = true }) }
            eventually("Repair did not save the retained root") { completed && store.saveStates().isEmpty() }
            check(File(directory, "${changed.documentId}.ncp").readText(Charsets.UTF_8) == changed.encode())
            checks.add("repair_retries_exact_root")
            val canonical = File(directory, "${changed.documentId}.ncp")
            val backup = File(directory, canonical.name + ".bak")
            check(canonical.renameTo(backup))
            val unrelated = (0 until 2000).map { File(directory, "unrelated-$it.ncp") } +
                File(directory, "unrelated-backup.ncp.bak")
            unrelated.forEach { check(it.mkdir()) }
            var catalogChecked = false
            var catalogError: String? = null
            var summaries = emptyList<NoteSummary>()
            main { store.list { result, error -> summaries = result; catalogError = error; catalogChecked = true } }
            eventually("Catalog did not return") { catalogChecked }
            check(catalogError == null && summaries.singleOrNull()?.id == changed.documentId) {
                "Unrelated directories hid the saved note: $catalogError"
            }
            check(summaries.single().title == changed.title)
            check(canonical.readText(Charsets.UTF_8) == changed.encode() && !backup.exists())
            check(unrelated.all { it.isDirectory })
            checks.add("catalog_ignores_directories_and_recovers_legacy_backup")
            checks.add("catalog_retry_preserves_retained_edits_and_recovers_disk_list")
            val damaged = File(directory, "damaged-header.ncp")
            val oversizedHeader = "NCPREVIEW\t2\n" + "x".repeat(4096) + "\n"
            damaged.writeText(oversizedHeader)
            var damagedCatalog: List<NoteSummary>? = null
            main {
                store.list { result, error ->
                    check(error == null)
                    damagedCatalog = result
                }
            }
            eventually("Malformed header prevented catalog delivery") { damagedCatalog != null }
            val titles = checkNotNull(damagedCatalog).associate { it.id to it.title }
            check(titles == mapOf(changed.documentId to changed.title, "damaged-header" to "待恢复的笔记"))
            check(damaged.readText() == oversizedHeader && canonical.readText() == changed.encode())
            check(damaged.delete())
            checks.add("catalog_preserves_unreadable_note_and_lists_valid_neighbor")
            val oldBytes = canonical.readBytes()
            val stagingDirectory = File(directory, canonical.name + ".new")
            check(stagingDirectory.mkdir())
            val marker = File(stagingDirectory, "keep").apply { writeText("original marker") }
            lateinit var retained: PagedPreviewSnapshot
            var saveRejected = false
            main {
                check(note.rename("Retained after staging conflict"))
                retained = note.capture()
                check(store.save(retained) { _, error -> check(error != null); saveRejected = true })
            }
            eventually("Staging conflict did not retain the edit") {
                saveRejected && store.saveStates().singleOrNull()?.let {
                    it.retryRequired && it.snapshot === retained
                } == true
            }
            check(canonical.readBytes().contentEquals(oldBytes) && marker.readText() == "original marker")
            check(marker.delete() && stagingDirectory.delete())
            main { check(store.retryRetainedWrites()) }
            eventually("Retry did not finish saving the original retained edit") { store.saveStates().isEmpty() }
            check(canonical.readText(Charsets.UTF_8) == retained.encode())
            main {
                check(store.releaseActive(note))
                store.stopTestBackend()
                store = NoteStore.isolated(host, directory)
            }
            var recovered = false
            main {
                store.open(retained.documentId) { value, error ->
                    check(error == null && value?.persisted == true)
                    note = store.adopt(checkNotNull(value))
                    check(note.capture().sameContent(retained))
                    recovered = true
                }
            }
            eventually("The retained edit did not reopen from disk") { recovered }
            checks.add("staging_conflict_preserves_original_then_retries_and_reopens")
            val savedBytes = canonical.readBytes()
            val invalidDestination = File(directory, "blocked-export")
            check(invalidDestination.mkdir())
            val destinationMarker = File(invalidDestination, "keep").apply { writeText("keep destination") }
            var exportFailed = false
            main {
                store.export(Uri.fromFile(invalidDestination), retained) { error ->
                    check(!error.isNullOrEmpty()) { "Unwritable backup target reported success" }
                    exportFailed = true
                }
            }
            eventually("Export failure did not reach the original callback") { exportFailed }
            check(destinationMarker.readText() == "keep destination" && canonical.readBytes().contentEquals(savedBytes))
            val destination = File(directory, "backup-output")
            var exported = false
            main {
                store.export(Uri.fromFile(destination), retained) { error ->
                    check(error == null)
                    exported = true
                }
            }
            eventually("Retry did not export the original capture") { exported }
            check(destination.readBytes().contentEquals(savedBytes) && canonical.readBytes().contentEquals(savedBytes))
            main { check(note.capture().sameContent(retained) && store.saveStates().isEmpty()) }
            checks.add("export_target_failure_preserves_note_and_allows_retry")
            main {
                val first = note.capture()
                val firstRequest = store.beginExportPicker(first)
                check(firstRequest.disposition == ExportPickerDisposition.READY)
                check(store.exportReservedByteCharge == first.retainedByteCharge)
                check(note.rename("Later export"))
                check(store.beginExportPicker(note.capture()).disposition == ExportPickerDisposition.BUSY)
                check(store.takeExportPicker(checkNotNull(firstRequest.requestCode)) === first); check(store.exportReservedByteCharge == 0L)
                val later = note.capture()
                val nextRequest = store.beginExportPicker(later)
                check(nextRequest.disposition == ExportPickerDisposition.READY)
                check(store.takeExportPicker(checkNotNull(firstRequest.requestCode)) == null)
                check(store.takeExportPicker(checkNotNull(nextRequest.requestCode)) === later)
                check(store.takeExportPicker(nextRequest.requestCode) == null && store.exportReservedByteCharge == 0L)
                // This edit was for export only; retain it through the original save handoff before close.
                completed = false
                check(store.save(later) { _, error -> check(error == null); completed = true })
            }
            checks.add("single_export_snapshot_preserves_original")
            eventually("Post-export edit did not save") { completed && store.saveStates().isEmpty() }
            main { store.releaseActive(note); store.stopTestBackend(); store = NoteStore.isolated(host, directory) }
            var opened = false
            main { store.open(changed.documentId) { value, error ->
                check(error == null && value?.persisted == true)
                note = store.adopt(checkNotNull(value)); opened = true
            } }
            eventually("Saved document did not reopen") { opened }
            main {
                store.stopTestBackend()
                var success = 0
                check(store.save(note.capture()) { _, error -> check(error == null); success++ })
                check(success == 1 && store.saveStates().isEmpty())
                checks.add("reopened_saved_marker_skips_stopped_backend")
                val root = note.snapshot()
                val foreign = PagedPreviewSnapshot.fromRoot(DocumentRoot.compose("Foreign same revision", root.scene, root.pages()))
                var refused = 0
                check(store.save(foreign) { _, error -> check(error != null); refused++ })
                check(refused > 0 && store.saveStates().single().snapshot === foreign)
                check(note.snapshot() === root)
                checks.add("foreign_same_revision_is_not_acknowledged_as_saved")
            }
            return JSONObject().put("checks", JSONArray(checks)).put("original_note_files_used", false)
                .put("system_file_picker_verified", false).toString()
        } finally {
            main { store.stopTestBackend() }
            directory.deleteRecursively(); moved.deleteRecursively()
        }
    }
}
