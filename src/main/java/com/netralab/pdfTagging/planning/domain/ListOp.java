package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record ListOp(String role, List<BulletOp> bullets, BBox parentCoor) implements Op {
}
