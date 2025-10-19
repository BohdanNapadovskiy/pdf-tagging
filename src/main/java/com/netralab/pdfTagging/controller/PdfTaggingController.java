package com.netralab.pdfTagging.controller;

import com.netralab.pdfTagging.rest.PdfTaggingRequest;
import com.netralab.pdfTagging.service.PdfTaggingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
public class PdfTaggingController {

    private final  PdfTaggingService pdfTaggingService;

    @GetMapping("/health")
    public String healthCheck() {
        return "OK";
    }

    @GetMapping("/api/ping")
    public String ping() {
        return "Welcome to the Pdf Tagging API!";
    }

    @PostMapping("/api/pdf-tagging")
    public ResponseEntity<?> pdfTagging(@RequestBody PdfTaggingRequest request) {
        log.info("Pdf Tagging Request Body = {}", request);

        try {
            Map<String, Object> response = pdfTaggingService.handleRequest(request);
            return ResponseEntity.ok(response); // 200 OK with response body
        } catch (Exception e) {
            log.error("Error while processing PDF tagging request", e);
            Map<String, Object> res = new HashMap<>();
            res.put("message", e.getMessage());
            res.put("error", "true");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(res);
        }
    }

}
