package dev.notecore.app.probes;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteFullException;
import android.os.Process;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/** Test-only app_process entry. One synthetic database, one writer, no App document access. */
@android.annotation.TargetApi(28)
public final class SqliteCrashProbe {
    private static SQLiteDatabase open(File path, boolean readOnly) {
        SQLiteDatabase.OpenParams.Builder params = new SQLiteDatabase.OpenParams.Builder()
            .setOpenFlags(readOnly ? SQLiteDatabase.OPEN_READONLY : SQLiteDatabase.CREATE_IF_NECESSARY)
            .setErrorHandler(database -> { /* Never delete an unknown/corrupt file. */ });
        if (!readOnly) params.setJournalMode("WAL").setSynchronousMode("FULL");
        return SQLiteDatabase.openDatabase(path, params.build());
    }
    private static long number(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query, null)) { check(cursor.moveToFirst()); return cursor.getLong(0); }
    }
    private static String string(SQLiteDatabase db, String query) {
        try (Cursor cursor = db.rawQuery(query, null)) { check(cursor.moveToFirst()); return cursor.getString(0); }
    }
    private static void check(boolean condition) { if (!condition) throw new AssertionError("Synthetic SQLite invariant failed"); }
    private static void emit(JSONObject result) { System.out.println("NOTECORE_SQLITE " + result); System.out.flush(); }
    private static void crash(String stage) throws Exception {
        emit(new JSONObject().put("crash_point", stage));
        Process.killProcess(Process.myPid());
        throw new AssertionError("SIGKILL returned");
    }
    private static String apply(SQLiteDatabase db, String op, long base, String payload, String crash) throws Exception {
        db.beginTransactionNonExclusive();
        try {
            try (Cursor cursor = db.rawQuery("SELECT payload,revision FROM operations WHERE op_id=?", new String[]{op})) {
                if (cursor.moveToFirst()) {
                    check(payload.equals(cursor.getString(0)) && cursor.getLong(1)==base+1);
                    db.setTransactionSuccessful();
                    return "replayed";
                }
            }
            check(number(db,"SELECT revision FROM root WHERE id=1") == base);
            db.execSQL("INSERT INTO operations(op_id,revision,payload) VALUES(?,?,?)",new Object[]{op,base+1,payload});
            db.execSQL("UPDATE root SET revision=?,payload=? WHERE id=1",new Object[]{base+1,payload});
            if ("uncommitted".equals(crash)) crash(crash);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        if ("committed_no_receipt".equals(crash)) crash(crash);
        return "committed";
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 2);
        File directory = new File(args[0]).getCanonicalFile();
        check(directory.getPath().contains("/cache/sqlite-capability-"));
        check(directory.mkdirs() || directory.isDirectory());
        File file = new File(directory,"probe.db");
        String stage = args[1];
        if ("unknown_schema".equals(stage) || "corrupt_readonly".equals(stage)) {
            File copy = new File(directory, stage+".db");
            Files.copy(file.toPath(),copy.toPath());
            if ("unknown_schema".equals(stage)) {
                try (SQLiteDatabase db = open(copy,false)) { db.setVersion(99); }
            } else {
                byte[] bytes = Files.readAllBytes(copy.toPath()); Arrays.fill(bytes,0,32,(byte)0x7f); Files.write(copy.toPath(),bytes);
            }
            byte[] before = Files.readAllBytes(copy.toPath()); boolean refused = false;
            try (SQLiteDatabase db = open(copy,true)) {
                if (db.getVersion()!=1) refused = true;
                else string(db,"PRAGMA quick_check");
            } catch (SQLiteException failure) { refused = true; }
            check(refused && copy.exists() && Arrays.equals(before,Files.readAllBytes(copy.toPath())));
            emit(new JSONObject().put("stage",stage).put("refused_without_modification",true)); return;
        }
        JSONObject result = new JSONObject().put("stage",stage);
        try (SQLiteDatabase db = open(file,false)) {
            check("wal".equalsIgnoreCase(string(db,"PRAGMA journal_mode")) && number(db,"PRAGMA synchronous")==2);
            if ("init".equals(stage)) {
                db.execSQL("CREATE TABLE root(id INTEGER PRIMARY KEY CHECK(id=1),revision INTEGER NOT NULL,payload TEXT NOT NULL)");
                db.execSQL("CREATE TABLE operations(op_id TEXT PRIMARY KEY,revision INTEGER UNIQUE NOT NULL,payload TEXT NOT NULL)");
                db.execSQL("INSERT INTO root VALUES(1,0,'empty')"); db.setVersion(1);
                apply(db,"op1",0,"A","");
                result.put("sqlite_version",string(db,"SELECT sqlite_version()"));
            } else if ("kill_uncommitted".equals(stage)) apply(db,"op2",1,"B","uncommitted");
            else if ("kill_committed".equals(stage)) apply(db,"op2",1,"B","committed_no_receipt");
            else if ("kill_after_receipt".equals(stage)) {
                apply(db,"op3",2,"C",""); emit(new JSONObject().put("receipt_revision",3)); crash("after_receipt");
            } else if ("replay".equals(stage)) check("replayed".equals(apply(db,"op2",1,"B","")));
            else if ("replay_final".equals(stage)) check("replayed".equals(apply(db,"op3",2,"C","")));
            else if ("conflict".equals(stage) || "stale".equals(stage) || "replay_base_conflict".equals(stage)) {
                boolean refused = false;
                try { apply(db,"stale".equals(stage)?"op3":"op2",0,"replay_base_conflict".equals(stage)?"B":"wrong",""); }
                catch (AssertionError expected) { refused = true; }
                check(refused); result.put("refused",true);
            } else if ("rollback".equals(stage) || "migration_failure".equals(stage)) {
                db.beginTransactionNonExclusive();
                try {
                    db.execSQL("INSERT INTO operations VALUES('op3',3,'C')");
                    db.execSQL("UPDATE root SET revision=3,payload='C' WHERE id=1");
                    if ("migration_failure".equals(stage)) { db.execSQL("ALTER TABLE root ADD COLUMN extra TEXT"); db.setVersion(2); }
                    // No successful marker: actual SQLite transaction rollback, including DDL/version.
                } finally { db.endTransaction(); }
                check(db.getVersion()==1 && number(db,"SELECT count(*) FROM pragma_table_info('root') WHERE name='extra'")==0);
            } else if ("capacity_full".equals(stage)) {
                boolean full = false;
                db.beginTransactionNonExclusive();
                try {
                    long limit = number(db,"PRAGMA page_count")+1;
                    // Query-returning PRAGMA on this transaction's writer connection, not a pooled reader.
                    check(number(db,"PRAGMA max_page_count="+limit)==limit);
                    db.execSQL("INSERT INTO operations VALUES('op3',3,'C')");
                    db.execSQL("UPDATE root SET payload=? WHERE id=1",new Object[]{"X".repeat(512*1024)});
                    db.setTransactionSuccessful();
                } catch (SQLiteFullException expected) { full = true; }
                finally { try { db.endTransaction(); } catch (SQLiteException end) { if (!full) throw end; } }
                check(full); result.put("sqlite_capacity_full",true);
            } else check("verify".equals(stage));
            result.put("revision",number(db,"SELECT revision FROM root WHERE id=1"))
                .put("payload",string(db,"SELECT payload FROM root WHERE id=1"))
                .put("operation_count",number(db,"SELECT count(*) FROM operations"))
                .put("schema",db.getVersion()).put("journal_mode",string(db,"PRAGMA journal_mode"))
                .put("synchronous",number(db,"PRAGMA synchronous"));
        }
        emit(result);
    }
}
