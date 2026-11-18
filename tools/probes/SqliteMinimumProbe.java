import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteDatabaseLockedException;
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

    private static JSONObject rebuildWriter(SQLiteDatabase first, File path, int flags) throws Exception {
        String readerRevision;
        try (SQLiteDatabase contender = SQLiteDatabase.openDatabase(path.getPath(), null, flags, ignored -> {})) {
            contender.execSQL("PRAGMA synchronous=FULL");
            first.beginTransactionNonExclusive();
            try {
                verifyWriter(first);
                first.execSQL("UPDATE document SET revision=9,payload='uncommitted' WHERE id=1");
                readerRevision = query(contender, "SELECT revision FROM document WHERE id=1");
                require("1".equals(readerRevision), "second pool observed uncommitted revision");
                try {
                    apply(contender, "new-checkpoint", "reopened-writer");
                    throw new AssertionError("second pool acquired an occupied writer");
                } catch (SQLiteDatabaseLockedException expected) {
                    require("0".equals(query(contender, "SELECT count(*) FROM operations")),
                            "blocked writer left an operation");
                }
            } finally {
                first.endTransaction();
            }
        }
        first.close();

        try (SQLiteDatabase replacement = SQLiteDatabase.openDatabase(path.getPath(), null, flags, ignored -> {})) {
            String reopenedMode;
            replacement.beginTransactionNonExclusive();
            try {
                reopenedMode = query(replacement, "PRAGMA synchronous");
            } finally {
                replacement.endTransaction();
            }

            replacement.execSQL("PRAGMA synchronous=NORMAL");
            try {
                apply(replacement, "new-checkpoint", "reopened-writer");
                throw new AssertionError("writer accepted NORMAL durability");
            } catch (AssertionError error) {
                require("writer must use FULL".equals(error.getMessage()), error.getMessage());
            }
            require("last-confirmed".equals(query(replacement, "SELECT payload FROM document WHERE id=1")),
                    "writer refusal changed last confirmed content");
            require("0".equals(query(replacement, "SELECT count(*) FROM operations")), "writer refusal wrote an operation");

            replacement.execSQL("PRAGMA synchronous=FULL");
            require("COMMITTED".equals(apply(replacement, "new-checkpoint", "reopened-writer")), "reopened writer commit");
            replacement.beginTransactionNonExclusive();
            try {
                verifyWriter(replacement);
                return new JSONObject().put("reader_revision_while_writer_held_lock", readerRevision)
                        .put("contender_refused_busy", true)
                        .put("reopened_default_synchronous", reopenedMode)
                        .put("normal_replacement_refused", true)
                        .put("writer_synchronous", query(replacement, "PRAGMA synchronous"))
                        .put("journal_mode", query(replacement, "PRAGMA journal_mode"))
                        .put("revision", query(replacement, "SELECT revision FROM document WHERE id=1"))
                        .put("payload", query(replacement, "SELECT payload FROM document WHERE id=1"))
                        .put("operation_count", query(replacement, "SELECT count(*) FROM operations"))
                        .put("quick_check", query(replacement, "PRAGMA quick_check"))
                        .put("sqlite", query(replacement, "SELECT sqlite_version()"));
            } finally {
                replacement.endTransaction();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "expected isolated directory and stage");
        require(Build.VERSION.SDK_INT == 26 || Build.VERSION.SDK_INT == 27, "expected minimum OS");
        File directory = new File(args[0]).getCanonicalFile();
        require(directory.getPath().matches("/data/local/tmp/notecore-sqlite-minimum-[a-f0-9]{32}"),
                "refusing non-fixture directory");
        require(directory.isDirectory(), "fixture directory missing");
        String stage = args[1];
        require(stage.matches("init|verify|before-commit|after-commit|replay|conflict|reopened-writer"), "invalid stage");
        File path = new File(directory, "probe.sqlite");
        int flags = SQLiteDatabase.ENABLE_WRITE_AHEAD_LOGGING;
        if ("init".equals(stage)) flags |= SQLiteDatabase.CREATE_IF_NECESSARY;
        if ("reopened-writer".equals(stage)) {
            SQLiteDatabase first = SQLiteDatabase.openDatabase(path.getPath(), null, flags, ignored -> {});
            try {
                first.execSQL("PRAGMA synchronous=FULL");
                emit(rebuildWriter(first, path, flags).put("stage", stage).put("status", "COMMITTED").put("pid", Process.myPid()));
            } finally {
                if (first.isOpen()) first.close();
            }
            return;
        }
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
