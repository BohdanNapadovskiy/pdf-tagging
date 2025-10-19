package com.netralab.pdfTagging.planning.domain;

import org.json.simple.JSONObject;

public record CellOp(String text, String cellType, BBox bBox, long rowIndex, long columnIndex, int rowSpan, int columnSpan) implements Op {



    public static String str(JSONObject o, String key, boolean required) {
        Object v = o.get(key);
        if (v == null) {
            if (required) throw new IllegalArgumentException(key + " is required");
            return "";
        }
        return String.valueOf(v);
    }


}


