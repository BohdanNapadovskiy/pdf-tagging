package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record FigureTagOP(List<FigureOp> figures, BBox parentCoor) implements Op {
}
