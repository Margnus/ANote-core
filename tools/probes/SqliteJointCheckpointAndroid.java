package dev.notecore.probes;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import android.system.OsConstants;
import dev.notecore.document.PagedPreviewCodec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Self-targeted, disposable API 26 instrumentation with checkpoint storage in its own cache. */
public final class SqliteJointCheckpointAndroid extends Instrumentation {
    private static final String OP = "attach-legacy";
    private String stage, scope;

    private static final class Refused extends Exception {
        final String status;
        Refused(String status) { super(status); this.status = status; }
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static byte[] digest(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] data) {
        char[] output = new char[2 * data.length];
        String digits = "0123456789abcdef";
        for (int index = 0; index < data.length; index++) {
            output[2 * index] = digits.charAt((data[index] & 255) >> 4);
            output[2 * index + 1] = digits.charAt(data[index] & 15);
        }
        return new String(output);
    }

    private static String text(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static byte[] blob(SQLiteDatabase db, String sql, String id) {
        try (Cursor cursor = db.rawQuery(sql, new String[]{id})) {
            return cursor.moveToFirst() ? cursor.getBlob(0) : null;
        }
    }

    private static void checkWriter(SQLiteDatabase db) {
        require("wal".equals(text(db, "PRAGMA journal_mode")), "WAL writer");
        require("2".equals(text(db, "PRAGMA synchronous")), "FULL writer");
        require("1".equals(text(db, "PRAGMA foreign_keys")), "foreign-key enforcement");
    }

    private static final class Checkpoint {
        final String id, identity;
        final long revision;
        final byte[] original, canonical, originalSha, canonicalSha;

        Checkpoint(Path file) throws Exception {
            original = Files.readAllBytes(file);
            String content = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(original)).toString();
            PagedPreviewCodec codec = PagedPreviewCodec.INSTANCE;
            dev.notecore.document.DocumentRoot root = codec.decode(content);
            id = root.getId().getValue();
            revision = root.getRevision();
            canonical = codec.encode(root).getBytes(StandardCharsets.UTF_8);
            originalSha = digest(original);
            canonicalSha = digest(canonical);
            identity = new JSONArray().put(id).put(JSONObject.NULL).put(revision)
                    .put(hex(canonicalSha)).put(hex(originalSha)).toString();
        }
    }

    private static final class Resource {
        final byte[] contents;
        final String sha;
        final Path target;

        Resource(Path input, Path folder) throws Exception {
            contents = Files.readAllBytes(input);
            require(contents.length > 0 && contents.length <= 1024 * 1024, "fixture resource size");
            sha = hex(digest(contents));
            target = folder.resolve(sha);
        }

        void validate() throws Exception {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.size(target) != contents.length
                    || !Arrays.equals(Files.readAllBytes(target), contents)) throw new Refused("REPAIR_REQUIRED");
        }

