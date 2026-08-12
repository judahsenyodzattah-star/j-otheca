package com.libranet.media.storage;

import com.libranet.common.error.ApiException;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;

/**
 * Content sniffing + variant generation for uploads (spec Phase 7):
 *
 *  - The actual type is detected from MAGIC BYTES; the client's Content-Type/filename
 *    is treated as untrustworthy decoration.
 *  - Only decodable JPEG/PNG/GIF images are accepted (everything else: 400).
 *  - Two variants are produced: FULL (longest side ≤ {@value #MAX_FULL}px, re-encoded)
 *    and THUMB (≤ {@value #MAX_THUMB}px). Re-encoding strips metadata and any payload
 *    smuggled after the real image data.
 */
public final class ImageProcessor {

    public static final int MAX_FULL = 1600;
    public static final int MAX_THUMB = 320;

    private ImageProcessor() {}

    /** Result of sniffing + processing one upload. */
    public record Processed(byte[] full, byte[] thumb, String contentType, String extension,
                            int width, int height) {}

    private enum Sniffed { JPEG, PNG, GIF }

    public static Processed process(byte[] raw, String originalName) {
        if (raw == null || raw.length == 0) {
            throw ApiException.badRequest("EMPTY_FILE", "The uploaded file is empty.");
        }
        Sniffed sniffed = sniff(raw);
        if (sniffed == null) {
            // Sniff-first rejection: an EXE/HTML/polyglot never reaches the decoder.
            throw ApiException.badRequest("UNSUPPORTED_MEDIA_TYPE",
                    "Only JPEG, PNG or GIF images are accepted (detected from file content, not the file name).");
        }
        BufferedImage image;
        try {
            image = ImageIO.read(new ByteArrayInputStream(raw));
        } catch (Exception e) {
            image = null;
        }
        if (image == null) {
            throw ApiException.badRequest("IMAGE_UNREADABLE", "The file looks like an image but could not be decoded.");
        }
        boolean alpha = sniffed != Sniffed.JPEG;   // keep transparency for PNG/GIF
        BufferedImage full = resizeToFit(image, MAX_FULL, alpha);
        BufferedImage thumb = resizeToFit(image, MAX_THUMB, alpha);
        String format = alpha ? "png" : "jpeg";
        return new Processed(encode(full, format), encode(thumb, format),
                alpha ? "image/png" : "image/jpeg", alpha ? "png" : "jpg",
                full.getWidth(), full.getHeight());
    }

    /** Magic-byte sniffing. Anything unknown (or merely extension-renamed) returns null. */
    static Sniffed sniff(byte[] b) {
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) {
            return Sniffed.JPEG;
        }
        if (b.length >= 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G'
                && (b[4] & 0xFF) == 0x0D && (b[5] & 0xFF) == 0x0A && (b[6] & 0xFF) == 0x1A && (b[7] & 0xFF) == 0x0A) {
            return Sniffed.PNG;
        }
        if (b.length >= 6 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F' && b[3] == '8'
                && (b[4] == '7' || b[4] == '9') && b[5] == 'a') {
            return Sniffed.GIF;
        }
        return null;
    }

    private static BufferedImage resizeToFit(BufferedImage src, int maxEdge, boolean alpha) {
        int w = src.getWidth();
        int h = src.getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(w, h));
        int tw = Math.max(1, (int) Math.round(w * scale));
        int th = Math.max(1, (int) Math.round(h * scale));
        int type = alpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
        BufferedImage dst = new BufferedImage(tw, th, type);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, tw, th, null);
        g.dispose();
        return dst;
    }

    private static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, format, out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode image variant", e);
        }
    }
}
