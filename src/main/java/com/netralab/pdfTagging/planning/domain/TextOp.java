package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record TextOp(List<LineOp> lines, String role) implements Op {}
