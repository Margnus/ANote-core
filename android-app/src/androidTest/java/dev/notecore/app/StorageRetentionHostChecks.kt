package dev.notecore.app

import android.app.Activity
import android.app.Instrumentation
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import dev.notecore.canvas.prototype.BoundedOwnerExecutor
import dev.notecore.canvas.prototype.ResultDisposition
import dev.notecore.canvas.prototype.WorkAdmission
import dev.notecore.canvas.prototype.WorkAdmissionFailure
import dev.notecore.document.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
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
        CatalogRetentionHostChecks(test, host).verify()
        PendingRecoveryHostChecks(test, host).verify()
        val appleRoundTrip = ReadFailureHostChecks(test, host).verify()
        val directory = File(host.cacheDir, "storage-retention-${java.util.UUID.randomUUID()}")
        val moved = File(directory.path + "-saved")
        lateinit var store: NoteStore
        lateinit var note: PagedPreviewNotebook
        var completed = false
        val checks = mutableListOf("local_file_identity_refusal_preserves_owner_input_and_io", "catalog_capacity_failure_preserves_owner_snapshot", "derived_text_capacity_checkpoint_save_and_reopen", "read_failure_classification_to_owner", "pending_recovery_atomic_failure_retry_new_owner_and_canonical_reopen")
        checks.add("import_refusal_preserves_source_and_retry_remaps_identity")
        checks.add("backup_round_trip_preserves_source_and_remaps_ink_references")
        checks.add("apple_ui_backup_import_preserves_two_pages_and_three_strokes")
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
            val validBytes = changed.encode()
            val damagedBackup = validBytes.removeSuffix("END\n") + "INCOMPLETE\n"
            backup.writeText(damagedBackup)
            var listedWithDamagedBackup = false
            var refusedBackup = false
            main {
                check(store.releaseActive(note))
                store.list { rows, error ->
                    check(error == null && rows.single().id == changed.documentId)
                    listedWithDamagedBackup = true
                }
                store.open(changed.documentId) { opened, error ->
                    check(opened == null && error == LocalReadFailure.INVALID_FORMAT.message)
                    refusedBackup = true
                }
            }
            eventually("Damaged legacy backup was not refused") { listedWithDamagedBackup && refusedBackup }
            check(canonical.readText() == validBytes && backup.readText() == damagedBackup)
            checks.add("damaged_legacy_backup_preserves_original_during_list_and_open")
            check(backup.delete())
            check(canonical.renameTo(backup))
            val partialBase = "partial base left by a legacy writer"
            canonical.writeText(partialBase)
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
            check(canonical.readText() == partialBase && backup.readText() == validBytes)
            var backupOpened = false
            main { store.open(changed.documentId) { opened, error ->
                check(error == null && opened?.persisted == true)
                note = store.adopt(checkNotNull(opened))
                check(note.captureRecovery().sameContent(changed))
                backupOpened = true
            } }
            eventually("Open did not recover valid legacy backup") { backupOpened }
            check(canonical.readText(Charsets.UTF_8) == validBytes && !backup.exists())
            check(unrelated.all { it.isDirectory })
            checks.add("catalog_ignores_directories_and_recovers_legacy_backup")
            verifyLegacyBackupDuringFailedWrite(changed)
            checks.add("legacy_backup_survives_failed_write_until_verified_replacement")
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
                val worker = NoteStore::class.java.getDeclaredField("executor").apply { isAccessible = true }
                    .get(store) as BoundedOwnerExecutor
                val maximum = 256L * 1024 * 1024
                check(worker.status().entries.isEmpty())
                check(worker.submit(maximum, { error("Over-budget work executed") }) { ResultDisposition.ACKNOWLEDGED } ==
                    WorkAdmission.Rejected(WorkAdmissionFailure.BYTE_CAPACITY))
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
            val releaseWorker = CountDownLatch(1)
            val worker = NoteStore::class.java.getDeclaredField("executor").apply { isAccessible = true }
                .get(store) as BoundedOwnerExecutor
            try {
                main {
                    val picker = store.beginExportPicker(note.capture())
                    val pickerCode = checkNotNull(picker.requestCode)
                    check(note.rename("Save waiting for picker"))
                    val waiting = note.capture()
                    val needed = waiting.encodingWorkByteCharge
                    // Keep the queue exactly one byte short until the matching picker releases it.
                    check(worker.submit(worker.availableBytes() - needed + 1, {
                        check(releaseWorker.await(12, TimeUnit.SECONDS))
                    }) { ResultDisposition.ACKNOWLEDGED } is WorkAdmission.Accepted)
                    check(worker.availableBytes() == needed - 1)
                    completed = false
                    check(store.save(waiting) { _, error -> check(error == null); completed = true })
                    check(!store.saveStates().single().inFlight)
                    check(store.takeExportPicker(pickerCode + 1) == null)
                    check(worker.availableBytes() == needed - 1)
                    check(store.takeExportPicker(pickerCode) != null)
                    check(!store.saveStates().single().inFlight)
                }
                eventually("Matching picker release did not dispatch the waiting save") {
                    store.saveStates().singleOrNull()?.inFlight == true
                }
                check(canonical.readText().contains("Later export"))
            } finally {
                releaseWorker.countDown()
            }
            eventually("Waiting save did not reach disk") { completed && store.saveStates().isEmpty() }
            check(canonical.readText().contains("Save waiting for picker"))
            checks.add("export_picker_release_dispatches_waiting_save")
            val confirmed = File(directory, "${changed.documentId}.ncp").readBytes()
            main {
                val root = note.snapshot(); val history = note.authorityStatus()
                var refused = 0
                check(!store.save(changed) { _, error -> check(error != null); refused++ })
                check(refused == 1 && store.saveStates().isEmpty())
                check(note.snapshot() === root && note.authorityStatus() == history)
            }
            check(File(directory, "${changed.documentId}.ncp").readBytes().contentEquals(confirmed))
            checks.add("late_capture_refused_after_latest_ack_without_disk_rollback")
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
                check(!store.save(foreign) { _, error -> check(error != null); refused++ })
                check(refused == 1 && store.saveStates().isEmpty())
                check(note.snapshot() === root)
                checks.add("foreign_same_revision_is_not_acknowledged_as_saved")
            }
            verifyReopenAfterWriteAcknowledgement()
            checks.add("retained_reopen_observes_preceding_write_ack")
            checks.add("retained_reopen_reserves_capture_at_exact_byte_boundary")
            verifyOpenDuringSave(cancel = true)
            checks.add("superseded_open_acks_without_disk_retry_or_delivery")
            verifyOpenDuringSave(cancel = false)
            checks.add("unrelated_save_does_not_retry_requested_open")
            verifyOpenDuringSave(cancel = false, sameDocument = true)
            checks.add("same_note_save_supersedes_in_flight_read")
            return JSONObject().put("checks", JSONArray(checks)).put("original_note_files_used", false)
                .put("apple_round_trip_base64", Base64.encodeToString(appleRoundTrip, Base64.NO_WRAP))
                .put("system_file_picker_verified", false).toString()
        } finally {
            main { store.stopTestBackend() }
            directory.deleteRecursively(); moved.deleteRecursively()
        }
    }
    private fun verifyReopenAfterWriteAcknowledgement() {
        val directory = File(host.cacheDir, "open-after-write-${java.util.UUID.randomUUID()}")
        val writerEntered = CountDownLatch(1)
        val releaseWriter = CountDownLatch(1)
        lateinit var store: NoteStore
        lateinit var snapshot: PagedPreviewSnapshot
        var reopened = false
        try {
            main {
                store = NoteStore.isolated(host, directory, beforeOpenRead = { error("retained reopen reached disk") },
                    observeWriteBoundary = { boundary ->
                        if (boundary == PreviewWriteBoundary.SYNCED_BEFORE_REPLACE) {
                            writerEntered.countDown()
                            check(releaseWriter.await(12, TimeUnit.SECONDS))
                        }
                    })
                val note = store.create()
                snapshot = note.captureRecovery()
                check(store.save(snapshot) { _, error -> check(error == null) })
                check(writerEntered.await(5, TimeUnit.SECONDS))
                check(store.releaseActive(note))
                check(store.saveStates().single().inFlight)
                val worker = NoteStore::class.java.getDeclaredField("executor").apply { isAccessible = true }
                    .get(store) as BoundedOwnerExecutor
                val needed = snapshot.retainedByteCharge + 4096
                val padding = worker.availableBytes() - needed
                check(worker.trySetRetainedOwnerBytes(padding + 1))
                var refused = false
                store.open(snapshot.documentId) { opened, message ->
                    check(opened == null && message == "后台队列繁忙，请重新打开笔记")
                    refused = true
                }
                check(refused && worker.availableBytes() == needed - 1)
                check(store.saveStates().single().inFlight && store.saveStates().single().snapshot.sameContent(snapshot))
                check(worker.trySetRetainedOwnerBytes(padding))
                store.open(snapshot.documentId) { opened, message ->
                    check(message == null && opened?.persisted == true)
                    val restored = store.adopt(checkNotNull(opened))
                    check(restored.captureRecovery().sameContent(snapshot))
                    check(store.editorPresentation(restored).phase == EditorSavePhase.SAVED)
                    var acknowledged = false
                    check(store.save(restored.captureRecovery()) { _, error ->
                        check(error == null)
                        acknowledged = true
                    })
                    check(acknowledged && store.saveStates().isEmpty())
                    reopened = true
                }
                check(worker.availableBytes() == 0L)
                check(worker.trySetRetainedOwnerBytes(0))
            }
            releaseWriter.countDown()
            eventually("Reopen did not use the preceding write acknowledgement") { reopened }
            check(File(directory, "${snapshot.documentId}.ncp").readText() == snapshot.encode())
            android.util.Log.i("NoteCoreStorageTest", "retained open charge=${snapshot.retainedByteCharge + 4096} disk=${PagedPreviewCodec.maximumDecodingWorkByteCharge()}")
        } finally {
            releaseWriter.countDown()
            main { store.stopTestBackend() }
            directory.deleteRecursively()
        }
    }

    private fun verifyLegacyBackupDuringFailedWrite(original: PagedPreviewSnapshot) {
        val directory = File(host.cacheDir, "legacy-backup-write-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val root = original.root
        val base = File(directory, "${original.documentId}.ncp").apply { writeText(original.encode()) }
        val backupText = PagedPreviewSnapshot.fromRoot(DocumentRoot.compose("Legacy backup", root.scene, root.pages())).encode()
        val backup = File(directory, base.name + ".bak").apply { writeText(backupText) }
        val staging = File(directory, base.name + ".new").apply { writeText("Unpublished staging") }
        val fail = java.util.concurrent.atomic.AtomicBoolean(true)
        lateinit var store: NoteStore
        lateinit var latest: PagedPreviewSnapshot
        try {
            main {
                store = NoteStore.isolated(host, directory, observeWriteBoundary = { boundary ->
                    if (boundary == PreviewWriteBoundary.SYNCED_BEFORE_REPLACE && fail.getAndSet(false)) {
                        throw java.io.IOException("Injected replacement failure")
                    }
                })
                val note = store.adopt(OpenedDocument(root, true, original))
                check(note.rename("Verified replacement"))
                latest = note.captureRecovery()
                check(store.save(latest) { _, _ -> })
            }
            eventually("Replacement failure did not retain the latest capture") {
                store.saveStates().singleOrNull()?.let { it.retryRequired && it.snapshot === latest } == true
            }
            check(base.readText() == original.encode() && backup.readText() == backupText && !staging.exists())
            main { check(store.retryRetainedWrites()) }
            eventually("Legacy replacement did not receive write ACK") { store.saveStates().isEmpty() }
            check(base.readText() == latest.encode() && !backup.exists())
        } finally {
            main { store.stopTestBackend() }
            directory.deleteRecursively()
        }
    }

    private fun verifyOpenDuringSave(cancel: Boolean, sameDocument: Boolean = false) {
        val directory = File(host.cacheDir, "superseded-open-${java.util.UUID.randomUUID()}")
        check(directory.mkdir())
        val oldBytes = "NCPREVIEW\t2\nD\told-note\tEarlier selection\t1\t0\t0\nG\tpage\tlayer\t595.0\t842.0\tBLANK\t0\nEND\n"
        val original = File(directory, "old-note.ncp").apply { writeText(oldBytes) }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val reads = AtomicInteger()
        lateinit var store: NoteStore
        lateinit var worker: BoundedOwnerExecutor
        lateinit var current: PagedPreviewSnapshot
        var requested = true
        var oldDeliveries = 0
        var saved = false
        try {
            main {
                store = NoteStore.isolated(host, directory, beforeOpenRead = {
                    reads.incrementAndGet()
                    entered.countDown()
                    check(release.await(10, TimeUnit.SECONDS))
                })
                worker = NoteStore::class.java.getDeclaredField("executor").apply { isAccessible = true }
                    .get(store) as BoundedOwnerExecutor
                store.open("old-note", isRequested = { requested }) { result, failure ->
                    check(failure == null && result != null)
                    if (sameDocument) check(result.root === current.root)
                    else check(result.recovery.encode() == oldBytes)
                    oldDeliveries++
                }
            }
            check(entered.await(5, TimeUnit.SECONDS))
            main {
                val note = if (sameDocument) {
                    val decoded = PreviewRecoveryCodec.decode(oldBytes)
                    store.adopt(OpenedDocument(decoded.root, true, decoded)).also { check(it.rename("Newer save")) }
                } else store.create()
                current = note.captureRecovery()
                check(store.save(current) { _, failure -> saved = failure == null })
                requested = !cancel
                store.open(current.documentId) { result, failure ->
                    check(failure == null && result?.root === current.root)
                }
            }
            release.countDown()
            eventually("Newer save did not settle") { saved && store.saveStates().isEmpty() }
            test.waitForIdleSync()
            eventually("Work was not acknowledged") { worker.status().entries.isEmpty() }
            main { check(oldDeliveries == if (cancel) 0 else 1); check(store.activeDocumentId == current.documentId) }
            check(reads.get() == 1)
            if (!sameDocument) check(original.readText() == oldBytes)
            check(File(directory, "${current.documentId}.ncp").readText() == current.encode())
        } finally {
            release.countDown()
            main { store.stopTestBackend() }
            directory.deleteRecursively()
        }
    }

}
