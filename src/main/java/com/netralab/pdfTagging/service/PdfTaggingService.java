package com.netralab.pdfTagging.service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.*;
import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.kernel.exceptions.PdfException;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.annot.PdfAnnotation;
import com.itextpdf.kernel.pdf.annot.PdfWidgetAnnotation;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.xobject.PdfFormXObject;
import com.itextpdf.layout.Document;
import com.netralab.pdfTagging.planning.TagPagePlanBuilder;
import com.netralab.pdfTagging.rest.PdfTaggingRequest;
import com.netralab.pdfTagging.utils.FontLoaderUtils;
import com.netralab.pdfTagging.utils.PdfFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.json.JSONException;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
public class PdfTaggingService {

    @Autowired
    private AcroFormFieldExtractor acroFormFieldExtractor;

    @Autowired
    AmazonS3 s3Client;

    public Map<String, Object> handleRequest(PdfTaggingRequest request) {
        log.info("START: Handling PDF tagging request.");

        Map<String, Object> res = new HashMap<>();

        try {
            // Validate request
            if (request == null) {
                log.error("PdfTaggingRequest is null.");
                throw new IllegalArgumentException("Request cannot be null.");
            }

            String bucketName = request.getBucketName();
            String requestFileName = request.getFileName();
            String flattenFileName = request.getFlattenFileName();
            String folderName = request.getFolderName();
            String flattenFolderName = request.getFlattenFolderName(); // Make sure getter exists!

            if (StringUtils.isEmpty(bucketName) || StringUtils.isEmpty(requestFileName) || StringUtils.isEmpty(flattenFileName)) {
                log.error("Missing required request parameters: bucketName={}, fileName={}, flattenFileName={}", bucketName, requestFileName, flattenFileName);
                throw new IllegalArgumentException("Missing required parameters: bucketName, fileName, or flattenFileName.");
            }

            String inputFolderName = "01_input_files";
            String outputFolderName = "04_tagged_files";

            // Ensure extensions and base names for original file
            String pdfExtension = PdfFileUtils.getPdfExtension(requestFileName);
            final String fileName = PdfFileUtils.ensurePdfExtension(requestFileName, pdfExtension);
            String fileBaseName = PdfFileUtils.getFileBaseName(fileName, pdfExtension);

            JSONArray jsonArray = readJsonArrayFromS3(bucketName, inputFolderName, folderName, fileBaseName);
            if (jsonArray == null || jsonArray.isEmpty()) {
                res.put("error", true);
                res.put("message", "JSON data is empty.");
                return res;
            }
            log.info("Parsed JSON with {} page entries.", jsonArray.size());

            String normalLocalFilePath = prepareAndDownloadPdfFromS3(bucketName, fileName, folderName, inputFolderName);
            String flattenLocalFilePath = prepareAndDownloadPdfFromS3(bucketName, flattenFileName, flattenFolderName, inputFolderName);

            ExecutorService executorService = Executors.newFixedThreadPool(2);

            // Process ORIGINAL PDF file
            CompletableFuture<String> normalFileFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return processPdfFile(normalLocalFilePath, bucketName, fileName, folderName, inputFolderName, outputFolderName, jsonArray, true);

                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executorService).whenComplete((ok, ex) -> safeDelete(normalLocalFilePath));

            // Process FLATTENED PDF file
            CompletableFuture<String> flattenFileFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return processPdfFile(flattenLocalFilePath, bucketName, flattenFileName, flattenFolderName, inputFolderName, outputFolderName, jsonArray, false);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, executorService).whenComplete((ok, ex) -> {
                safeDelete(flattenLocalFilePath);
            });
            CompletableFuture.allOf(normalFileFuture, flattenFileFuture).join();
            String outputFileLocation = normalFileFuture.get();
            String flattenOutputFileLocation = flattenFileFuture.get();
            executorService.shutdown();
            res.put("Download tagged File Path", outputFileLocation);
            res.put("Download flatten tagged File Path", flattenOutputFileLocation);
        } catch (JSONException e) {
            log.error("JSONException during PDF processing: {}", e.getMessage(), e);
            res.put("error", true);
            res.put("message", "JSON Exception: " + e.getMessage());

        } catch (NullPointerException e) {
            log.error("NullPointerException during processing: {}", e.getMessage(), e);
            res.put("error", true);
            res.put("message", "Null Exception: " + e.getMessage());

        } catch (Exception e) {
            log.error("Unexpected error during PDF processing: {}", e.getMessage(), e);
            res.put("error", true);
            res.put("message", "Error: " + e.getMessage());
        }

        log.info("END: Lambda request completed with result: {}", res);
        return res;
    }

    private String processPdfFile(String localInputPath, String bucketName, String fileName, String folderName, String inputFolderName, String outputFolderName, JSONArray jsonArray, Boolean isNormal) throws Exception {
        // Load font
        log.info("Loading font from resources...");
        PdfFont arialMono = FontLoaderUtils.loadLiberationSansFont();
        log.info("Font loaded successfully: {}", arialMono.getFontProgram().getFontNames().getFontName());

        // Ensure extensions and base name
        String pdfExtension = PdfFileUtils.getPdfExtension(fileName);
        fileName = PdfFileUtils.ensurePdfExtension(fileName, pdfExtension);
        String fileBaseName = PdfFileUtils.getFileBaseName(fileName, pdfExtension);

        // Process each page entry in JSON with this PDF file
        log.info("Processing {} page entries in PDF file: {}", jsonArray.size(), fileName);
        List<Map<String, Object>> fieldList = new ArrayList<>();

        if (isNormal) {
            ExecutorService executor = Executors.newFixedThreadPool(20);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            try (PDDocument PDdocument = PDDocument.load(new File(localInputPath));
                 PdfDocument PDFdocument = new PdfDocument(new PdfReader(localInputPath))
            ) {
                ConcurrentHashMap<Integer, JSONObject> resultMap = new ConcurrentHashMap<>();

                for (int i = 0; i < jsonArray.size(); i++) {
                    final int index = i;
                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            String currentPage = jsonArray.get(index).toString();
                            int pageNumber = PdfFileUtils.extractPageNumber(currentPage);
                            log.debug("Processing page: {}", pageNumber);

                            boolean hasFormFields = acroFormFieldExtractor.isAcroFormsAvailable(PDFdocument, pageNumber, fieldList);
                            if (hasFormFields) {
                                String updatedJson = acroFormFieldExtractor.setAcroFieldMappingInJson(
                                        PDdocument, currentPage, inputFolderName, pageNumber, fieldList
                                );
                                if (!updatedJson.trim().isEmpty()) {
                                    JSONObject updatedObject = (JSONObject) new JSONParser().parse(updatedJson);
                                    resultMap.put(index, updatedObject);
                                }
                            } else {
                                log.debug("No form fields found on page {}", pageNumber);
                            }
                        } catch (Exception e) {
                            log.error("Error processing page index {}: {}", index, e.getMessage(), e);
                        }
                    }, executor));
                }

                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
                executor.shutdown();

                for (Map.Entry<Integer, JSONObject> entry : resultMap.entrySet()) {
                    jsonArray.set(entry.getKey(), entry.getValue());
                }
            } finally {
                executor.shutdown();
            }
        }


        // Create output key and upload tagged PDF
        String outputTaggedFile = fileBaseName + "_AOD" + pdfExtension;
        String outputFileKey = (folderName == null || folderName.isEmpty()) ?
                outputFolderName + "/" + outputTaggedFile :
                outputFolderName + "/" + folderName + "/" + outputTaggedFile;

        log.info("Tagged PDF uploaded successfully: {}", outputFileKey);
