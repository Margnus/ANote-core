import dev.notecore.document.DocumentBudget;
import dev.notecore.document.PagedPreviewNotebook;
import dev.notecore.document.PagedPreviewSnapshot;
import dev.notecore.document.PreviewRecoveryCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import kotlin.Unit;

// Isolated migration oracle for a complete pending-ink recovery envelope.
class SqlitePendingRecoveryFixture {
    public static void main(String[] args) throws Exception {
        int limit = 4 * 1024 * 1024;
        byte[] input = System.in.readNBytes(limit + 1);
        if (input.length > limit) {
            System.out.println("SOURCE_CAPACITY");
            return;
        }
        var utf8 = StandardCharsets.UTF_8;
        String text;
        try {
            text = utf8.newDecoder().decode(ByteBuffer.wrap(input)).toString();
        } catch (java.nio.charset.CharacterCodingException error) {
            System.out.println("INVALID_SOURCE");
            return;
        }
        var read = PreviewRecoveryCodec.INSTANCE.read(text);
        PagedPreviewSnapshot snapshot = read.getSnapshot();
        if (snapshot == null || snapshot.getPending() == null) {
            System.out.println("INVALID_SOURCE");
            return;
        }
        var defaults = new DocumentBudget();
        var budget = defaults.copy(defaults.getMaxPages(), defaults.getMaxObjects(), defaults.getLiveBytes(),
                defaults.getJournalEntries(), defaults.getJournalBytes(), defaults.getIdentityBytes(),
                defaults.getHistoryEntries(), defaults.getHistoryBytes(), Long.parseLong(args[0]));
        var opened = PagedPreviewNotebook.Companion.openRecovery(snapshot, "sqlite-import", budget, () -> Unit.INSTANCE);
        var notebook = opened.getNotebook();
        if (notebook == null) {
            System.out.println(opened.getFailure().name());
            return;
        }
        var retained = notebook.captureRecovery();
        if (!retained.sameContent(snapshot)) {
            throw new IllegalStateException("reopen changed source content");
        }
        long revision = snapshot.getVersion();
        int strokes = notebook.getStrokeCount();
        if (!notebook.retryPendingStroke()) {
            if (!notebook.captureRecovery().sameContent(snapshot)) {
                throw new IllegalStateException("failed recovery changed source content");
            }
            System.out.println("PENDING_COMMIT_REFUSED");
            return;
        }
        if (!notebook.retryPendingStroke() || notebook.getChangeVersion() != revision + 1
                || notebook.getStrokeCount() != strokes + 1 || notebook.pendingStroke() != null
                || notebook.getInputReservedByteCharge() != 0) {
            throw new IllegalStateException("recovery did not commit exactly once");
        }
        var committed = notebook.capture();
        if (!committed.getDocumentId().equals(snapshot.getDocumentId())) {
            throw new IllegalStateException("recovery changed document identity");
        }
        String id = Base64.getEncoder().encodeToString(committed.getDocumentId().getBytes(utf8));
        System.out.write(("READY\n" + id + "\n" + committed.getVersion() + "\n" + committed.encode()).getBytes(utf8));
    }
}
