package com.netralab.pdfTagging.utils;

import com.itextpdf.io.font.PdfEncodings;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;

@Slf4j
public class FontLoaderUtils {
    private static final String FONT_RESOURCE_PATH = "fonts/LiberationSans-Regular.ttf";

    public static PdfFont loadLiberationSansFont() throws IOException {
        log.info("Loading font from resource: {}", FONT_RESOURCE_PATH);

        try (InputStream fontStream = FontLoaderUtils.class.getClassLoader().getResourceAsStream(FONT_RESOURCE_PATH)) {
            if (fontStream == null) {
                log.info("Font resource not found at {}", FONT_RESOURCE_PATH);
                throw new IOException("Font resource not found at " + FONT_RESOURCE_PATH + ". Ensure font is included in the deployment package.");
            }

            byte[] fontBytes = fontStream.readAllBytes();
            log.info("Font resource loaded successfully, size: {} bytes", fontBytes.length);

            PdfFont pdfFont = PdfFontFactory.createFont(fontBytes, PdfEncodings.IDENTITY_H, PdfFontFactory.EmbeddingStrategy.FORCE_EMBEDDED);
            log.info("PdfFont created: {}", pdfFont.getFontProgram().getFontNames().getFontName());

            return pdfFont;
        }
    }
}
