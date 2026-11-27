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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/** Isolated native writer and reader for a fixed, synthetic export selection. */
public final class AndroidPdfCompositeProbe {
    private static String sha(Path path) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
        StringBuilder result = new StringBuilder();
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

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

    private static Bitmap composite(PdfRenderer source, JSONObject task, int scale) throws Exception {
        int width = task.getInt("page_width");
        int height = task.getInt("page_height");
        Bitmap bitmap = Bitmap.createBitmap(task.getInt("pixel_width"), task.getInt("pixel_height"), Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(Color.WHITE);
            if (!task.isNull("source_index")) {
                try (PdfRenderer.Page page = source.openPage(task.getInt("source_index"))) {
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
        } catch (Exception | Error failure) {
            bitmap.recycle();
            throw failure;
        }
    }

    private static long export(PdfRenderer source, Path destination, JSONObject quality, boolean cancelled) throws Exception {
        require(!Files.exists(destination), "Destination must be new within the isolated output directory");
        Path stage = Files.createTempFile(destination.getParent(), "composite-", ".stage");
        PdfDocument output = new PdfDocument();
        List<Bitmap> retained = new ArrayList<>();
        long allocation = 0;
        try {
            JSONArray tasks = quality.getJSONArray("tasks");
            for (int i = 0; i < tasks.length(); i++) {
                JSONObject task = tasks.getJSONObject(i);
                int width = task.getInt("page_width"), height = task.getInt("page_height");
                int outputNumber = task.getInt("output_number");
                require(outputNumber == i + 1, "Export task ordering changed");
                Bitmap image = composite(source, task, quality.getInt("scale"));
                retained.add(image);
                allocation += image.getAllocationByteCount();
                require(image.getAllocationByteCount() == task.getLong("argb_bytes"), "Unexpected raster allocation");
                require(allocation <= quality.getLong("retained_argb_bytes"), "Retained allocation exceeds the plan");
                PdfDocument.Page page = output.startPage(new PdfDocument.PageInfo.Builder(width, height, outputNumber).create());
                page.getCanvas().drawBitmap(image, null, new Rect(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
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

    private static JSONArray reopen(Path pdf, int expectedPages) throws Exception {
        JSONArray pages = new JSONArray();
        try (PdfRenderer renderer = openReadOnly(pdf)) {
            require(renderer.getPageCount() == expectedPages, "Export page count changed");
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
        Path source = folder.resolve("source.pdf");
        Path planFile = folder.resolve("composite-plan.json");
        JSONObject plan = new JSONObject(new String(Files.readAllBytes(planFile), StandardCharsets.UTF_8));
        require(sha(source).equals(plan.getString("source_sha256")), "Source PDF hash differs from the plan");
        JSONArray qualities = plan.getJSONArray("qualities");
        require(qualities.length() == 2, "Expected two planned qualities");
        JSONObject result = new JSONObject();
        try (PdfRenderer renderer = openReadOnly(source)) {
            require(renderer.getPageCount() == plan.getInt("source_page_count"), "Source PDF page count differs from the plan");
            for (int index = 0; index < qualities.length(); index++) {
                JSONObject quality = qualities.getJSONObject(index);
                int scale = quality.getInt("scale");
                JSONArray tasks = quality.getJSONArray("tasks");
                JSONArray pageIds = new JSONArray();
                for (int i = 0; i < tasks.length(); i++) pageIds.put(tasks.getJSONObject(i).getString("page_id"));
                Path output = folder.resolve("quality-" + scale + ".pdf");
                long allocation = export(renderer, output, quality, false);
                result.put("quality_" + scale, new JSONObject().put("pages", reopen(output, tasks.length()))
                    .put("page_ids", pageIds)
                    .put("retained_bitmap_bytes", allocation));
            }
            export(renderer, cancelled, qualities.getJSONObject(0), true);
        }
        try (java.util.stream.Stream<Path> files = Files.list(folder)) {
            require(files.noneMatch(path -> path.getFileName().toString().endsWith(".stage")), "Stage file leaked");
        }
        require(!Files.exists(cancelled), "Cancelled export became visible");
        System.out.println(result.put("cancelled_before_publish", true).put("plan_sha256", sha(planFile)));
    }
}
