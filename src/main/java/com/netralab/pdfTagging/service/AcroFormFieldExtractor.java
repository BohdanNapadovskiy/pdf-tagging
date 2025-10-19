package com.netralab.pdfTagging.service;

import com.google.gson.*;
import com.itextpdf.forms.PdfAcroForm;
import com.itextpdf.forms.fields.PdfFormField;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.annot.PdfWidgetAnnotation;
import com.netralab.pdfTagging.utils.PdfFieldFilterUtils;
import com.netralab.pdfTagging.utils.PdfFileUtils;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Slf4j
@Service
public class AcroFormFieldExtractor {

    @Autowired
    ChatGptClient chatGptClient;

    @Autowired
    FieldProcessor fieldProcessor;

    public boolean isAcroFormsAvailable(PdfDocument inputPdf, int pageNumber, List<Map<String, Object>> fieldList) {
        // Retrieve the AcroForm from the PDF
        PdfAcroForm srcForm = PdfAcroForm.getAcroForm(inputPdf, false);
        if (srcForm == null) {
            System.err.println("No AcroForm found in the PDF."); // Optional logging
            return false;
        }
        Map<String, PdfFormField> formFields = srcForm.getAllFormFields();

        log.info("Total form fields in document: {}", formFields.size());

        if (formFields.isEmpty()) {
            log.info("No form fields found in the PDF");
            return false;  // No fields in the entire document, return false
        }
        // Iterate over each form field
        for (Map.Entry<String, PdfFormField> entry : formFields.entrySet()) {
            String fieldName = entry.getKey();
            PdfFormField field = entry.getValue();
            String fieldValue = field.getValueAsString();

            log.info("Checking field: {} with value: {}", fieldName, fieldValue);

            // Iterate over widgets associated with the form field
            for (PdfWidgetAnnotation widget : field.getWidgets()) {
                // Get the page index for the widget's page
                int widgetPageNumber = inputPdf.getPageNumber(widget.getPage());

                // Compare the widget's page number to the requested page number
                if (widgetPageNumber == pageNumber) { // Widget is on the requested page
                    // If the widget is on the requested page, fetch its position and add to list
                    Rectangle rect = widget.getRectangle().toRectangle();
                    log.info("Field position (x,y,w,h): {},{},{},{}", rect.getX(), rect.getY(), rect.getWidth(), rect.getHeight());

                    Map<String, Object> bbox = new HashMap<>();
                    bbox.put("x", rect.getX());
                    bbox.put("y", rect.getY());
                    bbox.put("width", rect.getWidth());
                    bbox.put("height", rect.getHeight());

                    Map<String, Object> fieldInfo = new HashMap<>();
                    fieldInfo.put("id", UUID.randomUUID().toString());
                    fieldInfo.put("fieldsName", fieldName);
                    fieldInfo.put("fieldsValue", fieldValue);
                    fieldInfo.put("bBox", bbox);
                    fieldInfo.put("fieldType", field.getFormType() != null ? field.getFormType().getValue() : null);

                    log.info("Added field info: {}", fieldInfo);

                    fieldList.add(fieldInfo); // Add the field info to the list
                } else {
                    log.info("Skipping widget not on requested page (Widget page: {}, Requested page: {})", widgetPageNumber + 1, pageNumber);
                }
            }
        }
        // Check if we found any fields for the requested page
        if (fieldList.isEmpty()) {
            log.info("No form fields found on the specified page");
            return false;  // No form fields on this page
        }
        log.info("Completed field extraction with {} fields on page {}", fieldList.size(), pageNumber);
        return true;  // Fields are available on the specified page
    }

