package dev.notecore.library

import kotlin.test.*

class CoverLoadRulesTest {
    private val notes = listOf(
        LibraryViewNote("n0", "A", 0, null, false),
        LibraryViewNote("n1", "B", 1, null, false),
        LibraryViewNote("n2", "C", 2, null, false),
        LibraryViewNote("n3", "D", 3, null, false),
    )
    private val catalog = LibraryViewSnapshot("local", 15, true, notes,
        LibraryViewPreference(LibrarySort.NAME, LibraryLayout.GRID))

    private fun query(version: Long = 7, operationId: String = "covers", input: String = "") =
        LibraryPageQuery(LibrarySearchQuery(operationId, version, "local", 15, input),
            LibrarySort.NAME, false)

    private fun firstPage(limit: Int = 2, source: LibraryViewSnapshot = catalog) =
        assertNotNull(LibraryPageRules.first(source, query(), limit).page)

    private fun metadata(page: LibraryPage, firstRevision: Long = 5) =
        page.rows.mapIndexed { index, row -> CoverPageMetadata(row.noteId, firstRevision + index) }

    private fun request(
        maxCoverBytes: Long = 327680,
        maxBatchBytes: Long = 655360,
        maxTasks: Int = 2,
        targetWidth: Int = 200,
        targetHeight: Int = 260,
        scale: Double = 1.0,
    ) = CoverLoadRequest(targetWidth, targetHeight, scale, maxCoverBytes, maxBatchBytes, maxTasks)

    private fun plan(page: LibraryPage = firstPage(), input: List<CoverPageMetadata> = metadata(page),
                     loadRequest: CoverLoadRequest = request()) =
        assertNotNull(CoverLoadRules.prepare(page, input, loadRequest).plan)

    @Test fun visibleRowsBecomeBoundedTasksAndEmptyPageNeedsNoMetadata() {
        val page = firstPage()
        val candidate = plan(page)
        assertEquals(listOf("n0", "n1"), candidate.tasks.map { it.key.noteId })
        assertEquals(listOf(5L, 6L), candidate.tasks.map { it.key.firstPageRevision })
        assertEquals(listOf(256, 256), candidate.tasks.map { it.key.bucketWidth })
        assertEquals(listOf(320, 320), candidate.tasks.map { it.key.bucketHeight })
        assertTrue(candidate.tasks.all { it.argbByteUpperBound == 327680L })
        assertEquals(655360L, candidate.totalArgbByteUpperBound)
        assertTrue(candidate.tasks.none { it.key.noteId == "n2" })
        val emptyCatalog = catalog.copy(notes = emptyList())
        val emptyPage = firstPage(source = emptyCatalog)
        val empty = assertNotNull(CoverLoadRules.prepare(emptyPage, emptyList(), request()).plan)
        assertTrue(empty.tasks.isEmpty())
        assertTrue(empty.metadata.isEmpty())
        assertEquals(0L, empty.totalArgbByteUpperBound)
    }

    @Test fun invalidRequestsAndCapacityFailuresNeverReturnPartialTasks() {
        val page = firstPage()
        val input = metadata(page)
        for (bad in listOf(request(maxTasks = 0), request(maxTasks = 201),
                           request(maxCoverBytes = 0), request(maxBatchBytes = 0), request(targetWidth = 0),
                           request(scale = Double.NaN))) {
            val result = CoverLoadRules.prepare(page, input, bad)
            assertNull(result.plan)
            assertEquals(CoverLoadFailure.INVALID_REQUEST, result.failure)
        }
        val tasks = CoverLoadRules.prepare(page, input, request(maxTasks = 1))
        assertNull(tasks.plan)
        assertEquals(CoverLoadFailure.TASK_CAPACITY, tasks.failure)
        val pixels = CoverLoadRules.prepare(page, input, request(maxCoverBytes = 327679))
        assertNull(pixels.plan)
        assertEquals(CoverLoadFailure.PIXEL_CAPACITY, pixels.failure)
        assertNotNull(CoverLoadRules.prepare(page, input, request(maxCoverBytes = 327680)).plan)
    }

    @Test fun coversThatIndividuallyFitStillRequireTheWholeBatchBudget() {
        val page = firstPage()
        val input = metadata(page)
        val tooSmall = request(maxBatchBytes = 655359)
        assertEquals(CoverLoadPlanResult(failure = CoverLoadFailure.PIXEL_CAPACITY),
            CoverLoadRules.prepare(page, input, tooSmall))

        val accepted = request(maxBatchBytes = 655360)
        val plan = plan(page, input, accepted)
        val completion = CoverLoadCompletion(plan, plan.tasks.first())
        assertEquals(655360L, plan.totalArgbByteUpperBound)
        assertEquals(CoverLoadFailure.STALE_REQUEST,
            CoverLoadRules.deliver(completion, page, page.query, tooSmall).failure)
        val tampered = completion.copy(plan = plan.copy(totalArgbByteUpperBound = 327680L))
        assertEquals(CoverLoadFailure.STALE_PLAN,
            CoverLoadRules.deliver(tampered, page, page.query, accepted).failure)
    }

