package com.netralab.pdfTagging.service;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Component;

import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

@Slf4j
@Component
public class ChatGptClient {

    @Value("${CHATGPT_KEY}")
    private String key;

    @Value("${chatgpt.open-api.uri}")
    private String uri;

    @Value("${chatgpt.open-api.model}")
    private String model;

    @Value("${chatgpt.open-api.role}")
    private String role;

    @Retryable(
            value = {IOException.class},
            include = {SocketTimeoutException.class, ConnectException.class},
            exclude = {IllegalStateException.class},
            maxAttempts = 3,
            backoff = @Backoff(delay = 1000, multiplier = 2, random = true)
    )
    public String callChatGPT(String prompt, String base64Image) throws Exception {
        log.info("===> [START] callChatGPT");
        log.info("Prompt length: {}, Base64 image length: {}", prompt.length(), base64Image.length());

        prompt = prompt.replace("\"", "\\\"");
        log.info("Sanitized prompt: {}", prompt);

        String jsonInputString = createRequestBody(prompt, base64Image);
        log.info("Generated JSON payload: {}", jsonInputString);

        HttpURLConnection connection = null;
        try {
            log.info("Sending request to OpenAI API...");
            connection = sendPostRequest(jsonInputString);

            log.info("Connection established with: {}", connection.getURL());
            log.info("Request method: {}, Headers: {}", connection.getRequestMethod(), connection.getRequestProperties());

            int responseCode = connection.getResponseCode();
            log.info("HTTP response code: {}", responseCode);

            if (responseCode == 200) {
                String response = getResponse(connection);
                if (response != null) {
                    log.info("Success: Response received from ChatGPT API");
                    log.info("Response body: {}", response);
                    return response;
                } else {
                    log.info("info: Null response received despite 200 status");
                    throw new IOException("Received null response from ChatGPT API");
                }
            } else if (responseCode == 429 || responseCode == 503) {
                String infoMessage = getResponse(connection);
                log.info("Retryable info occurred: HTTP {} - {}", responseCode, infoMessage);
                throw new IOException("HTTP " + responseCode + ": " + infoMessage);
            } else {
                String infoMessage = getResponse(connection);
                log.info("Non-retryable info occurred: HTTP {} - {}", responseCode, infoMessage);
                throw new IllegalStateException("HTTP " + responseCode + ": " + infoMessage);
            }
        }finally {
            if (connection != null) {
                connection.disconnect();
                log.info("HTTP connection closed.");
            }
            log.info("===> [END] callChatGPT");
        }
    }

    private String createRequestBody(String prompt, String base64Image) {
        log.info("===> [START] createRequestBody");
        log.info("Model: {}", model);
        log.info("Role: {}", role);
        log.info("Prompt (escaped): {}", prompt);
        log.info("Base64 image length: {}", base64Image != null ? base64Image.length() : 0);

        // Root object
        JsonObject root = new JsonObject();
        root.addProperty("model", model);

        // Message array
        JsonArray messages = new JsonArray();
        JsonObject message = new JsonObject();
        message.addProperty("role", role);

        // Message content: text + image
        JsonArray contentArray = getJsonElements(prompt, base64Image);
        log.info("Content array: {}", contentArray);

        message.add("content", contentArray);
        messages.add(message);
        root.add("messages", messages);

        // Response format
        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        root.add("response_format", responseFormat);

        String requestBody = root.toString();
        log.info("Final JSON request body: {}", requestBody);
        log.info("===> [END] createRequestBody");

        return requestBody;
    }

    private JsonArray getJsonElements(String prompt, String base64Image) {
        log.info("===> [START] getJsonElements");

        if (prompt == null || prompt.trim().isEmpty()) {
            log.info("Prompt is null or empty. Proceeding with empty text field.");
        } else {
            log.info("Prompt content: {}", prompt);
        }

        if (base64Image == null || base64Image.trim().isEmpty()) {
            log.info("Base64 image string is null or empty. Proceeding without image.");
        } else {
            log.info("Base64 image length: {}", base64Image.length());
        }

        JsonArray contentArray = new JsonArray();

        // Text part
        JsonObject textPart = new JsonObject();
        textPart.addProperty("type", "text");
        textPart.addProperty("text", prompt != null ? prompt : "");
        contentArray.add(textPart);
        log.info("Added text part to content array.");

        // Image part
        if (base64Image != null && !base64Image.trim().isEmpty()) {
            JsonObject imageUrlObject = new JsonObject();
            imageUrlObject.addProperty("url", "data:image/png;base64," + base64Image);

            JsonObject imagePart = new JsonObject();
            imagePart.addProperty("type", "image_url");
            imagePart.add("image_url", imageUrlObject);

            contentArray.add(imagePart);
            log.info("Added image part to content array.");
        } else {
            log.info("Skipping image part due to missing base64 data.");
        }

        log.info("===> [END] getJsonElements");
        return contentArray;
    }

    private HttpURLConnection sendPostRequest(String jsonInputString) throws IOException {
        log.info("===> [START] sendPostRequest");
        log.info("Target URI: {}", uri);
        log.info("Request payload length: {}", jsonInputString != null ? jsonInputString.length() : 0);

        URL url = new URL(uri);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();

        connection.setRequestMethod("POST");
        connection.setDoOutput(true);
        connection.setRequestProperty("Authorization", "Bearer " + key);
        connection.setRequestProperty("Content-Type", "application/json");

        log.info("Opening connection output stream...");
        try (OutputStream os = connection.getOutputStream()) {
            byte[] input = Objects.requireNonNull(jsonInputString).getBytes(StandardCharsets.UTF_8);
            os.write(input, 0, input.length);
            os.flush();
            log.info("Request payload written to output stream successfully.");
        }

        log.info("POST request sent successfully to {}", uri);
        log.info("===> [END] sendPostRequest");
        return connection;
    }

    private String getResponse(HttpURLConnection connection) {
        log.info("===> [START] getResponse");
        try {
            int responseCode = connection.getResponseCode();
            log.info("HTTP Response Code: {}", responseCode);

            InputStream inputStream = responseCode >= 400 ? connection.getErrorStream() : connection.getInputStream();

            if (inputStream == null) {
                log.info("No response stream available from HTTP connection.");
                return null;
            }

            try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))) {
                StringBuilder response = new StringBuilder();
                String responseLine;
                while ((responseLine = br.readLine()) != null) {
                    response.append(responseLine.trim());
                }

                String responseString = response.toString();
                log.info("Raw response: {}", responseString);
                log.info("Response read successfully from the connection.");
                log.info("===> [END] getResponse");
                return responseString;
            }
        } catch (IOException e) {
            log.info("Failed to read response from HTTP connection: {}", e.getMessage(), e);
            return null;
        }
    }

}