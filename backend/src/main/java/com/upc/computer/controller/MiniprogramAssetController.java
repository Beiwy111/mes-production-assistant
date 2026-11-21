package com.upc.computer.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import java.util.Locale;

/**
 * 兼容小程序历史资源路径，将 /miniprogram-assets/** 映射到 frontend/picture。
 */
@RestController
public class MiniprogramAssetController {

    private static final String ASSET_PREFIX = "/miniprogram-assets/";

    private static final Map<String, String> ALIASES = Map.of(
            "customer/order-showcase-02.webp", "OIP (1).webp",
            "customer/order-showcase-03.webp", "OIP.webp"
    );

    @GetMapping("/miniprogram-assets/**")
    public ResponseEntity<Resource> serve(HttpServletRequest request) {
        String uri = request.getRequestURI();
        int index = uri.indexOf(ASSET_PREFIX);
        if (index < 0) {
            return ResponseEntity.notFound().build();
        }

        String relative = URLDecoder.decode(uri.substring(index + ASSET_PREFIX.length()), StandardCharsets.UTF_8);
        String pictureName = ALIASES.getOrDefault(relative, stripOperatorPrefix(relative));

        Path pictureDir = resolvePictureDir().toAbsolutePath().normalize();
        Path file = pictureDir.resolve(pictureName).normalize();
        if (!file.startsWith(pictureDir) || !Files.isRegularFile(file)) {
            return ResponseEntity.notFound().build();
        }

        return ResponseEntity.ok()
                .contentType(probeMediaType(pictureName))
                .body(new FileSystemResource(file));
    }

    private static String stripOperatorPrefix(String relative) {
        if (relative.startsWith("operator/")) {
            return relative.substring("operator/".length());
        }
        return relative;
    }

    private static Path resolvePictureDir() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        Path root = "backend".equals(String.valueOf(cwd.getFileName())) ? cwd.getParent() : cwd;
        return root.resolve("frontend").resolve("picture");
    }

    private static MediaType probeMediaType(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".png")) {
            return MediaType.IMAGE_PNG;
        }
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return MediaType.IMAGE_JPEG;
        }
        if (lower.endsWith(".webp")) {
            return MediaType.parseMediaType("image/webp");
        }
        if (lower.endsWith(".gif")) {
            return MediaType.IMAGE_GIF;
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
