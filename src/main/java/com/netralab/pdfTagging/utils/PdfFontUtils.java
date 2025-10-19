package com.netralab.pdfTagging.utils;

import com.itextpdf.kernel.font.PdfFont;
import lombok.extern.slf4j.Slf4j;

import java.awt.*;
import java.awt.image.BufferedImage;

@Slf4j
public class PdfFontUtils {

    public static float calculateBestFitFontSize(PdfFont pdfFont, float desiredHeight) {
        if (pdfFont == null) {
            log.info("pdfFont cannot be null");
            throw new IllegalArgumentException("pdfFont cannot be null");
        }
        BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2d = img.createGraphics();
        float fontSize = 12f;
        Font awtFont = new Font(pdfFont.getFontProgram().getFontNames().getFontName(), Font.PLAIN, (int) fontSize);
        g2d.setFont(awtFont);
        FontMetrics metrics = g2d.getFontMetrics();
        float scaleFactor = desiredHeight / metrics.getAscent();
        float bestFitSize = fontSize * scaleFactor;
        g2d.dispose();
        return bestFitSize;
    }

    public static float getStringWidth(String text, PdfFont font, float fontSize) {
        if (text == null || font == null) {
            log.info("Text or font is null, returning 0");
            return 0f;
        }
        float width = font.getWidth(text, fontSize);
        return width;
    }
    
}
