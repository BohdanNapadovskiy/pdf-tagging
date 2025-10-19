package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record PagePlan(int pageNumber, List<Op> ops) {
}
