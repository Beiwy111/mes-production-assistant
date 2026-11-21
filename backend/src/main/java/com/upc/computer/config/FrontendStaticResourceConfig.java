package com.upc.computer.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 为小程序与 Web 前端托管本地图片资源（frontend/picture、frontend/public/materials）。
 */
@Configuration
public class FrontendStaticResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        Path projectRoot = resolveProjectRoot();
        Path pictureDir = projectRoot.resolve("frontend").resolve("picture");
        Path materialsDir = projectRoot.resolve("frontend").resolve("public").resolve("materials");

        String pictureLocation = pictureDir.toUri().toString();
        registry.addResourceHandler("/picture/**", "/frontend/picture/**")
                .addResourceLocations(pictureLocation)
                .setCachePeriod(3600);

        String materialsLocation = materialsDir.toUri().toString();
        registry.addResourceHandler("/materials/**")
                .addResourceLocations(materialsLocation)
                .setCachePeriod(3600);
    }

    private Path resolveProjectRoot() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if ("backend".equals(String.valueOf(cwd.getFileName()))) {
            return cwd.getParent();
        }
        return cwd;
    }
}
