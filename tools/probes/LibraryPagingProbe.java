package dev.notecore.probes;

import android.database.Cursor;
import android.database.sqlite.SQLiteCursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteCursorDriver;
import android.database.sqlite.SQLiteQuery;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.TreeSet;
import org.json.JSONArray;
import org.json.JSONObject;

/** Read-only system engine conformance. No App dependency, document owner or IO worker. */
public final class LibraryPagingProbe {
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
    private static byte[] hex(String value) {
        byte[] result = new byte[value.length()/2];
        for (int i=0;i<result.length;i++) result[i]=(byte)Integer.parseInt(value.substring(i*2,i*2+2),16);
        return result;
    }
    private static String hex(byte[] value) {
        StringBuilder result = new StringBuilder();
        for (byte b:value) result.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return result.toString();
    }
    public static void main(String[] args) {
        try { verify(args); }
        catch (Throwable error) { error.printStackTrace(System.err); System.exit(1); }
    }
    private static void verify(String[] args) throws Exception {
        check(args.length==1,"one fixture directory required");
        File folder = new File(args[0]).getCanonicalFile();
        check(folder.getPath().contains("/cache/library-paging-"),"isolated cache only");
        JSONObject input = new JSONObject(new String(Files.readAllBytes(new File(folder,"cases.json").toPath()),StandardCharsets.UTF_8));
        check(input.getString("schema").equals("library-sql-replay-1"),"fixture marker");
        JSONArray cases=input.getJSONArray("cases"); TreeSet<String> plans=new TreeSet<>();
        int queries=0; long queryRows=0;
        try (SQLiteDatabase db=SQLiteDatabase.openDatabase(new File(folder,"fixture.sqlite").getPath(),null,SQLiteDatabase.OPEN_READONLY)) {
            for (int ordinal=0;ordinal<cases.length();ordinal++) {
                JSONObject item=cases.getJSONObject(ordinal); JSONArray parameters=item.getJSONArray("parameters");
                String sql=item.getString("sql");
                check(sql.startsWith("SELECT")||sql.startsWith("EXPLAIN QUERY PLAN"),"only reads");
                check(!sql.contains("document_body")&&!sql.contains("image_resource"),"metadata only");
                MessageDigest digest=MessageDigest.getInstance("SHA-256"); int rows=0;
                try (Cursor c=db.rawQueryWithFactory(new SQLiteDatabase.CursorFactory() {
                  @Override public Cursor newCursor(SQLiteDatabase database, SQLiteCursorDriver driver, String editTable, SQLiteQuery query) {
                    try {
                        for (int i=0;i<parameters.length();i++) {
                            JSONObject p=parameters.getJSONObject(i);
                            switch(p.getString("type")) {
                                case "blob": query.bindBlob(i+1,hex(p.getString("hex"))); break;
                                case "integer": query.bindLong(i+1,p.getLong("value")); break;
                                case "text": query.bindString(i+1,p.getString("value")); break;
                                default: throw new AssertionError("binding type");
                            }
                        }
                    } catch (org.json.JSONException e) { throw new AssertionError(e); }
                    return new SQLiteCursor(driver,editTable,query);
                  }
                },sql,new String[0],null)) {
                    while(c.moveToNext()) {
                        rows++;
                        if(item.getString("kind").equals("plan")) {
                            String plan=c.getString(3);
                            check(plan.contains("USING COVERING INDEX page_")&&!plan.contains("TEMP B-TREE"),"unadopted plan "+plan);
                            plans.add(plan); continue;
                        }
                        for(int col=0;col<c.getColumnCount();col++) {
                            String tag; byte[] value;
                            switch(c.getType(col)) {
                                case Cursor.FIELD_TYPE_NULL: tag="N"; value=new byte[0]; break;
                                case Cursor.FIELD_TYPE_INTEGER: tag="I"; value=Long.toString(c.getLong(col)).getBytes(StandardCharsets.UTF_8); break;
                                case Cursor.FIELD_TYPE_STRING: tag="T"; value=c.getString(col).getBytes(StandardCharsets.UTF_8); break;
                                case Cursor.FIELD_TYPE_BLOB: tag="B"; value=c.getBlob(col); break;
                                default: throw new AssertionError("unexpected SQLite cell type");
                            }
                            digest.update((tag+value.length+":").getBytes(StandardCharsets.UTF_8)); digest.update(value); digest.update((byte)'\n');
                        }
                    }
                }
                check(rows==item.getInt("rows"),"row count at "+ordinal);
                if(item.getString("kind").equals("query")) {
                    check(hex(digest.digest()).equals(item.getString("sha256")),"typed rows at "+ordinal);
                    queries++; queryRows+=rows;
                }
            }
            String version;
            try(Cursor c=db.rawQuery("SELECT sqlite_version()",null)) {check(c.moveToFirst(),"version");version=c.getString(0);}
            System.out.println("NOTECORE_PAGING "+new JSONObject().put("status","passed").put("sqlite_version",version)
                .put("cases",cases.length()).put("queries",queries).put("query_rows",queryRows)
                .put("plans",new JSONArray(plans)).put("pid",android.os.Process.myPid()).put("read_only",db.isReadOnly()));
        }
    }
}
