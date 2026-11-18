package dev.notecore.probes;

import android.app.Activity;
import android.app.Instrumentation;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.os.Process;
import dev.notecore.document.DocumentBudget;
import dev.notecore.document.PagedPreviewNotebook;
import dev.notecore.document.PagedPreviewSnapshot;
import dev.notecore.document.PreviewNotebookOpen;
import dev.notecore.document.PreviewRecoveryCodec;
import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import kotlin.Unit;
import org.json.JSONArray;
import org.json.JSONObject;

/** Disposable instrumentation. Uses only its own private files and the fixed C1 SDK. */
public final class SqlitePendingCheckpointAndroid extends Instrumentation {
    private static final String IMPORT = "recover-legacy";
    private String stage;

    private static void require(boolean condition, String failure) {
        if (!condition) throw new AssertionError(failure);
    }

    private static byte[] hash(byte[] data) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(data);
    }

    private static String text(SQLiteDatabase db, String sql, String... args) {
        try (Cursor rows = db.rawQuery(sql, args)) {
            require(rows.moveToFirst() && rows.getCount() == 1, sql);
            return rows.getString(0);
        }
    }

    private byte[] asset(String name) throws Exception {
        try (InputStream stream = getTargetContext().getAssets().open(name)) {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) contents.write(buffer, 0, count);
            return contents.toByteArray();
        }
    }

    private JSONObject event() throws Exception {
        String domain = new String(Files.readAllBytes(Paths.get("/proc/self/attr/current")), StandardCharsets.UTF_8).trim();
        return new JSONObject().put("stage", stage).put("pid", Process.myPid()).put("domain", domain);
    }

    private void cut(Path folder) throws Exception {
        try (FileOutputStream out = new FileOutputStream(folder.resolve(stage + ".json").toFile())) {
            out.write(event().toString().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
        Process.killProcess(Process.myPid());
        throw new AssertionError("SIGKILL returned");
    }

    private static void verifyState(SQLiteDatabase db, PagedPreviewSnapshot snapshot, byte[] original,
                                    byte[] canonical, boolean committed) throws Exception {
        require("ok".equals(text(db, "PRAGMA integrity_check")), "database integrity");
        try (Cursor rows = db.rawQuery("PRAGMA foreign_key_check", null)) {
            require(!rows.moveToFirst(), "foreign keys");
        }
        if (!committed) {
            for (String name : new String[]{"document", "legacy_source", "operation"}) {
                require("0".equals(text(db, "SELECT count(*) FROM " + name)), "unexpected " + name);
            }
            return;
        }
        String id = snapshot.getDocumentId();
        long revision = snapshot.getVersion() + 1;
        try (Cursor rows = db.rawQuery("SELECT id,revision,checkpoint FROM document", null)) {
            require(rows.moveToFirst() && id.equals(rows.getString(0)) && revision == rows.getLong(1)
                    && Arrays.equals(canonical, rows.getBlob(2)) && !rows.moveToNext(), "canonical checkpoint");
        }
        try (Cursor rows = db.rawQuery("SELECT document_id,original FROM legacy_source", null)) {
            require(rows.moveToFirst() && id.equals(rows.getString(0))
                    && Arrays.equals(original, rows.getBlob(1)) && !rows.moveToNext(), "archived source");
        }
        try (Cursor rows = db.rawQuery(
                "SELECT id,document_id,base_revision,revision,checkpoint_sha256,source_sha256 FROM operation", null)) {
            require(rows.moveToFirst() && IMPORT.equals(rows.getString(0)) && id.equals(rows.getString(1))
                    && rows.isNull(2) && revision == rows.getLong(3) && Arrays.equals(hash(canonical), rows.getBlob(4))
                    && Arrays.equals(hash(original), rows.getBlob(5)) && !rows.moveToNext(), "operation identity");
        }
    }

    private String migrate(SQLiteDatabase db, Path folder, PagedPreviewSnapshot snapshot, byte[] original,
                           byte[] canonical) throws Exception {
        long inputBytes = stage.equals("capacity") ? 2247L : 2248L;
        long mib = 1024L * 1024L;
        DocumentBudget budget = new DocumentBudget(128, 5000, 8 * mib, 4096, 32 * mib, 4 * mib, 128, 8 * mib, inputBytes);
        PreviewNotebookOpen opened =
                PagedPreviewNotebook.Companion.openRecovery(snapshot, "sqlite-import", budget, () -> Unit.INSTANCE);
        PagedPreviewNotebook notebook = opened.getNotebook();
        if (notebook == null) return opened.getFailure().name();
        require(notebook.captureRecovery().sameContent(snapshot), "exact reopened source");
        int strokes = notebook.getStrokeCount();
        require(notebook.retryPendingStroke() && notebook.retryPendingStroke(), "pending retry");
        require(notebook.getStrokeCount() == strokes + 1 && notebook.getChangeVersion() == snapshot.getVersion() + 1
                && notebook.pendingStroke() == null && notebook.getInputReservedByteCharge() == 0, "one stroke");
        byte[] recovered = notebook.capture().encode().getBytes(StandardCharsets.UTF_8);
        require(Arrays.equals(canonical, recovered), "cross-platform canonical bytes");

        db.beginTransactionNonExclusive();
        try {
            require("wal".equals(text(db, "PRAGMA journal_mode")) && "2".equals(text(db, "PRAGMA synchronous"))
                    && "1".equals(text(db, "PRAGMA foreign_keys")), "writer settings");
            boolean committed = !"0".equals(text(db, "SELECT count(*) FROM document"));
            verifyState(db, snapshot, original, recovered, committed);
            String operation = stage.equals("duplicate") ? "another-import" : IMPORT;
            if (committed) {
                String exists = text(db, "SELECT count(*) FROM operation WHERE id=?", operation);
                db.setTransactionSuccessful();
                return exists.equals("1") ? "REPLAYED" : "STALE_BASE";
            }
            require(stage.equals("before-commit") || stage.equals("after-commit"), "fresh import attempt");
            String id = snapshot.getDocumentId();
            long revision = notebook.getChangeVersion();
            db.execSQL("INSERT INTO document VALUES(?,?,?)", new Object[]{id, revision, recovered});
            db.execSQL("INSERT INTO legacy_source VALUES(?,?)", new Object[]{id, original});
            db.execSQL("INSERT INTO operation VALUES(?,?,?,?,?,?)",
                    new Object[]{operation, id, null, revision, hash(recovered), hash(original)});
            if (stage.equals("before-commit")) cut(folder);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        cut(folder);
        throw new AssertionError("unreachable");
    }

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        stage = args.getString("stage");
        start();
    }

    @Override public void onStart() {
        Bundle output = new Bundle();
        try {
            require(stage.matches("seed|capacity|invalid|before-commit|empty|after-commit|present|replay|duplicate"), "stage");
            boolean seed = stage.equals("seed");
            Path folder = getTargetContext().getFilesDir().toPath();
            Path source = folder.resolve("legacy-recovery.ncp");
            byte[] original = asset("prepare.ncp");
            byte[] canonical = asset("confirmed-final.ncp");
            if (seed) {
                Files.createFile(source);
                Files.write(source, original);
            }
            require(Arrays.equals(original, Files.readAllBytes(source)), "unchanged source");
            byte[] input = stage.equals("invalid") ? Arrays.copyOf(original, original.length - 1) : original;
            PagedPreviewSnapshot snapshot = PreviewRecoveryCodec.INSTANCE.read(
                    new String(input, StandardCharsets.UTF_8)).getSnapshot();
            Path path = folder.resolve("pending.sqlite");
            require(Files.exists(path) != seed, "fixture database lifecycle");
            int flags = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING | (seed ? SQLiteDatabase.CREATE_IF_NECESSARY : 0);
            String status, sqlite;
            try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.toString(), null, flags, damaged -> {})) {
                db.setForeignKeyConstraintsEnabled(true);
                db.execSQL("PRAGMA synchronous=FULL");
                if (seed) {
                    JSONArray schema = new JSONArray(new String(asset("schema.json"), StandardCharsets.UTF_8));
                    db.beginTransactionNonExclusive();
                    try {
                        for (int i = 0; i < schema.length(); i++) db.execSQL(schema.getString(i));
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    status = "SEEDED";
                } else if (stage.equals("invalid")) {
                    require(snapshot == null, "truncation must not return a base-only document");
                    status = "INVALID_SOURCE";
                } else if (stage.equals("empty") || stage.equals("present")) {
                    verifyState(db, snapshot, original, canonical, stage.equals("present"));
                    status = stage.equals("present") ? "PRESENT" : "EMPTY";
                } else {
                    require(snapshot != null && snapshot.getPending() != null, "complete envelope");
                    status = migrate(db, folder, snapshot, original, canonical);
                }
                sqlite = text(db, "SELECT sqlite_version()");
            }
            output.putString("pending", event().put("status", status).put("sqlite", sqlite).toString());
            finish(Activity.RESULT_OK, output);
        } catch (Exception | AssertionError failure) {
            output.putString("failure", android.util.Log.getStackTraceString(failure));
            finish(Activity.RESULT_CANCELED, output);
        }
    }
}
