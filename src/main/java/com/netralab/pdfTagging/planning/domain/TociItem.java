package com.netralab.pdfTagging.planning.domain;

public record TociItem(String text, String contentType, String font, BBox box) implements Op {
}
