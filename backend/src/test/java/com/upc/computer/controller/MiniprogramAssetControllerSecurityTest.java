package com.upc.computer.controller;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiniprogramAssetControllerSecurityTest {
    @Test
    void rejectsEncodedTraversalOutsidePictureDirectory() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/miniprogram-assets/..%2F..%2FREADME.md");
        assertEquals(404, new MiniprogramAssetController().serve(request).getStatusCode().value());
    }

    @Test
    void detectsGifIndependentlyOfDefaultLocale() {
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            MediaType type = ReflectionTestUtils.invokeMethod(
                    MiniprogramAssetController.class, "probeMediaType", "preview.GIF");
            assertEquals(MediaType.IMAGE_GIF, type);
        } finally {
            Locale.setDefault(previous);
        }
    }
}
