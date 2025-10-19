package com.netralab.pdfTagging.planning.domain;

public record FigureOp(String text, String contentType, BBox bBox) implements Op {
}
