package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record FormOp(String role, List<FormLineOP> lines) implements Op {
}
