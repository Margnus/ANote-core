package dev.notecore.probes;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/** Self-targeted disposable APK. No NoteCore App, document, worker or database dependencies. */
public final class AtomicPromoteProbe extends Instrumentation {
    static { System.loadLibrary("notecore_atomic_promote_probe"); }
    private static native int renameNoReplace(String source, String target);
    private String mode, scope;
    private Path root;
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static byte[] payload(int seed) {
        byte[] value = new byte[65539];
        for (int i = 0; i < value.length; i++) value[i] = (byte)((i * 31 + seed) % 251);
        return value;
    }
    static void write(Path path, byte[] value) throws Exception {
        check(!Files.exists(path), "fresh fixture only");
        try (FileOutputStream file = new FileOutputStream(path.toFile())) {
            file.write(value); file.getFD().sync();
        }
    }
    static JSONObject facts(Path path) throws Exception {
        byte[] value = Files.readAllBytes(path);
        StringBuilder digest = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(value)) digest.append(String.format("%02x", b & 255));
        return new JSONObject().put("bytes", value.length).put("sha256", digest.toString())
            .put("inode", Os.stat(path.toString()).st_ino).put("device", Os.stat(path.toString()).st_dev);
    }
    static boolean same(JSONObject a, JSONObject b) { return a.toString().equals(b.toString()); }
    int promote(Path source, Path target) { return renameNoReplace(source.toString(), target.toString()); }
    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments); mode = arguments.getString("mode"); scope = arguments.getString("scope"); start();
    }
    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            check(scope != null && scope.matches("resource-atomic-[a-f0-9-]{36}"), "unique scope");
            root = getTargetContext().getCacheDir().toPath().resolve(scope);
            JSONObject result = new JSONObject().put("pid", Process.myPid()).put("mode", mode)
                .put("selinux_context", new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")), "UTF-8").trim());
            if (mode.equals("cases")) {
                Files.createDirectory(root); write(root.resolve("scope.txt"), scope.getBytes("UTF-8"));
                Path stage = root.resolve("fresh.stage"), target = root.resolve("fresh.resource");
                write(stage, payload(7)); JSONObject original = facts(stage);
                int error = promote(stage, target);
                if (error != 0) {
                    check(!Files.exists(target) && same(original, facts(stage)), "capability refusal preserves stage");
                    result.put("status", "unsupported").put("errno", error).put("stage", facts(stage));
                    output.putString("atomic_promote", result.toString()); finish(Activity.RESULT_OK, output); return;
                }
                check(!Files.exists(stage) && same(original, facts(target)), "atomic move identity and bytes");
                result.put("fresh", new JSONObject().put("errno", 0).put("target", facts(target)).put("stage_absent", true));
                for (String name : new String[]{"identical", "corrupt"}) {
                    stage = root.resolve(name + ".stage"); target = root.resolve(name + ".resource");
                    write(stage, payload(7)); write(target, name.equals("identical") ? payload(7) : new byte[]{1,2,3});
                    JSONObject beforeStage = facts(stage), beforeTarget = facts(target);
                    error = promote(stage, target);
                    check(error == OsConstants.EEXIST && same(beforeStage, facts(stage)) && same(beforeTarget, facts(target)), "never overwrite existing target");
                    result.put(name, new JSONObject().put("errno", error).put("stage", facts(stage)).put("target", facts(target)));
                }
                result.put("race", race());
            } else {
                check(new String(Files.readAllBytes(root.resolve("scope.txt")), "UTF-8").equals(scope), "marker");
                Path stage = root.resolve("crash.stage"), target = root.resolve("crash.resource");
                if (mode.equals("crash")) {
                    write(stage, payload(7)); JSONObject original = facts(stage);
                    int error = promote(stage, target);
                    check(error == 0 && !Files.exists(stage) && same(original, facts(target)), "rename before process death");
                    JSONObject marker = new JSONObject().put("pid", Process.myPid()).put("errno", error)
                        .put("target", facts(target)).put("stage_absent", true);
                    write(root.resolve("crash-marker.json"), marker.toString().getBytes("UTF-8"));
                    Process.killProcess(Process.myPid()); throw new AssertionError("SIGKILL returned");
                }
                check(mode.equals("recover"), "mode");
                JSONObject marker = new JSONObject(new String(Files.readAllBytes(root.resolve("crash-marker.json")), "UTF-8"));
                check(marker.getInt("pid") != Process.myPid() && marker.getInt("errno") == 0 && !Files.exists(stage), "new process, moved stage");
                check(same(marker.getJSONObject("target"), facts(target)) && Arrays.equals(Files.readAllBytes(target), payload(7)), "complete target after death");
                result.put("before_death", marker).put("after_death", facts(target));
            }
            output.putString("atomic_promote", result.put("status", "passed").toString()); finish(Activity.RESULT_OK, output);
        } catch (Throwable failure) {
            output.putString("atomic_failure", failure.toString()); finish(Activity.RESULT_CANCELED, output);
        }
    }
    JSONObject race() throws Exception {
        final Path[] sources = {root.resolve("race-a.stage"), root.resolve("race-b.stage")};
        final Path target = root.resolve("race.resource");
        final JSONObject[] original = new JSONObject[2]; final int[] errors = {-1,-1};
        final CountDownLatch ready = new CountDownLatch(2), go = new CountDownLatch(1);
        final AtomicReference<Throwable> failed = new AtomicReference<>(); Thread[] threads = new Thread[2];
        for (int i = 0; i < 2; i++) {
            write(sources[i], payload(i == 0 ? 7 : 13)); original[i] = facts(sources[i]); final int index = i;
            threads[i] = new Thread(new Runnable() { public void run() {
                try { ready.countDown(); go.await(); errors[index] = promote(sources[index], target); }
                catch (Throwable error) { failed.set(error); }
            }}); threads[i].start();
        }
        ready.await(); go.countDown(); for (Thread thread : threads) thread.join();
        check(failed.get() == null, "race execution");
        int winner = errors[0] == 0 ? 0 : 1, loser = 1-winner;
        check(errors[winner] == 0 && errors[loser] == OsConstants.EEXIST, "exactly one winner");
        check(!Files.exists(sources[winner]) && same(original[winner], facts(target)), "winner bytes/inode");
        check(same(original[loser], facts(sources[loser])), "loser stage preserved");
        return new JSONObject().put("winner", winner).put("winner_errno", errors[winner]).put("loser_errno", errors[loser])
            .put("target", facts(target)).put("loser_stage", facts(sources[loser]));
    }
}
