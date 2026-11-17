import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Build;
import android.os.Process;
import org.json.JSONObject;
import java.io.File;

/** Test-only app_process entry for the pre-OpenParams Android SQLite path. */
public final class SqliteMinimumProbe {
    private static String query(SQLiteDatabase db, String sql, String... args) {
        try (Cursor cursor = db.rawQuery(sql, args)) {
            return cursor.moveToFirst() ? cursor.getString(0) : null;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void verifyWriter(SQLiteDatabase db) {
        require("wal".equals(query(db, "PRAGMA journal_mode")), "writer must use WAL");
        require("2".equals(query(db, "PRAGMA synchronous")), "writer must use FULL");
    }

    private static void emit(JSONObject result) {
        System.out.println("NOTECORE_SQLITE_MINIMUM " + result);
        System.out.flush();
    }

    private static void cut(String stage) throws Exception {
        emit(new JSONObject().put("cut", stage).put("pid", Process.myPid()));
        Process.killProcess(Process.myPid());
        throw new AssertionError("SIGKILL returned");
    }

    private static String apply(SQLiteDatabase db, String payload, String stage) throws Exception {
        db.beginTransactionNonExclusive();
        try {
            verifyWriter(db);
            String prior = query(db, "SELECT payload FROM operations WHERE id=?", "op-edit");
            if (prior != null) {
                require(prior.equals(payload), "operation identity conflict");
                db.setTransactionSuccessful();
                return "REPLAYED";
            }
            require("1".equals(query(db, "SELECT revision FROM document WHERE id=1")), "stale base");
            db.execSQL("INSERT INTO operations VALUES(?,?)", new Object[]{"op-edit", payload});
            db.execSQL("UPDATE document SET revision=2,payload=? WHERE id=1", new Object[]{payload});
            if ("before-commit".equals(stage)) cut(stage);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        if ("after-commit".equals(stage)) cut(stage);
        return "COMMITTED";
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "expected isolated directory and stage");
        require(Build.VERSION.SDK_INT == 26 || Build.VERSION.SDK_INT == 27, "expected minimum OS");
        File directory = new File(args[0]).getCanonicalFile();
        require(directory.getPath().matches("/data/local/tmp/notecore-sqlite-minimum-[a-f0-9]{32}"),
                "refusing non-fixture directory");
        require(directory.isDirectory(), "fixture directory missing");
        String stage = args[1];
        require(stage.matches("init|verify|before-commit|after-commit|replay|conflict"), "invalid stage");
        File path = new File(directory, "probe.sqlite");
        int flags = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        if ("init".equals(stage)) flags |= SQLiteDatabase.CREATE_IF_NECESSARY;
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(path.getPath(), null, flags, ignored -> {})) {
            String normalControl = null;
            if ("init".equals(stage)) {
                // This image already defaults to FULL. Observe NORMAL on the writer before proving the switch.
                db.execSQL("PRAGMA synchronous=NORMAL");
                db.beginTransactionNonExclusive();
                try {
                    normalControl = query(db, "PRAGMA synchronous");
                    require("1".equals(normalControl), "NORMAL control did not reach writer");
                } finally {
                    db.endTransaction();
                }
            }
            db.execSQL("PRAGMA synchronous=FULL");
            String status = stage;
            if ("init".equals(stage)) {
                db.beginTransactionNonExclusive();
                try {
                    verifyWriter(db);
                    db.execSQL("CREATE TABLE document(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER,payload TEXT)");
                    db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY NOT NULL,payload TEXT NOT NULL)");
                    db.execSQL("INSERT INTO document VALUES(1,1,'last-confirmed')");
                    db.setTransactionSuccessful();
                } finally {
                    db.endTransaction();
                }
            } else if ("conflict".equals(stage)) {
                boolean refused = false;
                try {
                    apply(db, "different-payload", stage);
                } catch (AssertionError error) {
                    require("operation identity conflict".equals(error.getMessage()), error.getMessage());
                    refused = true;
                }
                require(refused, "conflict unexpectedly accepted");
                status = "REFUSED";
            } else if (!"verify".equals(stage)) {
                status = apply(db, "new-checkpoint", stage);
            }
            db.beginTransactionNonExclusive();
            try {
                verifyWriter(db);
                emit(new JSONObject()
                        .put("stage", stage)
                        .put("status", status)
                        .put("pid", Process.myPid())
                        .put("sqlite", query(db, "SELECT sqlite_version()"))
                        .put("normal_control_synchronous", normalControl)
                        .put("writer_synchronous", query(db, "PRAGMA synchronous"))
                        .put("journal_mode", query(db, "PRAGMA journal_mode"))
                        .put("revision", query(db, "SELECT revision FROM document WHERE id=1"))
                        .put("payload", query(db, "SELECT payload FROM document WHERE id=1"))
                        .put("operation_count", query(db, "SELECT count(*) FROM operations"))
                        .put("quick_check", query(db, "PRAGMA quick_check")));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }
    }
}