//        return createTaggedPDFAndUpload(localInputPath, jsonArray, bucketName, outputFileKey, pdfExtension, fileBaseName, arialMono);
        return createTaggedPdfDirectToS3(localInputPath, jsonArray, bucketName, outputFileKey, fileBaseName, arialMono);
    }

    public String createTaggedPdfDirectToS3(
            String inputPdfPath,
            JSONArray jsonArray,
            String bucketName,
            String s3OutputKey,
            String baseName,
            PdfFont arialMono
    ) {
        log.info("START: Creating tagged PDF and streaming directly to S3. input={}, s3://{}/{}",
                inputPdfPath, bucketName, s3OutputKey);

        if (!StringUtils.hasText(inputPdfPath) || jsonArray == null || jsonArray.isEmpty()
                || !StringUtils.hasText(bucketName) || !StringUtils.hasText(s3OutputKey)
                || !StringUtils.hasText(baseName) || arialMono == null) {
            throw new IllegalArgumentException("Invalid args for createTaggedPdfDirectToS3");
        }

        // S3 multipart init
        final String uploadId;
        final List<PartETag> parts = new CopyOnWriteArrayList<>();
        final int PART_SIZE = 8 * 1024 * 1024; // 8MB (>= 5MB except last part)
        InitiateMultipartUploadResult init;
        try {
            var initReq = new InitiateMultipartUploadRequest(bucketName, s3OutputKey);
            init = s3Client.initiateMultipartUpload(initReq);
            uploadId = init.getUploadId();
            log.info("Initiated multipart upload. uploadId={}", uploadId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to initiate multipart upload", e);
        }

        // Pipe: iText producer writes to pos; consumer reads from pis and uploads parts
        try (var pos = new PipedOutputStream();
             var pis = new PipedInputStream(pos, 1 << 20)
        ) {
            // Consumer: read from 'pis' and upload parts as they arrive
            CompletableFuture<Void> uploader = CompletableFuture.runAsync(() -> {
                byte[] buf = new byte[PART_SIZE];
                int partNum = 1;
                try (InputStream in = pis) {
                    while (true) {
                        int filled = fillBuffer(in, buf);
                        if (filled <= 0) break;
                        var upr = new UploadPartRequest()
                                .withBucketName(bucketName)
                                .withKey(s3OutputKey)
                                .withUploadId(uploadId)
                                .withPartNumber(partNum++)
                                .withPartSize(filled)
                                .withInputStream(new ByteArrayInputStream(buf, 0, filled));
                        var upRes = s3Client.uploadPart(upr);
                        parts.add(upRes.getPartETag());
                        log.debug("Uploaded part #{} ({} bytes)", partNum - 1, filled);
                    }
                } catch (Throwable t) {
                    throw new CompletionException(t);
                }
            });

            try (var inputPdf = new PdfDocument(new PdfReader(inputPdfPath));
                 var pdf = new PdfDocument(new PdfWriter(pos))) {

                // 1) Copy pages (mirrors your current logic)
                for (int pageNumber = 1; pageNumber <= inputPdf.getNumberOfPages(); pageNumber++) {
                    var inputPage = inputPdf.getPage(pageNumber);
                    var destPage = pdf.addNewPage(new PageSize(inputPage.getPageSizeWithRotation()));
                    int rotation = inputPage.getRotation();

                    var destCanvas = new PdfCanvas(destPage);
                    var xObj = inputPage.copyAsFormXObject(pdf);
                    if (rotation == 270) {
                        destCanvas.addXObjectWithTransformationMatrix(xObj, 0, 1, -1, 0,
                                inputPage.getPageSizeWithRotation().getWidth(), 0);
                    } else if (rotation == 90) {
                        destCanvas.addXObjectWithTransformationMatrix(xObj, 0, -1, 1, 0, 0,
                                inputPdf.getFirstPage().getPageSizeWithRotation().getHeight());
                    } else {
                        destCanvas.addXObjectWithTransformationMatrix(xObj, 1, 0, 0, 1, 0, 0);
                    }
                }

                // 2) Tagging (your existing planning + tagging flow)
                jsonArray = sortJsonArrayBasedOnPageNumber(jsonArray); // uses your method
                var builder = new TagPagePlanBuilder();
                var plans = builder.build(jsonArray, json -> PdfFileUtils.extractPageNumber(json));
                var plan = new PdfTaggingFromPlan(pdf, baseName, arialMono);
                plan.tagging(plans);
                // Auto-close: closing pdf will flush all bytes into the pipe
            } catch (Throwable t) {
                // Abort multipart if producer failed
                safeAbortMultipart(bucketName, s3OutputKey, uploadId);
                throw t;
            }

            // Wait for consumer to finish reading & uploading all parts
            uploader.join();

            // Complete multipart
            s3Client.completeMultipartUpload(new CompleteMultipartUploadRequest(bucketName, s3OutputKey, uploadId, parts));
            log.info("Multipart upload complete. s3://{}/{}", bucketName, s3OutputKey);
            return s3OutputKey;

        } catch (Throwable t) {
            // Best effort abort (if we still have an upload id)
            try {
                safeAbortMultipart(bucketName, s3OutputKey, init.getUploadId());
            } catch (Throwable ignore) {
            }
            throw new RuntimeException("Failed to stream tagged PDF to S3", t);
        }
    }

    // Helper: fill buffer fully unless EOF earlier (returns total bytes read)
    private static int fillBuffer(InputStream in, byte[] buf) throws IOException {
        int off = 0, r;
        while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
        return off;
    }

    private void safeAbortMultipart(String bucket, String key, String uploadId) {
        if (uploadId == null) return;
        try {
            s3Client.abortMultipartUpload(new AbortMultipartUploadRequest(bucket, key, uploadId));
            log.warn("Aborted multipart upload for s3://{}/{}", bucket, key);
        } catch (Exception e) {
            log.warn("Abort multipart failed for s3://{}/{}: {}", bucket, key, e.toString());
        }
    }

    private JSONArray sortJsonArrayBasedOnPageNumber(JSONArray jsonArray) {
        List<JSONObject> jsonValues = new ArrayList<>();
        for (Object obj : jsonArray) {
            if (obj instanceof JSONObject) {
                jsonValues.add((JSONObject) obj);
            }
        }
        Collections.sort(jsonValues, new Comparator<JSONObject>() {
            @Override
            public int compare(JSONObject a, JSONObject b) {
                String aPageJson = a.toJSONString();
                int aPageNumber = PdfFileUtils.extractPageNumber(aPageJson);

                String bPageJson = b.toJSONString();
                int bPageNumber = PdfFileUtils.extractPageNumber(bPageJson);

                return Integer.compare(aPageNumber, bPageNumber);
            }
        });

        // Convert back to JSONArray
        JSONArray sortedJsonArray = new JSONArray();
        sortedJsonArray.addAll(jsonValues);

        return sortedJsonArray;
    }

    private void safeDelete(String path) {
        if (path == null || path.isBlank()) return;
        try {
            Files.deleteIfExists(Paths.get(path));
            log.info("Deleted temp file: {}", path);
        } catch (Exception e) {
            log.warn("Could not delete temp file {}: {}", path, e.toString());
        }
    }

    public JSONArray readJsonArrayFromS3(String bucket, String inputFolderName, String folderName, String baseName) {
        String jsonFileName = baseName + ".json";
        String key = (folderName == null || folderName.isEmpty())
                ? inputFolderName + "/" + jsonFileName
                : inputFolderName + "/" + folderName + "/" + jsonFileName;
        return readJsonArrayFromS3(bucket, key);
    }

    public JSONArray readJsonArrayFromS3(String bucket, String key) {
        log.info("Reading JSON directly from S3: s3://{}/{}", bucket, key);

        if (!StringUtils.hasText(bucket) ||
                !StringUtils.hasText(key)) {
            throw new IllegalArgumentException("Bucket and key are required");
        }

        try (S3Object s3Object = s3Client.getObject(bucket, key)) {
            if (s3Object == null || s3Object.getObjectContent() == null) {
                throw new IllegalStateException("S3 object/content is null for key: " + key);
            }

            try (InputStream in = s3Object.getObjectContent();
                 Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {

                // Read stream -> String (no external libs)
                StringBuilder sb = new StringBuilder(64 * 1024);
                char[] buf = new char[8192];
                int n;
                while ((n = reader.read(buf)) > 0) sb.append(buf, 0, n);

                String json = sb.toString();
                Object parsed = new JSONParser().parse(json);

                if (parsed instanceof JSONArray) {
                    JSONArray arr = (JSONArray) parsed;
                    if (arr.isEmpty()) {
                        log.warn("Parsed JSON array is empty for s3://{}/{}", bucket, key);
                    }
                    return arr;
                } else {
                    throw new IllegalStateException("Expected a JSON array at s3://" + bucket + "/" + key);
                }
            }

        } catch (AmazonS3Exception e) {
            log.error("S3 error reading JSON s3://{}/{}: {} ({})", bucket, key, e.getMessage(), e.getErrorCode());
            throw new RuntimeException("Failed to read JSON from S3: " + e.getMessage(), e);
        } catch (ParseException e) {
            log.error("JSON parse error for s3://{}/{}: {}", bucket, key, e.getMessage());
            throw new RuntimeException("Failed to parse JSON from S3: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("I/O error reading s3://{}/{}: {}", bucket, key, e.toString());
            throw new RuntimeException("I/O error reading JSON from S3", e);
        }
    }

    private String prepareAndDownloadPdfFromS3(String bucketName, String fileName, String folderName, String inputFolderName) throws IOException {
        // Ensure extensions and base name
        String pdfExtension = PdfFileUtils.getPdfExtension(fileName);
        fileName = PdfFileUtils.ensurePdfExtension(fileName, pdfExtension);

        // Construct S3 input key
        String inputFileKey = (folderName == null || folderName.isEmpty()) ?
                inputFolderName + "/" + fileName :
                inputFolderName + "/" + folderName + "/" + fileName;

        // Create a local temp file path
        String localInputPath = "/tmp/" + System.currentTimeMillis() + "_" + fileName;

        log.info("Downloading PDF from S3: {}", inputFileKey);
        try (S3Object s3Object = s3Client.getObject(bucketName, inputFileKey)) {
            if (s3Object == null || s3Object.getObjectContent() == null) {
                log.error("S3 object or content is null for key: {}", inputFileKey);
                throw new IllegalStateException("S3 PDF content is null: " + inputFileKey);
            }

            Files.copy(s3Object.getObjectContent(), Paths.get(localInputPath), StandardCopyOption.REPLACE_EXISTING);
            log.info("PDF downloaded successfully to: {}", localInputPath);
            return localInputPath;
        } catch (AmazonS3Exception e) {
            log.error("Failed to download PDF from S3: {}, Code: {}", e.getMessage(), e.getErrorCode());
            throw new RuntimeException("Failed to download PDF from S3: " + e.getMessage());
        }
    }

}
