package dev.notecore.document

import dev.notecore.canvas.prototype.InputRejection

enum class PreviewInputDisposition { REJECTED, ACCEPTED, COMMITTED, PENDING_COMMIT, CANCELLED }

/** Synchronous admission on the original notebook owner, not a durable or display receipt.
 * REJECTED consumes zero new samples; caller owns the entire batch for retry or explicit cancellation.
 * PENDING_COMMIT owns all admitted points as the notebook's exact completed command: never resend the batch.
 */
class PreviewInputResult internal constructor(
    val disposition: PreviewInputDisposition,
    val acceptedSamples: Int,
    val activeSamples: Int,
    val failure: PreviewSessionFailure?,
    val inputReason: InputRejection? = null,
) {
    val admitted: Boolean get() = disposition != PreviewInputDisposition.REJECTED
}

internal const val PREVIEW_INPUT_BATCH_LIMIT = 1024
/** Declared worst producer overlap: scalar arrays, Swift values/maps, Data/NSData bridge and native
 * copies, plus container allowance. OS event objects and runtime heap behaviour are not measured here.
 * One owner callback at a time; no producer buffers survive that synchronous callback.
 */
internal const val PREVIEW_INPUT_PRODUCER_BYTES = 2048L + PREVIEW_INPUT_BATCH_LIMIT * 192L
