package com.upc.computer.config;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/** 压缩微信截图后再发给视觉模型，避免大图导致超时。 */
public final class VisionImageUtil {

    private static final int MAX_EDGE = 1280;
    private static final int MAX_BYTES = 900_000;

    private VisionImageUtil() {
    }

    public static byte[] prepare(byte[] input, String ext) throws IOException {
        if (input == null || input.length == 0) return input;
        if (input.length <= MAX_BYTES) {
            BufferedImage probe = ImageIO.read(new ByteArrayInputStream(input));
            if (probe != null && probe.getWidth() <= MAX_EDGE && probe.getHeight() <= MAX_EDGE) {
                return input;
            }
        }

        BufferedImage source = ImageIO.read(new ByteArrayInputStream(input));
        if (source == null) return input;

        int width = source.getWidth();
        int height = source.getHeight();
        double scale = Math.min(1.0, (double) MAX_EDGE / Math.max(width, height));
        int targetW = Math.max(1, (int) Math.round(width * scale));
        int targetH = Math.max(1, (int) Math.round(height * scale));

        BufferedImage scaled = new BufferedImage(targetW, targetH, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(source, 0, 0, targetW, targetH, null);
        graphics.dispose();

        String format = "png".equalsIgnoreCase(ext) ? "png" : "jpg";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(scaled, format, out);
        return out.toByteArray();
    }
}
