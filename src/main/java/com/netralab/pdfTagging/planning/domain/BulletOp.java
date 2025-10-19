package com.netralab.pdfTagging.planning.domain;

public record BulletOp(String text, String contentType, BBox bBox, long identLevel) implements Op {
}
