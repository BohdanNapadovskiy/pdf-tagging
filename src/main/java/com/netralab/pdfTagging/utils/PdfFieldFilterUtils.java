package com.netralab.pdfTagging.utils;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
public class PdfFieldFilterUtils {
    public static List<Map<String, Object>> fetchFieldsFromFieldList(List<Map<String, Object>> fieldList ,String targetFieldType){
        log.info("Fetching fields with target field type: {}", targetFieldType);

        if (targetFieldType == null) {
            log.info("Target field type is null");
            return List.of();
        }
        List<Map<String, Object>> filteredFields = fieldList.stream()
                .filter(field -> {
                    boolean matches = targetFieldType.equals(field.get("fieldType"));
                    log.info("Checking field: {}, matches: {}", field, matches);
                    return matches;
                })
                .collect(Collectors.toList());

        log.info("Found {} fields matching type: {}", filteredFields.size(), targetFieldType);
        return filteredFields;
    }
}
