import dev.notecore.document.PagedPreviewCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

// Fixture adapter to the original preview reader. This class is never packaged with the App.
class SqliteLegacyFixture {
    public static void main(String[] args) throws Exception {
        byte[] input = System.in.readNBytes(4 * 1024 * 1024 + 1);
        if (input.length > 4 * 1024 * 1024) {
            throw new IllegalArgumentException("fixture exceeds byte limit");
        }
        String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(input)).toString();
        PagedPreviewCodec codec = PagedPreviewCodec.INSTANCE;
        var root = codec.decode(text);
        var canonical = codec.encode(root);
        if (!codec.encode(codec.decode(canonical)).equals(canonical)) {
            throw new IllegalStateException("canonical fixture does not round trip");
        }
        String id = Base64.getEncoder().encodeToString(root.getId().getValue().getBytes(StandardCharsets.UTF_8));
        System.out.write((id + "\n" + root.getRevision() + "\n" + canonical).getBytes(StandardCharsets.UTF_8));
    }
}
