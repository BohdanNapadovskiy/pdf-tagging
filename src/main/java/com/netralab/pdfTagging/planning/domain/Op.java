package com.netralab.pdfTagging.planning.domain;

public sealed interface Op permits BulletOp, CellOp, FigureOp, FigureTagOP, FormLineOP, FormOp, ListOp, TableOp, TextOp, TociItem, TociOp { }
