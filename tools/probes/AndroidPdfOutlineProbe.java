package dev.notecore.probes;

import android.graphics.pdf.PdfRenderer;
import android.os.ParcelFileDescriptor;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;

/** Records the documented page-link API on two PDFs with an identical document outline. */
public final class AndroidPdfOutlineProbe {
    private static PdfRenderer open(File pdf) throws IOException {
        ParcelFileDescriptor fd = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY);
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

    private static JSONArray readPageLinks(File pdf) throws Exception {
        JSONArray pages = new JSONArray();
        try (PdfRenderer renderer = open(pdf)) {
            for (int index = 0; index < renderer.getPageCount(); index++) {
                try (PdfRenderer.Page page = renderer.openPage(index)) {
                    JSONArray destinations = new JSONArray();
                    page.getGotoLinks().forEach(link -> destinations.put(link.getDestination().getPageNumber()));
                    pages.put(destinations);
                }
            }
        }
        return pages;
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected fixture folder");
        File folder = new File(args[0]);
        JSONObject result = new JSONObject()
            .put("outline_only", readPageLinks(new File(folder, "outline-only.pdf")))
            .put("outline_with_page_link", readPageLinks(new File(folder, "outline-with-page-link.pdf")));
        System.out.println(result);
    }
}
