package com.netralab.pdfTagging.planning.domain;

public record FormLineOP (String id, String text, String contentType, BBox bBox, BBox valueBox) implements Op {
}
