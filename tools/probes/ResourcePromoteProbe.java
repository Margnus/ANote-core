package dev.notecore.probes;

import android.system.Os;
import android.system.OsConstants;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.AccessDeniedException;
import java.security.MessageDigest;
import java.util.Arrays;
import org.json.JSONObject;

/** Disposable cache-only filesystem investigation; no App or database linkage. */
public final class ResourcePromoteProbe {
    static Path root;
    static byte[] payload() {
        byte[] bytes = new byte[65539];
        for (int i = 0; i < bytes.length; i++) bytes[i] = (byte)((i * 31 + 7) % 251);
        return bytes;
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void write(Path path, byte[] bytes) throws Exception {
        check(!Files.exists(path), "fixture must be fresh");
        try (FileOutputStream stream = new FileOutputStream(path.toFile())) {
            stream.write(bytes); stream.getFD().sync();
        }
    }
    static JSONObject facts(Path path) throws Exception {
        byte[] bytes = Files.readAllBytes(path);
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(bytes)) hex.append(String.format("%02x", b & 255));
        return new JSONObject().put("bytes", bytes.length).put("sha256", hex.toString())
            .put("inode", Os.stat(path.toString()).st_ino).put("device", Os.stat(path.toString()).st_dev);
    }
    static JSONObject pair(String name) throws Exception {
        Path stage = root.resolve(name + ".stage"), target = root.resolve(name + ".resource");
        check(Arrays.equals(Files.readAllBytes(stage), payload()), "complete stage");
        check(Arrays.equals(Files.readAllBytes(target), payload()), "complete target");
        JSONObject a = facts(stage), b = facts(target);
        check(a.toString().equals(b.toString()), "same inode/device/hash");
        return new JSONObject().put("stage", a).put("target", b);
    }
    static void emit(JSONObject value) {
        System.out.println("NOTECORE_PROMOTE " + value.toString()); System.out.flush();
    }
    public static JSONObject execute(String[] args) throws Exception {
            check(args.length == 2, "directory and mode");
            root = Paths.get(args[0]).toAbsolutePath().normalize();
            check(root.getFileName().toString().matches("resource-promote-[a-f0-9-]{36}"), "unique scope");
            check(root.getParent().getFileName().toString().equals("cache"), "App cache only");
            check(new String(Files.readAllBytes(root.resolve("scope.txt")), "UTF-8").equals(root.getFileName().toString()), "scope marker");
            String mode = args[1];
            JSONObject result = new JSONObject().put("mode", mode).put("pid", Os.getpid())
                .put("selinux_context", new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")), "UTF-8").trim());
            if (mode.equals("cases")) {
                write(root.resolve("fresh.stage"), payload());
                try { Files.createLink(root.resolve("fresh.resource"), root.resolve("fresh.stage")); }
                catch (AccessDeniedException denied) {
                    check(!Files.exists(root.resolve("fresh.resource")), "denial creates no target");
                    check(Arrays.equals(Files.readAllBytes(root.resolve("fresh.stage")), payload()), "denial preserves stage");
                    return result.put("status", "access_denied").put("error", denied.toString())
                        .put("stage", facts(root.resolve("fresh.stage"))).put("target_exists", false);
                }
                result.put("fresh", pair("fresh"));
                Files.delete(root.resolve("fresh.stage"));
                check(Arrays.equals(Files.readAllBytes(root.resolve("fresh.resource")), payload()), "stage unlink preserves target");
                result.put("stage_unlink_keeps_target", true);
                for (String name : new String[]{"identical", "corrupt"}) {
                    Path stage = root.resolve(name + ".stage"), target = root.resolve(name + ".resource");
                    write(stage, payload()); write(target, name.equals("identical") ? payload() : new byte[]{1,2,3});
                    String before = facts(target).toString(), stageBefore = facts(stage).toString();
                    String error = "";
                    try { Files.createLink(target, stage); }
                    catch (FileAlreadyExistsException expected) { error = expected.getClass().getName() + ": " + expected.getMessage(); }
                    check(!error.isEmpty(), "must reject existing target");
                    check(before.equals(facts(target).toString()) && stageBefore.equals(facts(stage).toString()), "no mutation");
                    result.put(name, new JSONObject().put("error", error).put("stage", facts(stage)).put("target", facts(target)));
                }
            } else if (mode.equals("crash")) {
                write(root.resolve("crash.stage"), payload());
                Files.createLink(root.resolve("crash.resource"), root.resolve("crash.stage"));
                result.put("linked_before_stage_cleanup", pair("crash"));
                emit(result.put("status", "kill_boundary"));
                Os.kill(Os.getpid(), OsConstants.SIGKILL);
                throw new AssertionError("SIGKILL returned");
            } else if (mode.equals("recover")) {
                result.put("after_process_death", pair("crash"));
            } else throw new AssertionError("mode");
            return result.put("status", "passed");
    }
    public static void main(String[] args) {
        try { emit(execute(args));
        } catch (Throwable failure) { failure.printStackTrace(System.err); System.exit(1); }
    }
}
