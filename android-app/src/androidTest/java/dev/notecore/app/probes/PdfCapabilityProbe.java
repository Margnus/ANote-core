package dev.notecore.app.probes;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.pdf.LoadParams;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Test-only reader of synthetic cache fixtures. No production PDF or App integration. */
@android.annotation.TargetApi(35)
public final class PdfCapabilityProbe {
    private static void check(boolean value) { if (!value) throw new AssertionError("Synthetic PDF invariant failed"); }
    private static PdfRenderer open(File file, String password) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        try { return password == null ? new PdfRenderer(fd) : new PdfRenderer(fd,new LoadParams.Builder().setPassword(password).build()); }
        catch (Throwable failure) { fd.close(); throw failure; }
    }
    private static Bitmap render(PdfRenderer.Page page, File output) throws IOException {
        Bitmap bitmap = Bitmap.createBitmap(page.getWidth(),page.getHeight(),Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.WHITE);
        page.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        try (FileOutputStream stream = new FileOutputStream(output)) { check(bitmap.compress(Bitmap.CompressFormat.PNG,100,stream)); }
        return bitmap;
    }
    public static void main(String[] args) throws Exception {
        check(args.length == 1);
        File root = new File(args[0]).getCanonicalFile();
        check(root.getPath().contains("/cache/pdf-capability-"));
        JSONArray pages = new JSONArray();
        PdfRenderer renderer = open(new File(root,"crop-rotations.pdf"),null);
        check(renderer.getPageCount()==4);
        for (int i=0;i<4;i++) {
            try (PdfRenderer.Page page = renderer.openPage(i)) {
                Bitmap full = render(page,new File(root,"page-"+i+".png"));
                try {
                    JSONObject value = new JSONObject().put("index",i).put("width",page.getWidth()).put("height",page.getHeight());
                    StringBuilder text = new StringBuilder();
                    page.getTextContents().forEach(content -> text.append(content.getText()));
                    value.put("native_text_contains_fixture",text.toString().contains("NoteCore"));
                    if (i==0) {
                        Bitmap tile = Bitmap.createBitmap(100,80,Bitmap.Config.ARGB_8888);tile.eraseColor(Color.WHITE);
                        try {
                            Matrix matrix = new Matrix();matrix.setTranslate(-50,-40);
                            page.render(tile,new Rect(0,0,100,80),matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            int delta = 0;
                            for (int y=0;y<80;y++) for(int x=0;x<100;x++) {
                                int a = tile.getPixel(x,y), b = full.getPixel(x+50,y+40);
                                delta=Math.max(delta,Math.abs(Color.red(a)-Color.red(b)));
                                delta=Math.max(delta,Math.abs(Color.green(a)-Color.green(b)));
                                delta=Math.max(delta,Math.abs(Color.blue(a)-Color.blue(b)));
                            }
                            value.put("tile_max_channel_delta",delta);
                            try (FileOutputStream stream = new FileOutputStream(new File(root,"tile.png"))) {check(tile.compress(Bitmap.CompressFormat.PNG,100,stream));}
                        } finally { tile.recycle(); }
                        Bitmap guarded = Bitmap.createBitmap(108,88,Bitmap.Config.ARGB_8888);guarded.eraseColor(Color.WHITE);
                        try {
                            Matrix matrix = new Matrix();matrix.setTranslate(-46,-36);
                            page.render(guarded,new Rect(0,0,108,88),matrix,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                            int delta=0;
                            for(int y=0;y<80;y++) for(int x=0;x<100;x++) {
                                int a=guarded.getPixel(x+4,y+4),b=full.getPixel(x+50,y+40);
                                delta=Math.max(delta,Math.abs(Color.red(a)-Color.red(b)));
                                delta=Math.max(delta,Math.abs(Color.green(a)-Color.green(b)));
                                delta=Math.max(delta,Math.abs(Color.blue(a)-Color.blue(b)));
                            }
                            value.put("tile_with_gutter_max_channel_delta",delta).put("tile_gutter",4);
                            try(FileOutputStream stream=new FileOutputStream(new File(root,"tile-gutter.png"))) {check(guarded.compress(Bitmap.CompressFormat.PNG,100,stream));}
                        } finally {guarded.recycle();}
                    }
                    pages.put(value);
                } finally { full.recycle(); }
            }
        }
        boolean invalidPage = false;
        try { renderer.openPage(4); } catch (IllegalArgumentException expected) { invalidPage = true; }
        check(invalidPage);
        PdfRenderer.Page closed = renderer.openPage(0);closed.close();
        boolean closedPage = false;
        try { closed.getTextContents(); }
        catch (IllegalStateException expected) { closedPage = true; }
        check(closedPage);
        renderer.close();
        boolean closedDocument = false;
        try { renderer.openPage(0); } catch (IllegalStateException expected) { closedDocument = true; }
        check(closedDocument);
        boolean noPassword = false, wrongPassword = false, malformed = false;
        try (PdfRenderer ignored = open(new File(root,"password.pdf"),null)) { }
        catch (SecurityException expected) { noPassword = true; }
        try (PdfRenderer ignored = open(new File(root,"password.pdf"),"wrong-fixture-password")) { }
        catch (SecurityException expected) { wrongPassword = true; }
        try (PdfRenderer unlocked = open(new File(root,"password.pdf"),"notecore-fixture")) {
            check(unlocked.getPageCount()==4);
            try (PdfRenderer.Page page = unlocked.openPage(0)) { Bitmap image=render(page,new File(root,"password.png"));image.recycle(); }
        }
        try (PdfRenderer ignored = open(new File(root,"malformed.pdf"),null)) { }
        catch (IOException expected) { malformed = true; }
        check(noPassword && wrongPassword && malformed);
        JSONArray requested = new JSONArray();
        try (PdfRenderer large = open(new File(root,"many-pages.pdf"),null)) {
            check(large.getPageCount()==500);
            for (int index:new int[]{0,499}) try (PdfRenderer.Page page=large.openPage(index)) {
                Bitmap image=render(page,new File(root,"large-"+index+".png"));image.recycle();requested.put(index);
            }
        }
        JSONObject result = new JSONObject().put("pages",pages).put("password_missing_refused",noPassword)
            .put("password_wrong_refused",wrongPassword).put("password_correct_rendered",true)
            .put("malformed_refused",malformed).put("invalid_page_refused",invalidPage)
            .put("closed_page_refused",closedPage).put("closed_document_refused",closedDocument)
            .put("large_page_count",500).put("large_render_requests",requested)
            .put("public_outline_api_verified",false).put("in_progress_render_cancellation_verified",false);
        System.out.println("NOTECORE_PDF "+result);
    }
}
