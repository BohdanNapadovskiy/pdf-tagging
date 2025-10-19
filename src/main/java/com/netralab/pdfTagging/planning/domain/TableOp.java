package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record TableOp(List<CellOp> cells, String role) implements Op {}
