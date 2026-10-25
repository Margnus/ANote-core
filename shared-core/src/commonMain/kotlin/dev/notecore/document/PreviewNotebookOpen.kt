package dev.notecore.document

/** Unfrozen preview adoption boundary. Not a persistence, thread-transfer or stable SDK receipt. */
enum class PreviewOpenFailure { INVALID_SESSION_NONCE, UNSUPPORTED_BUDGET, INVALID_CONTENT, UNSUPPORTED_CONTENT, DOCUMENT_CAPACITY, INVALID_DOCUMENT_ID, INVALID_TITLE, INVALID_PAGE, INPUT_CAPACITY }

/** Failure carries no mutable notebook; input root remains caller-owned and unchanged.
 * DocumentBudget itself is an already-validated value. Wrong owner and programmer errors propagate.
 */
class PreviewNotebookOpen internal constructor(val notebook: PagedPreviewNotebook?, val failure: PreviewOpenFailure?,
                                              val documentReason: DocumentRejection? = null)
