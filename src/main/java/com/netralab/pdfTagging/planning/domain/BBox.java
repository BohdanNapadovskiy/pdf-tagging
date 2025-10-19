package com.netralab.pdfTagging.planning.domain;

import org.json.simple.JSONObject;

public record BBox(float left, float top, float width, float height, float avgWordHeight) {

    public static BBox toBBox(JSONObject b, String tagName, boolean required) {
        if(required) {
            if (b == null || b.isEmpty())
                throw new IllegalArgumentException("bBox is required for " + tagName);
            float avg = (b.get("avgWordHeight") instanceof Number n) ? n.floatValue() : 0f;
            return new BBox(num(b, "Left"), num(b, "Top"), num(b, "Width"), num(b, "Height"), avg);
        } else {
            return new BBox(0f, 0f, 0f, 0f, 0f);
        }
    }

  public static float num(JSONObject o, String key) {
    Object v = o.get(key);
    if (!(v instanceof Number n)) throw new IllegalArgumentException("bBox." + key + " must be a number");
    return n.floatValue();
  }


}