    public String setAcroFieldMappingInJson(PDDocument inputPdf, String jsonData,
                                             String fileBaseName, int pageNumber,List<Map<String, Object>> fieldList) {

        log.info("Converting PDF page to Base64");
        String base64Image = PdfFileUtils.convertPdfPageToBase64(inputPdf, pageNumber, "/tmp/" + fileBaseName + ".png");
        log.info("PDF page successfully converted to Base64, length: {}", base64Image.length());

        // Prepare the field list for the prompt
        log.info("Preparing field list for ChatGPT prompt");
        List<Map<String, String>> maps = new ArrayList<>();
        for (Map<String, Object> map : fieldList) {
            HashMap<String, String> field = new HashMap<>();
            field.put("fieldsName", map.get("fieldsName").toString());
            maps.add(field);
        }
        Gson gson = new Gson();
        String jsonOutput = gson.toJson(maps);
        log.info("Generated field list JSON: {}", jsonOutput);

        String searchString = generatePrompt(jsonOutput);
        log.info("Calling ChatGPT with prompt");
        JSONObject jsonResponse = null;
        try {
            String response = chatGptClient.callChatGPT(searchString, base64Image);
            JSONParser parser = new JSONParser();

            // Parse the ChatGPT response
            log.info("Parsing ChatGPT response");
            jsonResponse = (JSONObject) parser.parse(response);
            JSONArray choices = (JSONArray) jsonResponse.get("choices");
            if (choices == null || choices.isEmpty()) {
                log.info("No choices found in ChatGPT response");
                throw new RuntimeException("No choices found in ChatGPT response.");
            }

            JSONObject firstChoice = (JSONObject) choices.get(0);
            JSONObject message = (JSONObject) firstChoice.get("message");
            String contentString = (String) message.get("content");
            JSONObject contentJson = (JSONObject) parser.parse(contentString);
            JSONArray fieldMappings = (JSONArray) contentJson.get("fieldMappings");
            log.info("Received {} field mappings from ChatGPT", fieldMappings.size());

            // Parse json1
            log.info("Parsing input JSON data");
            JsonObject json1 = JsonParser.parseString(jsonData).getAsJsonObject();
            JsonArray tagObjects = json1.getAsJsonArray("TagObjects");
            float pageWidth = (float) ((json1.get("PageWidth").getAsFloat()) * 0.24);
            float pageHeight = (float) ((json1.get("PageHeight").getAsFloat()) * 0.24);
            log.info("pageWidth is :{}", pageWidth);
            log.info("pageHeight is :{}", pageHeight);

            // Process field mappings
            log.info("Processing {} field mappings", fieldMappings.size());
            int updatedFields = 0;
            for (Object obj : fieldMappings) {
                JSONObject mapping = (JSONObject) obj;
                String jsonField = (String) mapping.get("jsonField");
                String pdfField = (String) mapping.get("pdfField");
                log.info("Processing mapping: jsonField={}, pdfField={}", jsonField, pdfField);

                // Find matching field in fieldList
                Map<String, Object> matchingField = fieldList.stream()
                        .filter(field -> jsonField.trim().equals(field.get("fieldsName").toString().trim()))
                        .findFirst()
                        .orElse(null);

                if (matchingField == null) {
                    log.info("No matching field found in fieldList for jsonField: {}", jsonField);
                    continue;
                }

                // Find matching text in json1
                boolean fieldUpdated = false;
                for (JsonElement tagElement : tagObjects) {
                    JsonObject tagObject = tagElement.getAsJsonObject();
                    JsonArray lines = tagObject.getAsJsonArray("lines");
                    if (lines == null) {
                        log.info("No lines found in tag object: {}", tagObject);
                        continue;
                    }

                    for (JsonElement lineElement : lines) {
                        JsonObject line = lineElement.getAsJsonObject();
                        String text = line.get("text").getAsString();
                        if (text != null && text.trim().equalsIgnoreCase(pdfField.trim())) {
                            log.info("Found matching text for pdfField: {} in line: {}", pdfField, line);
                            // Add fieldList data to the line
                            line.addProperty("fieldsValue", matchingField.get("fieldsValue").toString());
                            line.addProperty("fieldsName", matchingField.get("fieldsName").toString());
                            line.add("value_bBox", gson.toJsonTree(matchingField.get("bBox")));
                            line.addProperty("id", matchingField.get("id").toString());
                            line.addProperty(
                                    "fieldType",
                                    Optional.ofNullable(matchingField.get("fieldType"))
                                            .map(Object::toString)
                                            .orElse("")
                            );
                            fieldUpdated = true;
                            updatedFields++;
                            log.info("Updated field: fieldsName={}, fieldsValue={}",
                                    matchingField.get("fieldsName"), matchingField.get("fieldsValue"));
                            break;
                        }
                    }
                    if (fieldUpdated) {
                        break;
                    }
                }

                if (!fieldUpdated) {
                    log.info("No matching text found in json1 for pdfField: {}", pdfField);
                }
            }
            log.info("Updated {} fields in JSON", updatedFields);

            // Fetching Btn, findMinimum distance field and updated tagObjects
            log.info("Fetching and processing Btn fields");
            List<Map<String, Object>> fields = PdfFieldFilterUtils.fetchFieldsFromFieldList(fieldList,"Btn");
            log.info("Found {} Btn fields", fields.size());
            fieldProcessor.processFields(fields, tagObjects, pageWidth, pageHeight);
            log.info("Processed Btn fields");

            Gson prettyGson = new GsonBuilder().setPrettyPrinting().create();
            log.info("Serializing updated JSON");
            String result = prettyGson.toJson(json1);
            log.info("Completed AcroField mapping, returning JSON with length: {}", result.length());
            return result;
        } catch (Exception e) {
            log.info("Error processing JSON or updating fields: {}", e.getMessage(), e);
            return "";
        }
    }

    public String generatePrompt(String fieldListJson) {
        log.info("Method generatePrompt called with fieldListJson: {}", fieldListJson);
        String result = String.format(promptTemplate(), fieldListJson);
        log.info("Successfully generated prompt: {}", result);
        return result;
    }

    public String promptTemplate(){
        return "Map the field names in the below JSON to the corresponding names in the attached PDF accurately. "
                        + "There are short forms and pseudo names used. Carefully analyze and provide resulting JSON.\n"
                        + "Must include any text within these '()' in pdfField if given in pdf. Mostly check for Yes and No pdfFields.\n"
                        + "If there are numbers in jsonField like '27a', '32c', '32b' etc. then include that number in pdfField at start like '27.', '32.', '32.' etc.\n"
                        + "field names json: '%s'\n"
                        + ".\n"
                        + "Example output json: {\n"
                        + "  \"fieldMappings\": [\n"
                        + "    {\n"
                        + "      \"jsonField\": \"LastName\",\n"
                        + "      \"pdfField\": \"Last Name\"\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"jsonField\": \"FirstName\",\n"
                        + "      \"pdfField\": \"First Name\"\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"jsonField\": \"1099Yes\",\n"
                        + "      \"pdfField\": \"Yes (Did you make payments in the sum of $600.00 or more...)\"\n"
                        + "    },\n"
                        + "    {\n"
                        + "      \"jsonField\": \"32c\",\n"
                        + "      \"pdfField\": \"32. Credit to next year: c.\"\n"
                        + "    }\n"
                        + "  ]\n"
                        + "}";
    }


}
