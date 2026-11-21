package dev.notecore.probes;

import android.os.Process;
import dev.notecore.canvas.prototype.BrushKind;
import dev.notecore.document.DocumentBudget;
import dev.notecore.document.DocumentBudgetCreation;
import dev.notecore.document.DocumentBudgetFailure;
import dev.notecore.document.PagedPreviewNotebook;
import dev.notecore.document.PagedPreviewSnapshot;
import dev.notecore.document.PreviewDocumentRead;
import dev.notecore.document.PreviewDocumentReader;
import dev.notecore.document.PreviewEdit;
import dev.notecore.document.PreviewEditResult;
import dev.notecore.document.PreviewFailureKind;
import dev.notecore.document.PreviewNotebookOpen;
import dev.notecore.document.PreviewOpenFailure;
import dev.notecore.document.PreviewReadFailure;
import dev.notecore.document.PreviewRecoveryCodec;
import dev.notecore.document.PreviewRecoveryRead;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Objects;
import kotlin.Unit;
import org.json.JSONObject;

/** Independent Java consumer of the fixed Android AARs, run on API 26 ART. */
public final class C1AndroidFailureConsumer {
    private static final long MIB = 1024L * 1024L;

    private static DocumentBudgetCreation budget(long inputBytes) {
        return DocumentBudget.Companion.validated(128, 5000, 8 * MIB, 4096, 32 * MIB, 4 * MIB,
                128, 8 * MIB, inputBytes);
    }

    private static void check(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static String illegalArgument(Runnable action) {
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            return expected.getClass().getSimpleName();
        }
        throw new AssertionError("Expected IllegalArgumentException");
    }

    public static void main(String[] args) throws Exception {
        check(args.length == 2 || (args.length == 3 && args[2].equals("--current-candidate")), "fixture paths and mode");
        boolean currentCandidate = args.length == 3;
        String pending = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        String committed = new String(Files.readAllBytes(Paths.get(args[1])), StandardCharsets.UTF_8);

        DocumentBudgetCreation invalid = budget(-1);
        check(invalid.getFailure() == DocumentBudgetFailure.INPUT_BYTES && invalid.getBudget() == null, "typed budget");
        DocumentBudget exact = Objects.requireNonNull(budget(2248).getBudget());
        String constructor = illegalArgument(() -> new DocumentBudget(128, 5000, 8 * MIB, 4096, 32 * MIB, 4 * MIB,
                128, 8 * MIB, -1));
        String copy = illegalArgument(() -> exact.copy(exact.getMaxPages(), exact.getMaxObjects(), exact.getLiveBytes(),
                exact.getJournalEntries(), exact.getJournalBytes(), exact.getIdentityBytes(), exact.getHistoryEntries(),
                exact.getHistoryBytes(), -1));
        check(exact.getInputBytes() == 2248, "invalid copy did not change budget");

        if (currentCandidate) {
            PreviewDocumentRead highlight = PreviewDocumentReader.INSTANCE.read(
                    committed.replace("\tMONOLINE\t", "\tHIGHLIGHTER\t"));
            check(highlight.getFailure() == null && highlight.getRoot() != null, "current highlighter accepted");
        }
        String unsupportedKind = currentCandidate ? "PRESSURE" : "HIGHLIGHTER";
        PreviewDocumentRead unsupported = PreviewDocumentReader.INSTANCE.read(
                committed.replace("\tMONOLINE\t", "\t" + unsupportedKind + "\t"));
        check(unsupported.getFailure() == PreviewReadFailure.UNSUPPORTED_PREVIEW_CONTENT && unsupported.getRoot() == null,
                "typed document refusal");
        String malformed = "unrecognized checkpoint";
        PreviewRecoveryRead rejected = PreviewRecoveryCodec.INSTANCE.read(malformed);
        check(rejected.getFailure() == PreviewReadFailure.INVALID_OR_UNSUPPORTED_FORMAT && rejected.getSnapshot() == null,
                "typed recovery refusal");
        String decode = illegalArgument(() -> PreviewRecoveryCodec.INSTANCE.decode(malformed));

        PagedPreviewSnapshot checkpoint = Objects.requireNonNull(PreviewRecoveryCodec.INSTANCE.read(pending).getSnapshot());
        PreviewNotebookOpen refused = PagedPreviewNotebook.Companion.openRecovery(checkpoint,
                "failure-capacity", Objects.requireNonNull(budget(2247).getBudget()), () -> Unit.INSTANCE);
        check(refused.getNotebook() == null && refused.getFailure() == PreviewOpenFailure.INPUT_CAPACITY, "capacity");
        PreviewNotebookOpen opened = PagedPreviewNotebook.Companion.openRecovery(checkpoint,
                "failure-exact", exact, () -> Unit.INSTANCE);
        check(opened.getFailure() == null, "exact open");
        PagedPreviewNotebook notebook = Objects.requireNonNull(opened.getNotebook());
        long revision = notebook.getChangeVersion();
        check(notebook.pageAt(-1) == null && notebook.pageAt(notebook.getPageCount()) == null
                && notebook.strokeAt(-1) == null && notebook.strokeAt(0) == null, "safe index");
        PreviewEditResult edit = notebook.edit(new PreviewEdit.SelectPen(-1, Double.NaN, BrushKind.MONOLINE));
        check(!edit.getAccepted() && Objects.requireNonNull(edit.getFailure()).getKind() == PreviewFailureKind.INVALID_ARGUMENT
                && edit.getRootRevision() == revision, "invalid pen");
        check(notebook.getInputReservedByteCharge() == 2248
                && Objects.requireNonNull(notebook.pendingInk()).getSamples().getSize() == 4, "pending input");
        check(notebook.captureRecovery().encode().equals(pending), "preserved pending bytes");
        check(notebook.retryPendingStroke() && notebook.retryPendingStroke() && notebook.getChangeVersion() == 2
                && notebook.getStrokeCount() == 1 && notebook.getInputReservedByteCharge() == 0, "idempotent retry");
        check(notebook.capture().encode().equals(committed), "canonical bytes");

        System.out.println(new JSONObject()
                .put("pid", Process.myPid()).put("budget_typed", invalid.getFailure().name())
                .put("budget_constructor_exception", constructor).put("budget_copy_exception", copy)
                .put("unsupported_document_typed", unsupported.getFailure().name())
                .put("malformed_recovery_typed", rejected.getFailure().name())
                .put("malformed_recovery_exception", decode)
                .put("recovery_refused_bytes", 2247).put("recovery_admitted_bytes", 2248)
                .put("missing_index_is_nil", true).put("invalid_edit_preserved_pending", true)
                .put("retry_preserved_checkpoint_bytes", true));
    }
}