        void publish(Path folder, AtomicPromoteProbe port) throws Exception {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                validate();
                return;
            }
            Path staged = folder.resolve("stage-" + UUID.randomUUID());
            AtomicPromoteProbe.write(staged, contents);
            require(Arrays.equals(Files.readAllBytes(staged), contents), "closed stage bytes");
            int errno = port.promote(staged, target);
            if (errno != 0 && errno != OsConstants.EEXIST) throw new IllegalStateException("publish errno " + errno);
            validate();
            if (errno == OsConstants.EEXIST) Files.delete(staged);
        }
    }

    private static void record(SQLiteDatabase db, String operation, Checkpoint value) {
        db.execSQL("INSERT INTO document VALUES(?,?,?)", new Object[]{value.id, value.revision, value.canonical});
        db.execSQL("INSERT INTO legacy_source VALUES(?,?)", new Object[]{value.id, value.original});
        db.execSQL("INSERT INTO operation VALUES(?,?,NULL,?,?,?)",
                new Object[]{operation, value.id, value.revision, value.canonicalSha, value.originalSha});
    }

    private static boolean replay(SQLiteDatabase db, Checkpoint value, List<Resource> resources) throws Exception {
        String id = text(db, "SELECT document_id FROM operation WHERE id=?", OP);
        if (id == null) return false;
        if (!value.id.equals(id)
                || !Long.toString(value.revision).equals(text(db, "SELECT revision FROM operation WHERE id=?", OP))
                || !Arrays.equals(blob(db, "SELECT checkpoint_sha256 FROM operation WHERE id=?", OP), value.canonicalSha)
                || !Arrays.equals(blob(db, "SELECT source_sha256 FROM operation WHERE id=?", OP), value.originalSha)
                || !manifestMatches(db, "operation_resource", resources))
            throw new Refused("OPERATION_CONFLICT");
        if (!Arrays.equals(blob(db, "SELECT checkpoint FROM document WHERE id=?", id), value.canonical)
                || !Arrays.equals(blob(db, "SELECT original FROM legacy_source WHERE document_id=?", id), value.original))
            throw new Refused("REPAIR_REQUIRED");
        for (Resource resource : resources) {
            if (!Integer.toString(resource.contents.length).equals(text(db, "SELECT size FROM resource WHERE sha256=?", resource.sha)))
                throw new Refused("REPAIR_REQUIRED");
            resource.validate();
        }
        return true;
    }

    private static boolean manifestMatches(SQLiteDatabase db, String table, List<Resource> resources) {
        try (Cursor cursor = db.rawQuery("SELECT sha256, "
                + (table.equals("import_task_resource") ? "size" : "NULL") + " FROM " + table + " WHERE operation_id=? ORDER BY sha256",
                new String[]{OP})) {
            for (Resource resource : resources) {
                if (!cursor.moveToNext() || !resource.sha.equals(cursor.getString(0))
                        || (table.equals("import_task_resource") && resource.contents.length != cursor.getLong(1)))
                    return false;
            }
            return !cursor.moveToNext();
        }
    }

    private static void cut(Path folder, String stage) throws Exception {
        JSONObject marker = new JSONObject().put("cut", stage).put("pid", Process.myPid()).put("domain", domain());
        AtomicPromoteProbe.write(folder.resolve(stage + ".json"), marker.toString().getBytes(StandardCharsets.UTF_8));
        Process.killProcess(Process.myPid());
        throw new AssertionError("SIGKILL returned");
    }

    private String importCheckpoint(SQLiteDatabase db, Path folder, Checkpoint value, List<Resource> resources)
            throws Exception {
        db.beginTransactionNonExclusive();
        try {
            checkWriter(db);
            if (replay(db, value, resources)) {
                db.setTransactionSuccessful();
                return "REPLAYED";
            }
            String pending = text(db, "SELECT identity FROM import_task WHERE operation_id=?", OP);
            if (pending == null) {
                require(text(db, "SELECT id FROM document WHERE id=?", value.id) == null, "fresh document");
                db.execSQL("INSERT INTO import_task VALUES(?,?,?)", new Object[]{OP, value.id, value.identity});
                for (Resource resource : resources)
                    db.execSQL("INSERT INTO import_task_resource VALUES(?,?,?)",
                            new Object[]{OP, resource.sha, resource.contents.length});
            } else if (!pending.equals(value.identity) || !manifestMatches(db, "import_task_resource", resources)) {
                throw new Refused("OPERATION_CONFLICT");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }

        AtomicPromoteProbe port = new AtomicPromoteProbe();
        for (Resource resource : resources) {
            resource.publish(folder, port);
            if (stage.equals("resource-published")) cut(folder, stage);
        }
        db.beginTransactionNonExclusive();
        try {
            checkWriter(db);
            if (replay(db, value, resources)) {
                db.setTransactionSuccessful();
                return "REPLAYED";
            }
            require(value.identity.equals(text(db, "SELECT identity FROM import_task WHERE operation_id=?", OP))
                    && manifestMatches(db, "import_task_resource", resources), "pending ownership");
            record(db, OP, value);
            for (Resource resource : resources) {
                resource.validate();
                db.execSQL("INSERT OR IGNORE INTO resource VALUES(?,?)",
                        new Object[]{resource.sha, resource.contents.length});
                require(Integer.toString(resource.contents.length).equals(text(db, "SELECT size FROM resource WHERE sha256=?", resource.sha)),
                        "resource size");
                db.execSQL("INSERT INTO operation_resource VALUES(?,?)", new Object[]{OP, resource.sha});
            }
            db.execSQL("DELETE FROM import_task WHERE operation_id=?", new Object[]{OP});
            if (stage.equals("before-commit")) cut(folder, stage);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if (stage.equals("after-commit")) cut(folder, stage);
        return "COMMITTED";
    }

    private static String domain() throws Exception {
        return new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")), StandardCharsets.UTF_8).trim();
    }

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        scope = args.getString("scope");
        stage = args.getString("stage");
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            Path folder = getTargetContext().getCacheDir().toPath().resolve(scope);
            require(scope.matches("dev041-joint-[0-9a-f-]{36}") && Files.isDirectory(folder), "isolated fixture");
            require(stage.matches("init|resource-published|before-commit|after-commit|replay|conflict"), "stage");
            Path path = folder.resolve("checkpoint.sqlite");
            boolean initial = stage.equals("init");
            require(Files.exists(path) != initial, "fixture database lifecycle");
            int flags = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING | (initial ? SQLiteDatabase.CREATE_IF_NECESSARY : 0);
            String status, sqlite;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.toString(), null, flags, damaged -> {})) {
                db.setForeignKeyConstraintsEnabled(true);
                db.execSQL("PRAGMA synchronous=FULL");
                if (initial) {
                    JSONArray statements = new JSONArray(new String(Files.readAllBytes(folder.resolve("schema.json")),
                            StandardCharsets.UTF_8));
                    Checkpoint existing = new Checkpoint(folder.resolve("existing.ncp"));
                    db.beginTransactionNonExclusive();
                    try {
                        checkWriter(db);
                        for (int index = 0; index < statements.length(); index++) db.execSQL(statements.getString(index));
                        db.setVersion(2);
                        record(db, "existing", existing);
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    status = "SEEDED";
                } else {
                    require(db.getVersion() == 2, "schema version");
                    Checkpoint value = new Checkpoint(folder.resolve("legacy.ncp"));
                    Path targets = Files.createDirectories(folder.resolve("resources"));
                    List<Resource> resources = new ArrayList<>();
                    resources.add(new Resource(folder.resolve("resource-a.bin"), targets));
                    if (!stage.equals("conflict")) resources.add(new Resource(folder.resolve("resource-b.bin"), targets));
                    resources.sort(Comparator.comparing(resource -> resource.sha));
                    try { status = importCheckpoint(db, folder, value, resources); }
                    catch (Refused refusal) { status = refusal.status; }
                }
                sqlite = text(db, "SELECT sqlite_version()");
            }
            output.putString("joint", new JSONObject().put("status", status).put("stage", stage)
                    .put("pid", Process.myPid()).put("sqlite", sqlite).put("domain", domain()).toString());
            finish(Activity.RESULT_OK, output);
        } catch (Exception | AssertionError failure) {
            output.putString("failure", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, output);
        }
    }
}
