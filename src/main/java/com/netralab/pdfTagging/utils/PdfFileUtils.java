package com.netralab.pdfTagging.utils;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.Base64;

@Slf4j
public class PdfFileUtils {

    public static String getPdfExtension(String fileName) {
        log.info("Getting PDF extension for fileName: {}", fileName);
        if (fileName == null) {
            log.info("file_name cannot be null");
            throw new IllegalArgumentException("file_name cannot be null");
        }
        if (fileName.toLowerCase().endsWith(".pdf")) {
            String extension = fileName.substring(fileName.length() - 4);
            log.info("Found PDF extension: {}", extension);
            return extension;
        }
        log.info("No valid PDF extension found, defaulting to: .pdf");
        return ".pdf";
    }

    public static String ensurePdfExtension(String fileName, String pdfExtension) {
        log.info("Ensuring PDF extension for fileName: {}, pdfExtension: {}", fileName, pdfExtension);
        if (fileName == null) {
            log.info("file_name cannot be null");
            throw new IllegalArgumentException("file_name cannot be null");
        }
        if (fileName.toLowerCase().endsWith(".pdf")) {
            String newFileName = fileName.substring(0, fileName.length() - 4) + pdfExtension;
            log.info("Updated fileName with extension: {}", newFileName);
            return newFileName;
        }
        String newFileName = fileName + pdfExtension;
        log.info("Appended extension to fileName: {}", newFileName);
        return newFileName;
    }

    public static String getFileBaseName(String fileName, String pdfExtension) {
        log.info("Getting base name for fileName: {}, pdfExtension: {}", fileName, pdfExtension);
        if (fileName == null || pdfExtension == null) {
            log.info("fileName or pdfExtension cannot be null");
            throw new IllegalArgumentException("fileName or pdfExtension cannot be null");
        }
        if (fileName.endsWith(pdfExtension)) {
            String baseName = fileName.substring(0, fileName.length() - pdfExtension.length());
            log.info("Extracted base name: {}", baseName);
            return baseName;
        }
        log.info("No extension to remove, returning fileName as base name: {}", fileName);
        return fileName;
    }

    public static String convertPdfPageToBase64(PDDocument inputPdf, int pageNumber, String outputFilePath) {
        log.info("Starting PDF page conversion to Base64 for page {} at output path: {}", pageNumber, outputFilePath);
        long startTime = System.currentTimeMillis();

        if (inputPdf == null) {
            log.info("Input PDF document is null");
            throw new IllegalArgumentException("Input PDF document cannot be null");
        }

        if (outputFilePath == null || outputFilePath.trim().isEmpty()) {
            log.info("Output file path is null or empty");
            throw new IllegalArgumentException("Output file path cannot be null or empty");
        }

        if (pageNumber < 1 || pageNumber > inputPdf.getNumberOfPages()) {
            log.info("Invalid page number: {}. Page number must be between 1 and {}", pageNumber, inputPdf.getNumberOfPages());
            throw new IllegalArgumentException("Invalid page number.");
        }

        try {
            log.info("Creating PDF renderer for document");
            PDFRenderer pdfRenderer = new PDFRenderer(inputPdf);

            // Convert the page number to 0-based index for PDFBox
            int pageIndex = pageNumber - 1;

            log.info("Rendering page {} to image with 300 DPI", pageNumber);
            BufferedImage bufferedImage = pdfRenderer.renderImageWithDPI(pageIndex, 300);

            // Save the image as PNG
            log.info("Writing image to file: {}", outputFilePath);
            File outputFile = new File(outputFilePath);
            ImageIO.write(bufferedImage, "PNG", outputFile);

            // Convert image to Base64
            log.info("Converting image to Base64");
            ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
            ImageIO.write(bufferedImage, "PNG", byteArrayOutputStream);
            byte[] imageBytes = byteArrayOutputStream.toByteArray();
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);

            log.info("Successfully converted page {} to PNG and saved at: {}", pageNumber, outputFilePath);
            log.info("Base64 image length: {}, first 100 characters: {}...",
                    base64Image.length(), base64Image.substring(0, Math.min(100, base64Image.length())));

            long endTime = System.currentTimeMillis();
            log.info("PDF page conversion completed in {} ms", endTime - startTime);

            return base64Image; // Return the Base64 encoded image

        } catch (IOException e) {
            log.info("Failed to convert PDF page {} to Base64 image: {}", pageNumber, e.getMessage(), e);
            throw new RuntimeException("Failed to convert PDF page to Base64 image", e);
        } finally {
            try {
                log.info("Closing input PDF document");
                inputPdf.close();
            } catch (IOException e) {
                log.info("Failed to close input PDF document: {}", e.getMessage(), e);
            }
        }
    }

    public static int extractPageNumber(String jsonData) {
        try {
            if (jsonData == null || jsonData.isEmpty()) {
                log.info("json_data is null or empty");
                throw new IllegalArgumentException("json_data is null or empty");
            }
            JSONParser parser = new JSONParser();
            JSONObject json = (JSONObject) parser.parse(jsonData);
            if (json == null || !json.containsKey("Page") || json.get("Page") == null) {
                log.info("JSON does not contain valid 'Page' key");
                throw new IllegalArgumentException("JSON does not contain valid 'Page' key");
            }
            Object pageObj = json.get("Page");
            if (!(pageObj instanceof Long)) {
                log.info("Page value is not a valid number: {}", pageObj);
                throw new IllegalArgumentException("Page value is not a valid number");
            }
            return ((Long) pageObj).intValue();
        } catch (ParseException e) {
            log.info("Failed to parse JSON: {}", e.getMessage());
            throw new IllegalArgumentException("Failed to parse JSON: " + e.getMessage());
        }
    }

}
