package com.netralab.pdfTagging.service;


import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class FieldProcessor {

    public void processFields(List<Map<String, Object>> fields, JsonArray tagObjects, float pageWidth, float pageHeight) {
        for (Map<String, Object> field : fields) {
            Object bBoxObj = field.get("bBox");
            Object fieldsName = field.get("fieldsName");
            if (bBoxObj instanceof Map) {
                Map<String, Object> bBox = (Map<String, Object>) bBoxObj;
                Object xObj = bBox.get("x");
                Object yObj = bBox.get("y");
                Object widthObj = bBox.get("width");
                Object heightObj = bBox.get("height");
                if (xObj instanceof Number && yObj instanceof Number && widthObj instanceof Number && heightObj instanceof Number) {
                    float x = ((Number) xObj).floatValue() + ((Number) widthObj).floatValue();
                    float y = pageHeight - ((Number) heightObj).floatValue() - ((Number) yObj).floatValue();
                    float width = ((Number) widthObj).floatValue();
                    float rightEdge = x + width;
                    log.info("Processing field: {}, x: {}, y: {}, rightEdge: {}", fieldsName, x, y, rightEdge);

                    JsonObject closestField = findClosestRightEdgeField(x, y, tagObjects, pageWidth, pageHeight);
                    log.info("closest Fields : {}", closestField);
                    if (closestField != null) {
                        log.info("Closest field for {}: {}", fieldsName, closestField);

                        String closestText = closestField.has("text") ? closestField.get("text").getAsString() : null;
                        String closestId = closestField.has("Id") ? closestField.get("Id").getAsString() : null;
                        Object valueBBoxObject = field.get("bBox");
                        JsonObject valueBBox = new Gson().toJsonTree(valueBBoxObject).getAsJsonObject();
                        if (closestText != null && valueBBox != null) {
                            for (JsonElement tagElement : tagObjects) {
                                JsonObject tagObject = tagElement.getAsJsonObject();
                                if (tagObject.has("tag") && tagObject.get("tag").getAsString().equals("Form") && tagObject.has("lines")) {
                                    JsonArray lines = tagObject.getAsJsonArray("lines");
                                    for (JsonElement lineElement : lines) {
                                        JsonObject line = lineElement.getAsJsonObject();
                                        if (line.has("Id") && line.get("Id").getAsString().equals(closestId)) {
                                            line.add("value_bBox", valueBBox);
                                            log.info("Updated value_bBox for line with fieldsValue '{}': {}", closestText, line);
                                            break;
                                        }
                                    }
                                }
                            }
                        } else {
                            log.info("Closest field missing text or value_bBox for {}", fieldsName);
                        }
                    } else {
                        log.info("No matching field found for {}", fieldsName);
                    }
                }
            }
        }
    }

    private JsonObject findClosestRightEdgeField(float x, float y, JsonArray tagObjects, float pageWidth, float pageHeight) {
        log.info("Finding closest field to the right of point ({}, {}) on page (width: {}, height: {})",
                x, y, pageWidth, pageHeight);
        if (tagObjects == null || tagObjects.isEmpty()) {
            log.info("Tag objects array is null or empty");
            return null;
        }

        JsonObject closestField = null;
        double minDistance = Double.MAX_VALUE;
        int processedFields = 0;

        log.info("Iterating through {} tag objects", tagObjects.size());
        for (JsonElement tagElement : tagObjects) {
            if (!tagElement.isJsonObject()) {
                log.info("Skipping non-JsonObject tag element: {}", tagElement);
                continue;
            }

            JsonObject tagObject = tagElement.getAsJsonObject();
            String tag = tagObject.has("tag") ? tagObject.get("tag").getAsString() : null;

            if (!"Form".equals(tag)) {
                log.info("Skipping non-Form tag: {}", tag);
                continue;
            }

            if (!tagObject.has("lines")) {
                log.info("Tag object has no lines: {}", tagObject);
                continue;
            }

            JsonArray lines = tagObject.getAsJsonArray("lines");
            log.info("Processing {} lines in Form tag", lines.size());

            for (JsonElement lineElement : lines) {
                if (!lineElement.isJsonObject()) {
                    log.info("Skipping non-JsonObject line element: {}", lineElement);
                    continue;
                }

                JsonObject line = lineElement.getAsJsonObject();
                JsonObject bBox = line.has("bBox") ? line.getAsJsonObject("bBox") : null;

                if (bBox == null || !bBox.has("Left") || !bBox.has("Top")) {
                    log.info("Skipping line with invalid or missing bBox: {}", line);
                    continue;
                }

                // Convert coordinates to pixels
                float fieldLeft = bBox.get("Left").getAsFloat() * pageWidth;
                float fieldTop = bBox.get("Top").getAsFloat() * pageHeight;
                processedFields++;

                if (fieldLeft >= x) {
                    double distance = Math.sqrt(
                            Math.pow(fieldLeft - x, 2) + Math.pow(fieldTop - y, 2)
                    );
                    log.info("Field at ({}, {}), distance: {}", fieldLeft, fieldTop, distance);

                    // Update closest field if this distance is smaller
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestField = line;
                        log.info("New closest field found: {}, distance: {}", closestField, minDistance);
                    }
                } else {
                    log.info("Field at ({}, {}) is to the left of x={}, skipping", fieldLeft, fieldTop, x);
                }
            }
        }

        if (closestField != null) {
            log.info("Closest field to the right: {}, distance: {}, processed {} fields.",
                    closestField, minDistance, processedFields);
        } else {
            log.info("No fields found to the right of point: ({}, {}), processed {} fields.",
                    x, y, processedFields);
        }

        return closestField;
    }

}
