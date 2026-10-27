import java.io.*;
import java.nio.file.*;
// Synthetic framing oracle uses the same DataOutputStream primitives as the pinned source.
public class LegacyContainerFixture {
  public static void main(String[] args) throws Exception {
    try (var out = new DataOutputStream(Files.newOutputStream(Path.of(args[0])))) {
      out.writeUTF("TRY"); out.writeInt(2); out.writeUTF("synthetic-note");
      out.writeInt(1); out.writeUTF("NOTE"); out.writeUTF("DB"); out.writeUTF("中文😀\u0000");
      byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
      out.writeLong(body.length); out.writeUTF(""); out.writeUTF(""); out.writeUTF(""); out.write(body);
      out.writeInt(1); out.writeUTF("FUTURE_TYPE"); out.writeUTF("LOCAL"); out.writeUTF("../never-extracted");
      out.writeLong(3); out.writeUTF(""); out.writeUTF("C:\\untrusted"); out.writeUTF(""); out.write(new byte[]{0,1,2});
      out.writeInt(0); out.writeInt(2); out.writeUTF("Lucky"); out.writeLong(19);
    }
  }
}