    @Test fun metadataMustExactlyMatchVisibleRowsAndRevisions() {
        val page = firstPage()
        val input = metadata(page)
        val malformed = listOf(
            input.drop(1),
            input + CoverPageMetadata("n2", 7),
            listOf(input[0], input[0]),
            listOf(input[0], CoverPageMetadata("missing", 6)),
        )
        for (bad in malformed) {
            val result = CoverLoadRules.prepare(page, bad, request())
            assertNull(result.plan)
            assertEquals(CoverLoadFailure.INVALID_METADATA, result.failure)
        }
        val stale = CoverLoadRules.prepare(page,
            listOf(input[0], input[1].copy(firstPageRevision = -1)), request())
        assertNull(stale.plan)
        assertEquals(CoverLoadFailure.CACHE_REJECTED, stale.failure)
        assertEquals(CoverCacheFailure.INVALID_DIMENSIONS, stale.cacheFailure)
        assertEquals(listOf("n0", "n1"), input.map { it.noteId })
    }

    @Test fun oversizedMetadataIsRejectedBeforeReadingAnyRows() {
        val unreadable = object : AbstractList<CoverPageMetadata>() {
            override val size = 201
            override fun get(index: Int): CoverPageMetadata = error("oversized metadata should not be read")
        }
        assertEquals(CoverLoadPlanResult(failure = CoverLoadFailure.INVALID_METADATA),
            CoverLoadRules.prepare(firstPage(), unreadable, request()))
    }

    @Test fun planOwnsMetadataAndCurrentDeliveryIsIdempotent() {
        val page = firstPage()
        val mutable = metadata(page).toMutableList()
        val candidate = plan(page, mutable)
        mutable.clear()
        assertEquals(2, candidate.metadata.size)
        assertEquals(2, candidate.tasks.size)
        val completion = CoverLoadCompletion(candidate, candidate.tasks.first())
        val first = CoverLoadRules.deliver(completion, page, page.query, request())
        val second = CoverLoadRules.deliver(completion, page, page.query, request())
        assertNull(first.failure)
        assertSame(completion.task, first.task)
        assertEquals(first, second)
        assertSame(first.task, second.task)
        assertEquals(listOf("n0", "n1"), page.rows.map { it.noteId })
    }

    @Test fun oldQueryChangedRequestOrCancellationCannotPublish() {
        val page = firstPage()
        val candidate = plan(page)
        val completion = CoverLoadCompletion(candidate, candidate.tasks.first())
        assertEquals(CoverLoadFailure.CANCELLED,
            CoverLoadRules.deliver(completion, null, page.query, request()).failure)
        assertEquals(CoverLoadFailure.CANCELLED,
            CoverLoadRules.deliver(completion, page, null, request()).failure)
        assertEquals(CoverLoadFailure.CANCELLED,
            CoverLoadRules.deliver(completion, page, query(operationId = "other"), request()).failure)
        for (active in listOf(query(version = 8), query(input = "changed"),
                              page.query.copy(sort = LibrarySort.UPDATED), page.query.copy(favoritesOnly = true))) {
            assertEquals(CoverLoadFailure.STALE_QUERY,
                CoverLoadRules.deliver(completion, page, active, request()).failure)
        }
        assertSame(page.query.operation,
            LibrarySearchRules.cancelQuery(page.query.operation, "other", 7))
        assertNull(LibrarySearchRules.cancelQuery(page.query.operation, "covers", 7))
        assertNotNull(CoverLoadRules.deliver(completion, page, page.query, request()).task)
    }

    @Test fun pageSwitchOrDisplayRequestChangeInvalidatesOldCompletion() {
        val page = firstPage()
        val candidate = plan(page)
        val completion = CoverLoadCompletion(candidate, candidate.tasks.first())
        val next = assertNotNull(LibraryPageRules.next(catalog, query(), page.nextCursor!!, 2).page)
        assertEquals(CoverLoadFailure.STALE_PAGE,
            CoverLoadRules.deliver(completion, next, page.query, request()).failure)
        assertEquals(CoverLoadFailure.STALE_REQUEST,
            CoverLoadRules.deliver(completion, page, page.query, request(scale = 2.0)).failure)
        assertNotNull(CoverLoadRules.deliver(completion, page, page.query, request()).task)
    }

    @Test fun forgedPagesMetadataAndTasksCannotBeDelivered() {
        val page = firstPage()
        val candidate = plan(page)
        val original = CoverLoadCompletion(candidate, candidate.tasks.first())
        val changedMetadata = candidate.copy(metadata = candidate.metadata.mapIndexed { index, item ->
            if (index == 0) item.copy(firstPageRevision = 99) else item
        })
        val reversedTasks = candidate.copy(tasks = candidate.tasks.reversed())
        val forgedTask = original.task.copy(key = original.task.key.copy(firstPageRevision = 99))
        for (completion in listOf(original.copy(plan = changedMetadata),
                                  original.copy(plan = reversedTasks), original.copy(task = forgedTask))) {
            assertNull(CoverLoadRules.deliver(completion, page, page.query, request()).task)
            assertEquals(CoverLoadFailure.STALE_PLAN,
                CoverLoadRules.deliver(completion, page, page.query, request()).failure)
        }
        val reversedRows = page.copy(rows = page.rows.reversed())
        assertEquals(CoverLoadFailure.STALE_PAGE,
            CoverLoadRules.deliver(original, reversedRows, page.query, request()).failure)
        val changedSnapshot = page.copy(snapshot = page.snapshot.copy(notes = page.snapshot.notes.map {
            if (it.noteId == "n0") it.copy(title = "replacement") else it
        }))
        assertEquals(CoverLoadFailure.STALE_PAGE,
            CoverLoadRules.deliver(original, changedSnapshot, page.query, request()).failure)
        assertNotNull(CoverLoadRules.deliver(original, page, page.query, request()).task)
    }
}
