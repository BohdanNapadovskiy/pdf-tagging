package com.netralab.pdfTagging.planning.domain;

import java.util.List;

public record TociOp (String role, List<TociItem> items) implements Op {
}
