package dev.notecore.probes;

import android.app.Instrumentation;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Bundle;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import dev.notecore.document.PagedPreviewCodec;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import java.util.TreeMap;

/** Self-targeted, disposable instrumentation. The database and files live under its own UID. */
public final class AndroidJointCheckpointProbe extends Instrumentation {
    private static final String OPERATION = "attach-legacy";
    private static final String[] INPUTS = {"legacy.ncp", "existing.ncp", "resource-a.bin", "resource-b.bin"};
    private static final String TAG = "dev041";
    private static native int renameNoReplace(String source, String target);

    private Context context;
    private File root;
    private File resources;
    private String stage;
    private String domain;
    private final JSONObject publications = new JSONObject();

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        stage = args.getString("stage");
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            result.putString(TAG, execute().toString());
            finish(-1, result);
        } catch (Exception | AssertionError | LinkageError error) {
            result.putString("probe_error", android.util.Log.getStackTraceString(error));
            finish(0, result);
        }
    }

    private JSONObject execute() throws Exception {
        context = getTargetContext();
        require(Build.VERSION.SDK_INT == 26, "API 26 required");
        require(context.getPackageName().matches("dev\\.notecore\\.probes\\.dev041\\.p[a-f0-9]{32}"), "disposable package required");
        require(context.getPackageName().equals(getContext().getPackageName()), "must self-target");
        domain = new String(Files.readAllBytes(new File("/proc/self/attr/current").toPath()), StandardCharsets.UTF_8).trim();
        require(domain.startsWith("u:r:untrusted_app:") && Process.myUid() >= 10000, "expected untrusted_app UID");
        root = new File(context.getFilesDir(), "dev041");
        resources = new File(root, "resources");
        boolean init = "init".equals(stage);
        if (init) {
            require(!root.exists() && resources.mkdirs(), "fixture already exists");
            for (String name : INPUTS) sync(new File(root, name), asset(name));
        }
        require(resources.isDirectory(), "missing fixture");

        TreeMap<String, byte[]> manifest = new TreeMap<>();
        for (String name : new String[]{"resource-a.bin", "resource-b.bin"}) {
            byte[] payload = input(name);
            manifest.put(hex(digest(payload)), payload);
        }
        require(manifest.size() == 2, "expected two distinct resources");
        int flags = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        if (init) flags |= SQLiteDatabase.CREATE_IF_NECESSARY;

        File path = new File(root, "checkpoint.sqlite");
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getPath(), null, flags, ignored -> {})) {
            db.setForeignKeyConstraintsEnabled(true);
            String normal = null;
            if (init) {
                db.execSQL("PRAGMA synchronous=NORMAL");
                db.beginTransactionNonExclusive();
                try {
                    normal = scalar(db, "PRAGMA synchronous");
                    require("1".equals(normal), "NORMAL control did not reach writer");
                } finally {
                    db.endTransaction();
                }
            }
            db.execSQL("PRAGMA synchronous=FULL");
            String status;
            switch (stage) {
                case "init":
                    seed(db);
                    status = "SEEDED";
                    break;
                case "inspect":
                    status = "INSPECTED";
                    break;
                case "inject-occupied":
                case "remove-occupied":
                case "damage-committed":
                    alterSyntheticTarget(manifest);
                    status = "INJECTED";
                    break;
                case "resource-published":
                case "before-commit":
                case "after-commit":
                case "replay":
                case "conflict":
                    if ("conflict".equals(stage)) manifest.pollLastEntry();
                    System.loadLibrary("notecore_atomic_promote_probe");
                    status = apply(db, new Checkpoint(input("legacy.ncp")), manifest);
                    break;
                default:
                    throw new AssertionError("unknown stage " + stage);
            }
            JSONObject event = event(status).put("normal_control", normal);
            return event.put("state", snapshot(db));
        }
    }

    private void seed(SQLiteDatabase db) throws Exception {
        JSONArray schema = new JSONArray(new String(asset("schema.json"), StandardCharsets.UTF_8));
        db.beginTransactionNonExclusive();
        try {
            verifyWriter(db);
            for (int index = 0; index < schema.length(); index++) db.execSQL(schema.getString(index));
            db.setVersion(2);
            saveCheckpoint(db, "existing", new Checkpoint(input("existing.ncp")));
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    private String apply(SQLiteDatabase db, Checkpoint checkpoint, TreeMap<String, byte[]> manifest) throws Exception {
        JSONArray pins = manifestRows(manifest);
        String identity = checkpoint.id + ":" + checkpoint.revision + ":" + hex(digest(checkpoint.body))
                + ":" + hex(digest(checkpoint.original)) + ":" + pins;
        db.beginTransactionNonExclusive();
        try {
            verifyWriter(db);
            JSONArray receipt = rows(db, "SELECT document_id,base_revision,revision,lower(hex(checkpoint_sha256)),"
                    + "lower(hex(source_sha256)) FROM operation WHERE id=?", OPERATION);
            if (receipt.length() != 0) {
                String expected = new JSONArray().put(new JSONArray().put(checkpoint.id).put(JSONObject.NULL)
                        .put(checkpoint.revision).put(hex(digest(checkpoint.body))).put(hex(digest(checkpoint.original)))).toString();
                if (!expected.equals(receipt.toString()) || !same(pins, rows(db,
                        "SELECT r.sha256,r.size FROM operation_resource j JOIN resource r ON r.sha256=j.sha256 "
                                + "WHERE j.operation_id=? ORDER BY r.sha256", OPERATION))) return "OPERATION_CONFLICT";
                JSONArray stored = rows(db, "SELECT d.revision,lower(hex(d.checkpoint)),lower(hex(s.original)) "
                        + "FROM document d JOIN legacy_source s ON s.document_id=d.id WHERE d.id=?", checkpoint.id);
                JSONArray expectedContent = new JSONArray().put(new JSONArray().put(checkpoint.revision)
                        .put(hex(checkpoint.body)).put(hex(checkpoint.original)));
                if (!same(stored, expectedContent)) return "REPAIR_REQUIRED";
                for (Map.Entry<String, byte[]> item : manifest.entrySet()) {
                    if (!Arrays.equals(item.getValue(), read(new File(resources, item.getKey())))) return "REPAIR_REQUIRED";
                }
                db.setTransactionSuccessful();
                return "REPLAYED";
            }
            String pending = scalar(db, "SELECT identity FROM import_task WHERE operation_id=?", OPERATION);
            if (pending == null) {
                db.execSQL("INSERT INTO import_task VALUES(?,?,?)", new Object[]{OPERATION, checkpoint.id, identity});
                for (Map.Entry<String, byte[]> item : manifest.entrySet()) {
                    db.execSQL("INSERT INTO import_task_resource VALUES(?,?,?)",
                            new Object[]{OPERATION, item.getKey(), item.getValue().length});
                }
            } else if (!pending.equals(identity) || !same(pins, rows(db,
                    "SELECT sha256,size FROM import_task_resource WHERE operation_id=? ORDER BY sha256", OPERATION))) {
                return "OPERATION_CONFLICT";
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }

        for (Map.Entry<String, byte[]> item : manifest.entrySet()) {
            if (!publish(item.getKey(), item.getValue())) return "REPAIR_REQUIRED";
            if ("resource-published".equals(stage)) cut(db, false);
        }

        db.beginTransactionNonExclusive();
        try {
            verifyWriter(db);
            require(identity.equals(scalar(db, "SELECT identity FROM import_task WHERE operation_id=?", OPERATION)),
                    "missing task identity before joint write");
            saveCheckpoint(db, OPERATION, checkpoint);
            for (Map.Entry<String, byte[]> item : manifest.entrySet()) {
                db.execSQL("INSERT INTO resource VALUES(?,?)", new Object[]{item.getKey(), item.getValue().length});
                db.execSQL("INSERT INTO operation_resource VALUES(?,?)", new Object[]{OPERATION, item.getKey()});
            }
            db.execSQL("DELETE FROM import_task WHERE operation_id=?", new Object[]{OPERATION});
            if ("before-commit".equals(stage)) cut(db, true);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if ("after-commit".equals(stage)) cut(db, false);
        return "COMMITTED";
    }

    private boolean publish(String sha, byte[] payload) throws Exception {
        File staging = File.createTempFile(".stage-", ".bin", resources);
        sync(staging, payload);
        require(Arrays.equals(payload, read(staging)), "stage verification failed");
        long stagedInode = Os.stat(staging.getPath()).st_ino;
        File target = new File(resources, sha);
        int errno = renameNoReplace(staging.getPath(), target.getPath());
        JSONObject observation = new JSONObject().put("errno", errno).put("staged_inode", stagedInode);
        publications.put(sha, observation);
        if (errno != 0 && errno != OsConstants.EEXIST) throw new AssertionError("renameat2 errno " + errno);
        if (errno == OsConstants.EEXIST) {
            require(stagedInode == Os.stat(staging.getPath()).st_ino && Arrays.equals(payload, read(staging)),
                    "no-replace conflict changed staging");
            observation.put("source_preserved", true);
            if (!Arrays.equals(payload, read(target))) return false;
            require(staging.delete(), "could not retire verified duplicate stage");
        } else {
            require(!staging.exists() && Os.stat(target.getPath()).st_ino == stagedInode, "rename changed file identity");
        }
        require(Arrays.equals(payload, read(target)), "published resource verification failed");
        return true;
    }

    private void alterSyntheticTarget(TreeMap<String, byte[]> manifest) throws Exception {
        File target = new File(resources, manifest.lastKey());
        byte[] payload = manifest.lastEntry().getValue();
        byte[] damage = payload.clone();
        damage[0] ^= 1;
        if ("remove-occupied".equals(stage)) {
            require(Arrays.equals(damage, read(target)) && target.delete(), "refusing to remove unexpected target");
        } else {
            require("inject-occupied".equals(stage) ? target.createNewFile() : Arrays.equals(payload, read(target)),
                    "unexpected target before corruption");
            sync(target, damage);
        }
    }

    private void cut(SQLiteDatabase db, boolean insideTransaction) throws Exception {
        require(db.inTransaction() == insideTransaction, "cut on wrong transaction boundary");
        JSONObject marker = event("CUT").put("inside_transaction", insideTransaction);
        if (insideTransaction) {
            verifyWriter(db);
            marker.put("uncommitted_state", state(db));
        }
        sync(new File(root, stage + ".json"), marker.toString().getBytes(StandardCharsets.UTF_8));
        android.util.Log.i("NoteCoreDev041", marker.toString());
        Process.killProcess(Process.myPid());
        throw new AssertionError("SIGKILL returned");
    }

    private JSONObject event(String status) throws Exception {
        return new JSONObject().put("status", status).put("stage", stage).put("pid", Process.myPid())
                .put("uid", Process.myUid()).put("domain", domain).put("publications", publications);
    }

    private JSONObject snapshot(SQLiteDatabase db) throws Exception {
        db.beginTransactionNonExclusive();
        try {
            verifyWriter(db);
            JSONObject result = state(db).put("sqlite", scalar(db, "SELECT sqlite_version()"))
                    .put("journal_mode", scalar(db, "PRAGMA journal_mode"))
                    .put("writer_synchronous", scalar(db, "PRAGMA synchronous"))
                    .put("integrity", scalar(db, "PRAGMA integrity_check"))
                    .put("foreign_keys", rows(db, "PRAGMA foreign_key_check"))
                    .put("schema_version", db.getVersion());
            JSONObject targets = new JSONObject();
            File[] files = resources.listFiles();
            require(files != null, "resource directory unreadable");
            for (File file : files) {
                if (file.getName().startsWith(".stage-")) continue;
                byte[] data = read(file);
                targets.put(file.getName(), new JSONArray().put(hex(digest(data))).put(data.length)
                        .put(Os.stat(file.getPath()).st_ino));
            }
            JSONObject inputs = new JSONObject();
            for (String name : INPUTS) inputs.put(name, hex(digest(input(name))));
            db.setTransactionSuccessful();
            return result.put("resources_on_disk", targets).put("inputs", inputs);
        } finally {
            db.endTransaction();
        }
    }

    private static JSONObject state(SQLiteDatabase db) throws Exception {
        return new JSONObject()
                .put("documents", rows(db, "SELECT id,revision,lower(hex(checkpoint)) FROM document ORDER BY id"))
                .put("sources", rows(db, "SELECT document_id,lower(hex(original)) FROM legacy_source ORDER BY document_id"))
                .put("operations", rows(db, "SELECT id,document_id,base_revision,revision,lower(hex(checkpoint_sha256)),"
                        + "lower(hex(source_sha256)) FROM operation ORDER BY id"))
                .put("resources", rows(db, "SELECT sha256,size FROM resource ORDER BY sha256"))
                .put("references", rows(db, "SELECT operation_id,sha256 FROM operation_resource ORDER BY 1,2"))
                .put("tasks", rows(db, "SELECT operation_id,document_id FROM import_task ORDER BY operation_id"))
                .put("pins", rows(db, "SELECT operation_id,sha256,size FROM import_task_resource ORDER BY 1,2"));
    }

    private static JSONArray manifestRows(TreeMap<String, byte[]> manifest) {
        JSONArray result = new JSONArray();
        for (Map.Entry<String, byte[]> item : manifest.entrySet()) {
            result.put(new JSONArray().put(item.getKey()).put(item.getValue().length));
        }
        return result;
    }

    private static void saveCheckpoint(SQLiteDatabase db, String operation, Checkpoint checkpoint) throws Exception {
        db.execSQL("INSERT INTO document VALUES(?,?,?)", new Object[]{checkpoint.id, checkpoint.revision, checkpoint.body});
        db.execSQL("INSERT INTO legacy_source VALUES(?,?)", new Object[]{checkpoint.id, checkpoint.original});
        db.execSQL("INSERT INTO operation VALUES(?,?,NULL,?,?,?)", new Object[]{operation, checkpoint.id, checkpoint.revision,
                digest(checkpoint.body), digest(checkpoint.original)});
    }

    private static void verifyWriter(SQLiteDatabase db) {
        require("wal".equals(scalar(db, "PRAGMA journal_mode")) && "2".equals(scalar(db, "PRAGMA synchronous"))
                && "1".equals(scalar(db, "PRAGMA foreign_keys")), "expected WAL/FULL and foreign keys on writer");
    }

    private static String scalar(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static JSONArray rows(SQLiteDatabase db, String sql, String... args) {
        JSONArray values = new JSONArray();
        try (Cursor cursor = db.rawQuery(sql, args)) {
            while (cursor.moveToNext()) {
                JSONArray row = new JSONArray();
                for (int index = 0; index < cursor.getColumnCount(); index++) {
                    int type = cursor.getType(index);
                    if (type == Cursor.FIELD_TYPE_NULL) row.put(JSONObject.NULL);
                    else if (type == Cursor.FIELD_TYPE_INTEGER) row.put(cursor.getLong(index));
                    else row.put(cursor.getString(index));
                }
                values.put(row);
            }
        }
        return values;
    }

    private static boolean same(JSONArray left, JSONArray right) {
        return left.toString().equals(right.toString());
    }

    private static final class Checkpoint {
        final byte[] original;
        final byte[] body;
        final String id;
        final long revision;

        Checkpoint(byte[] source) throws Exception {
            original = source;
            String text = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(source)).toString();
            PagedPreviewCodec codec = PagedPreviewCodec.INSTANCE;
            var document = codec.decode(text);
            String canonical = codec.encode(document);
            require(canonical.equals(codec.encode(codec.decode(canonical))), "canonical body did not round trip");
            body = canonical.getBytes(StandardCharsets.UTF_8);
            id = document.getId().getValue();
            revision = document.getRevision();
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream stream = context.getAssets().open(name)) {
            ByteArrayOutputStream data = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            for (int count; (count = stream.read(buffer)) != -1;) data.write(buffer, 0, count);
            return data.toByteArray();
        }
    }

    private byte[] input(String name) throws Exception {
        return Files.readAllBytes(new File(root, name).toPath());
    }

    private static byte[] read(File file) throws Exception {
        return file.isFile() ? Files.readAllBytes(file.toPath()) : null;
    }

    private static void sync(File file, byte[] data) throws Exception {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(data);
            stream.getFD().sync();
        }
    }

    private static byte[] digest(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String hex(byte[] bytes) {
        char[] chars = new char[bytes.length * 2];
        String digits = "0123456789abcdef";
        for (int index = 0; index < bytes.length; index++) {
            chars[2 * index] = digits.charAt((bytes[index] >>> 4) & 15);
            chars[2 * index + 1] = digits.charAt(bytes[index] & 15);
        }
        return new String(chars);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
