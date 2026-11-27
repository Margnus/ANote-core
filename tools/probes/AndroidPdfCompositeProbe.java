package dev.notecore.probes;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/** Isolated native writer and reader for a fixed, synthetic export selection. */
public final class AndroidPdfCompositeProbe {
    private static final int[][] PAGES = {{3, 180, 300}, {-1, 200, 120}, {0, 300, 180}};

    private static PdfRenderer openReadOnly(Path path) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(path.toFile(), ParcelFileDescriptor.MODE_READ_ONLY);
        try {
            return new PdfRenderer(fd);
        } catch (IOException | RuntimeException failure) {
            try {
                fd.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static Bitmap composite(PdfRenderer source, int[] task, int scale) {
        int width = task[1];
        int height = task[2];
        Bitmap bitmap = Bitmap.createBitmap(width * scale, height * scale, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            if (task[0] >= 0) {
                try (PdfRenderer.Page page = source.openPage(task[0])) {
                    require(page.getWidth() == width && page.getHeight() == height, "Source geometry changed");
                    Matrix transform = new Matrix();
                    transform.setScale(scale, scale);
                    page.render(bitmap, null, transform, PdfRenderer.Page.RENDER_MODE_FOR_PRINT);
                }
            }
            Canvas overlay = new Canvas(bitmap);
            overlay.scale(scale, scale);
            Paint marker = new Paint();
            marker.setColor(Color.CYAN);
            overlay.drawRect(45, 45, 65, 65, marker);
            return bitmap;
        } catch (RuntimeException | Error failure) {
            bitmap.recycle();
            throw failure;
        }
    }

    private static long export(PdfRenderer source, Path destination, int scale, boolean cancelled) throws IOException {
        require(!Files.exists(destination), "Destination must be new within the isolated output directory");
        Path stage = Files.createTempFile(destination.getParent(), "composite-", ".stage");
        PdfDocument output = new PdfDocument();
        List<Bitmap> retained = new ArrayList<>();
        long allocation = 0;
        try {
            for (int i = 0; i < PAGES.length; i++) {
                int[] task = PAGES[i];
                Bitmap image = composite(source, task, scale);
                retained.add(image);
                allocation += image.getAllocationByteCount();
                PdfDocument.Page page = output.startPage(new PdfDocument.PageInfo.Builder(task[1], task[2], i + 1).create());
                page.getCanvas().drawBitmap(image, null, new Rect(0, 0, task[1], task[2]), new Paint(Paint.FILTER_BITMAP_FLAG));
                output.finishPage(page);
            }
            try (FileOutputStream stream = new FileOutputStream(stage.toFile())) {
                output.writeTo(stream);
                stream.getFD().sync();
            }
            require(Files.size(stage) > 0, "Writer produced an empty PDF");
            if (!cancelled) Files.move(stage, destination, StandardCopyOption.ATOMIC_MOVE);
            return allocation;
        } finally {
            try {
                output.close();
            } finally {
                for (Bitmap bitmap : retained) bitmap.recycle();
                Files.deleteIfExists(stage);
            }
        }
    }

    private static JSONArray reopen(Path pdf) throws Exception {
        JSONArray pages = new JSONArray();
        try (PdfRenderer renderer = openReadOnly(pdf)) {
            require(renderer.getPageCount() == PAGES.length, "Export page count changed");
            for (int i = 0; i < renderer.getPageCount(); i++) {
                try (PdfRenderer.Page page = renderer.openPage(i)) {
                    Bitmap bitmap = Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
                    try {
                        bitmap.eraseColor(Color.WHITE);
                        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                        try (FileOutputStream stream = new FileOutputStream(pdf.toString() + "." + i + ".png")) {
                            require(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream), "PNG encoding failed");
                        }
                        pages.put(new JSONArray().put(page.getWidth()).put(page.getHeight()));
                    } finally {
                        bitmap.recycle();
                    }
                }
            }
        }
        return pages;
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 1, "Expected an isolated probe folder");
        File directory = new File(args[0]).getCanonicalFile();
        require(directory.getPath().startsWith("/data/local/tmp/notecore-pdf-export-"), "Invalid probe directory");
        Path folder = directory.toPath();
        Path cancelled = folder.resolve("cancelled.pdf");
        JSONObject result = new JSONObject();
        try (PdfRenderer renderer = openReadOnly(folder.resolve("source.pdf"))) {
            require(renderer.getPageCount() == 4, "Fixture must have four source pages");
            for (int scale : new int[]{1, 2}) {
                Path output = folder.resolve("quality-" + scale + ".pdf");
                long allocation = export(renderer, output, scale, false);
                result.put("quality_" + scale, new JSONObject().put("pages", reopen(output))
                    .put("retained_bitmap_bytes", allocation));
            }
            export(renderer, cancelled, 1, true);
        }
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".stage")), "Stage file leaked");
        }
        require(!Files.exists(cancelled), "Cancelled export became visible");
        System.out.println(result.put("cancelled_before_publish", true));
    }
}
