package com.upc.computer.face;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;

@Component
public class FaceImageQuality {
    public static final int MAX_BYTES = 3 * 1024 * 1024;
    public static final int MAX_JSON_BYTES = 4 * 1024 * 1024 + 16384;
    private final FaceAuthProperties properties;
    public FaceImageQuality(FaceAuthProperties properties) { this.properties = properties; }
    public record Capture(BufferedImage image, byte[] jpeg) { }
    public Capture decode(String input) {
        if (input == null || input.isBlank() || input.length() > MAX_BYTES * 4 / 3 + 64) throw bad("图像为空或超过 3MB");
        String value = input;
        if (value.startsWith("data:")) {
            if (!(value.startsWith("data:image/jpeg;base64,") || value.startsWith("data:image/png;base64,"))) throw bad("仅支持 JPEG 或 PNG 图像");
            value = value.substring(value.indexOf(',') + 1);
        }
        try {
            byte[] bytes = Base64.getDecoder().decode(value);
            if (bytes.length > MAX_BYTES) throw bad("图像超过 3MB");
            try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                var readers = ImageIO.getImageReaders(stream);
                if (!readers.hasNext()) throw bad("图像格式无效");
                ImageReader reader = readers.next();
                try {
                    String format = reader.getFormatName();
                    if (!("JPEG".equalsIgnoreCase(format) || "PNG".equalsIgnoreCase(format))) throw bad("仅支持 JPEG 或 PNG 图像");
                    reader.setInput(stream, true, true);
                    int w = reader.getWidth(0), h = reader.getHeight(0);
                    if (w < 240 || h < 240 || w > 2048 || h > 2048 || (long) w * h > 4_000_000) throw bad("请使用 240 至 2048 像素、总像素不超过 400 万的摄像头图像");
                    BufferedImage decoded = reader.read(0);
                    BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                    var graphics = rgb.createGraphics();
                    graphics.drawImage(decoded, 0, 0, null);
                    graphics.dispose();
                    var output = new ByteArrayOutputStream();
                    // Memory output stream avoids ImageIO's default disk cache for biometric data.
                    try (var memory = new javax.imageio.stream.MemoryCacheImageOutputStream(output)) {
                        ImageIO.write(rgb, "jpeg", memory);
                    }
                    return new Capture(rgb, output.toByteArray());
                } finally { reader.dispose(); }
            }
        } catch (FaceException ex) { throw ex; }
        catch (Exception ex) { throw bad("图像无法解析，请重新拍摄"); }
    }
    public JsonNode singleFace(JsonNode response, Capture capture) {
        JsonNode results = response.path("result");
        if (!results.isArray()) throw CompreFaceClient.invalidResponse();
        if (results.size() != 1) throw bad(results.isEmpty() ? "未检测到人脸，请正视镜头" : "画面中只能有一张人脸");
        JsonNode face = results.get(0), box = face.path("box");
        double probability = box.path("probability").asDouble(-1);
        int left = box.path("x_min").asInt(-1), top = box.path("y_min").asInt(-1);
        int right = box.path("x_max").asInt(-1), bottom = box.path("y_max").asInt(-1);
        BufferedImage image = capture.image();
        if (!Double.isFinite(probability) || probability < properties.getDetectionThreshold()
                || left < 0 || top < 0 || right > image.getWidth() || bottom > image.getHeight()
                || right - left < properties.getMinFacePixels() || bottom - top < properties.getMinFacePixels()) {
            throw bad("人脸过小或不清晰，请靠近镜头并正视拍摄");
        }
        double brightness = 0, sum = 0, squares = 0;
        int n = 0;
        for (int y = top + 1; y < bottom - 1; y += 2) {
            for (int x = left + 1; x < right - 1; x += 2) {
                double center = gray(image.getRGB(x, y));
                double lap = gray(image.getRGB(x - 1, y)) + gray(image.getRGB(x + 1, y))
                        + gray(image.getRGB(x, y - 1)) + gray(image.getRGB(x, y + 1)) - 4 * center;
                brightness += center; sum += lap; squares += lap * lap; n++;
            }
        }
        double mean = brightness / Math.max(n, 1);
        if (mean < properties.getMinBrightness() || mean > properties.getMaxBrightness()) throw bad("人脸区域过暗或过亮，请调整光线");
        double variance = squares / Math.max(n, 1) - Math.pow(sum / Math.max(n, 1), 2);
        if (variance < properties.getMinSharpness()) throw bad("人脸图像较模糊，请保持稳定后重新拍摄");
        return face;
    }
    private double gray(int rgb) { return 0.299 * ((rgb >> 16) & 255) + 0.587 * ((rgb >> 8) & 255) + 0.114 * (rgb & 255); }
    static FaceException bad(String message) { return new FaceException(422, "IMAGE_QUALITY", message); }
}
