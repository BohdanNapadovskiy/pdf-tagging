package com.netralab.pdfTagging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.geom.Rectangle;
import com.itextpdf.kernel.pdf.*;
import com.itextpdf.kernel.pdf.canvas.PdfCanvas;
import com.itextpdf.kernel.pdf.extgstate.PdfExtGState;
import com.itextpdf.kernel.pdf.tagging.PdfStructElem;
import com.itextpdf.kernel.pdf.tagging.StandardRoles;
import com.itextpdf.kernel.pdf.tagutils.TagReference;
import com.itextpdf.kernel.pdf.tagutils.TagStructureContext;
import com.itextpdf.kernel.pdf.tagutils.TagTreePointer;
import com.netralab.pdfTagging.planning.domain.*;
import com.netralab.pdfTagging.utils.PdfFontUtils;
import lombok.extern.slf4j.Slf4j;
import org.json.JSONException;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class PdfTaggingFromPlan {

    private PdfDocument pdf;
    private String baseName;
    private PdfFont arialMono;
    final PdfExtGState INVIS_FILL = new PdfExtGState().setFillOpacity(0f);
    final PdfExtGState INVIS_BOTH = new PdfExtGState().setFillOpacity(0f).setStrokeOpacity(0f);
    private final ObjectMapper mapper = new ObjectMapper();

    public PdfTaggingFromPlan(PdfDocument pdf, String baseName, PdfFont font) {
        this.pdf = pdf;
        this.baseName = baseName;
        this.arialMono = font;

    }

    public void tagging(List<PagePlan> plans) {
        PdfDocumentInfo info = pdf.getDocumentInfo();
        log.info("Document initialized successfully");
        pdf.setTagged();
        info.setTitle(baseName);
        info.setProducer("Accessibility on Demand v2.0 - accessibility tagging - WCAG/PDFUA and Section 508 standards -");
        pdf.getCatalog().setViewerPreferences(new PdfViewerPreferences().setDisplayDocTitle(true));
        String pdfLanguage = "en-US";
        pdf.getCatalog().setLang(new PdfString(pdfLanguage));
        TagStructureContext tagContext = pdf.getTagStructureContext();
        TagTreePointer tagPointer = tagContext.getAutoTaggingPointer();
        for (PagePlan plan : plans) { //pages
            int pageNumber = plan.pageNumber();
            PdfPage destPage = pdf.getPage(pageNumber);
            if (tagContext == null) {
                log.info("Failed to initialize tag structure context");
                throw new IllegalStateException("Failed to initialize tag structure context");
            }
            if (tagPointer == null) {
                log.info("Failed to initialize tag tree pointer");
                throw new IllegalStateException("Failed to initialize tag tree pointer");
            }
            tagPointer.moveToRoot();
            tagPointer.setPageForTagging(destPage);
            tagPointer.moveToRoot();
            tagPointer.addTag(StandardRoles.SECT);
            int sectIndex = getSectIndex(tagPointer);
            log.info("Tagging page {} with sect index {}", pageNumber, sectIndex);
            Rectangle pageSize = destPage.getPageSize();
            if (pageSize == null) {
                log.info("Failed to get page size for page {}", pageNumber);
                throw new IllegalStateException("Failed to get page size for page " + pageNumber);
            }
            float pageWidth = pageSize.getWidth();
            float pageHeight = pageSize.getHeight();
            PdfCanvas pageCanvas = new PdfCanvas(destPage);
            if (plan.ops() == null || plan.ops().isEmpty()) {
                log.info("TagObjects is empty for page {}, adding empty section", pageNumber);
                TagReference tagRef = tagPointer.getTagReference();

                PdfExtGState invisible = new PdfExtGState().setFillOpacity(0.0f);

                pageCanvas.openTag(tagRef);
                pageCanvas.beginText()
                        .setFontAndSize(arialMono, 12)
                        .setExtGState(invisible)
                        .moveText(50, 50)
                        .endText();
                pageCanvas.closeTag();
                tagPointer.moveToParent();
                log.info("Added empty section to page {}", pageNumber);
                continue;
            }
            String text = "";
            String tagRole = "";
            String contentType = "";
            for (Op op : plan.ops()) {
                if (op instanceof TextOp t) {
                    tagRole = t.role();
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    tagPointer.addTag(tagRole);
                    TagReference tagRef2 = tagPointer.getTagReference();
                    pageCanvas.openTag(tagRef2);
                    // Embed MathML in the struct element if available
                    PdfStructElem structElem = pdf.getTagStructureContext().getPointerStructElem(tagPointer);
                    if (t.lines() != null && !t.lines().isEmpty()) {
                        for (LineOp line : t.lines()) {
                            text = line.text();
                            if (text == null) text = "";
                            if (line.mathMl() != null) {
                                String mathML =  line.mathMl();
                                if (mathML != null && !mathML.isEmpty()) {
                                    structElem.getPdfObject().put(PdfName.Alt, new PdfString(text));
                                    structElem.getPdfObject().put(PdfName.ActualText, new PdfString(mathML));
                                    log.info("Embedded MathML into PdfStructElem.");
                                } else {
                                    log.info("MathML is present but empty. Skipping embedding.");
                                }
                            }
                            float ttWidth = 0f;
                            float ttHeight = 0f;
                            float ttLeft = 0f;
                            float ttTop = 0f;
                            float ttAvgWordHeight = 0f;
                            try {
                                Object widthObj = line.box().width();
                                Object heightObj = line.box().height();
                                Object leftObj = line.box().left();
                                Object topObj = line.box().top();
                                Object avgObj = line.box().avgWordHeight();
                                if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                                        !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                                    log.info("{}: Invalid bBox property on page {}: expected Number", tagRole, pageNumber);
                                    throw new NullPointerException(tagRole + ": Invalid bBox property on page {}: expected Number");
                                }
                                ttWidth = ((Number) widthObj).floatValue();
                                ttHeight = ((Number) heightObj).floatValue();
                                ttLeft = ((Number) leftObj).floatValue();
                                ttTop = ((Number) topObj).floatValue();
                                ttAvgWordHeight = ((Number) avgObj).floatValue();
                            } catch (JSONException | NullPointerException e) {
                                log.info("{}: Missing bBox properties (Width, Height, Left, or Top) on page {}: {}", tagRole, pageNumber, e.getMessage());
                                throw new NullPointerException(tagRole + ": bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                            }

                            float x = ttLeft * pageWidth;
                            float y = (1 - ttTop - ttHeight) * pageHeight;
                            float width = (ttWidth * pageWidth) + 7;
                            float height = (ttHeight * pageHeight) + 7;
                            float bestFontSize = PdfFontUtils.calculateBestFitFontSize(arialMono, height);
                            float textWidth = arialMono.getWidth(text, bestFontSize);
                            float textHeight = bestFontSize;
                            pageCanvas.beginText()
                                    .setFontAndSize(arialMono, bestFontSize)
                                    .setExtGState(INVIS_FILL)
                                    .setTextMatrix(width / textWidth, 0, 0, height / textHeight, x - 2, y + (ttAvgWordHeight / 2))
                                    .showText(text)
                                    .endText();
                        }

                    }
                    pageCanvas.closeTag();
                    tagPointer.moveToParent();
                }
                else if (op instanceof TableOp tb) {
                    final long tableStart = System.currentTimeMillis();
                    int rowIndex = 0;
                    int row = -1;
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT).addTag(StandardRoles.TABLE);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    tagPointer.addTag(StandardRoles.TABLE);
                    int currentRowIndex = 0;
                    for (CellOp cell : tb.cells()) {
                        int jsonRowIndex = 0;
                        try {
                            long rowIndexObj = cell.rowIndex();
                            jsonRowIndex = ((Long) rowIndexObj).intValue();
                        } catch (JSONException | NullPointerException e) {
                            log.info("Table: Missing RowIndex in cell at page {}: {}", pageNumber, e.getMessage());
                            throw new NullPointerException("Table: Cell must contain a valid 'RowIndex'");
                        }

                        if (currentRowIndex != jsonRowIndex) {
                            rowIndex++;
                            currentRowIndex = jsonRowIndex;
                            if (row == -1) {
                                tagPointer.addTag(StandardRoles.TR);
                            } else {
                                tagPointer.moveToParent().addTag(StandardRoles.TR);
                            }
                            row = jsonRowIndex;
                        }
                        text = cell.text();
                        if (text == null || text.isEmpty()) {
                            text = "";
                        }
                        String cellType = cell.cellType();
                        if (cellType == null || cellType.isEmpty()) {
                            log.info("Table: Missing or invalid cellType in cell on page {}", rowIndex, pageNumber);
                            throw new IllegalArgumentException("Table: Missing or invalid cellType");
                        }
                        int columnIndex = 0;
                        try {
                            long columnIndexObj = cell.columnIndex();
                            columnIndex = (int) columnIndexObj;
                        } catch (JSONException | NullPointerException e) {
                            log.info("Table: Missing columnIndex in cell at page {}: {}", pageNumber, e.getMessage());
                            throw new NullPointerException("Table: Cell must contain a valid 'columnIndex'");
                        }
                        BBox bBox = cell.bBox();
                        float ttWidth = 0f;
                        float ttHeight = 0f;
                        float ttLeft = 0f;
                        float ttTop = 0f;
                        try {
                            Object widthObj = bBox.width();
                            Object heightObj = bBox.height();
                            Object leftObj = bBox.left();
                            Object topObj = bBox.top();
                            if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                                    !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                                log.info("Table: Invalid bBox property types in cell at row {} on page {}: expected Number", rowIndex, pageNumber);
                                throw new IllegalArgumentException("Table: bBox properties must be of type Number");
                            }
                            ttWidth = ((Number) widthObj).floatValue();
                            ttHeight = ((Number) heightObj).floatValue();
                            ttLeft = ((Number) leftObj).floatValue();
                            ttTop = ((Number) topObj).floatValue();
                        } catch (JSONException | NullPointerException e) {
                            log.info("Table: Missing bBox properties (Width, Height, Left, or Top) in cell at row {} on page {}: {}", rowIndex, pageNumber, e.getMessage());
                            throw new IllegalArgumentException("Table: bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                        }
                        float x = ttLeft * pageWidth;
                        float width = ttWidth * pageWidth;
                        float height = ttHeight * pageHeight;
                        float y = pageHeight - (pageHeight * ttTop) - height;
                        tagRole = cellType;
                        float bestFontSize = PdfFontUtils.calculateBestFitFontSize(arialMono, height);
                        tagPointer.addTag(tagRole);
                        TagReference tagRef = tagPointer.getTagReference();
                        pageCanvas.openTag(tagRef);
                        float textWidth = arialMono.getWidth(text, bestFontSize);
                        float textHeight = bestFontSize;
                        pageCanvas.beginText()
                                .setFontAndSize(arialMono, bestFontSize)
                                .setTextMatrix(width / textWidth, 0, 0, height / textHeight, x, y - arialMono.getAscent("Hello", bestFontSize) + textHeight)
                                .setExtGState(INVIS_FILL)
                                .showText(text)
                                .endText();
                        pageCanvas.closeTag();
                        tagPointer.moveToParent();
                    }
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    long tableEnd = System.currentTimeMillis();
                    long elapsedNanos = tableEnd - tableStart;
                    double elapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(elapsedNanos);
                    log.info("TIMING: Table tagging() took {} seconds", String.format("%.3f", elapsedSeconds));


                } else if (op instanceof FigureTagOP f) {
                    final long figureStart = System.currentTimeMillis();
                    addFigures(f.figures(), pageNumber, pageCanvas, pageWidth, pageHeight, tagPointer, sectIndex);
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    long figureEnd = System.currentTimeMillis();
                    long figureElapsedNanos = figureEnd - figureStart;
                    double figureElapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(figureElapsedNanos);
                    log.debug("TIMING: Figure tagging() took {} seconds", figureElapsedSeconds);
                } else if (op instanceof ListOp list) {
                    String tagType = list.role();
                    if ("LIST".equals(tagType)) {
                        tagType = "LI";
                        log.info("Converted LIST tag to LI for page {}", pageNumber);
                    }
                    if (!"LIST".equals(contentType)) {
//                        tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT)
//                                .addTag(StandardRoles.L);
                        tagPointer.moveToParent().moveToKid(sectIndex);
                        tagPointer.addTag(StandardRoles.L);
                    }
                    for (BulletOp bullet : list.bullets()) {
                        text = bullet.text();
                        if (text == null || text.isEmpty()) {
                            text = "";
                        }
                        BBox bBox = bullet.bBox();
                        float ttWidth = 0f;
                        float ttHeight = 0f;
                        float ttLeft = 0f;
                        float ttTop = 0f;
                        float ttAvgWordHeight = 0f;
                        try {
                            Number widthObj = bBox.width();
                            Number heightObj = bBox.height();
                            Number leftObj = bBox.left();
                            Number topObj = bBox.top();
                            if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                                    !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                                log.info("LIST: Invalid bBox property on page {}: expected Number", pageNumber);
                                throw new NullPointerException("LIST: Invalid bBox property on page :" + pageNumber);
                            }
                            ttWidth = widthObj.floatValue();
                            ttHeight = heightObj.floatValue();
                            ttLeft = leftObj.floatValue();
                            ttTop = topObj.floatValue();
                        } catch (JSONException | NullPointerException e) {
                            log.info("LIST: Missing bBox properties (Width, Height, Left, or Top) on page {}: {}", pageNumber, e.getMessage());
                            throw new NullPointerException("LIST: bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                        }
                        float x = ttLeft * pageWidth;
                        float width = (ttWidth * pageWidth);
                        float height = (ttHeight * pageHeight) + 5;
                        float y = pageHeight - (pageHeight * ttTop) - height;
                        tagRole = tagType;
                        float bestFontSize = PdfFontUtils.calculateBestFitFontSize(arialMono, height);
//                            TagReference tagRef = tagPointer.getTagReference(tagObjects.indexOf(tagPointer.addTag(tagRole)));
                        tagPointer.addTag(tagRole);
                        TagReference tagRef = tagPointer.getTagReference();
                        pageCanvas.openTag(tagRef);
                        float textWidth = arialMono.getWidth(text, bestFontSize);
                        float textHeight = bestFontSize;
                        int estimatedLines = (int) Math.ceil(textWidth / width);
                        float offsetX = 0;
                        float offsetY = 0;
                        if (PdfFontUtils.getStringWidth(text, arialMono, bestFontSize) > width) {
                            height = ((ttHeight * pageHeight) + (estimatedLines * 0.83f));
                            offsetY = (0.008f * pageHeight) + (estimatedLines * 0.4f);
                        } else {
                            offsetX = 0.001f * pageWidth;
                            offsetY = (0.003f * pageHeight) + (estimatedLines * 0.25f);
                        }
                        pageCanvas.beginText()
                                .setFontAndSize(arialMono, bestFontSize)
                                .setTextMatrix(width / textWidth, 0, 0, height / textHeight, x + offsetX, y + offsetY)
                                .setExtGState(INVIS_FILL)
                                .showText(text)
                                .endText();
                        pageCanvas.closeTag();
                        tagPointer.moveToParent();
                    }
                    //tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                } else if (op instanceof TociOp toc) {
                    long tocStart = System.currentTimeMillis();
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT).addTag(StandardRoles.TOC);
                    tagPointer.moveToParent().moveToKid(sectIndex);
                    tagPointer.addTag(StandardRoles.TOC);
                    List<TociItem> tocItems = toc.items();
                    log.debug("Processing {} TOC items", tocItems != null ? tocItems.size() : 0);
                    for (TociItem item : tocItems) {
                        text = item.text();
                        String font = item.font();
                        contentType = item.contentType();
                        log.debug("TOC item: text={}, font={}, contentType={}", text, font, contentType);
                        BBox bBox = item.box();
                        float ttWidth = 0f;
                        float ttHeight = 0f;
                        float ttLeft = 0f;
                        float ttTop = 0f;
                        float ttAvgWordHeight = 0f;
                        try {
                            Number widthObj = bBox.width();
                            Number heightObj = bBox.height();
                            Number leftObj = bBox.left();
                            Number topObj = bBox.top();
                            Number avgObj = bBox.avgWordHeight();
                            if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                                    !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                                log.info("TOCI: Invalid bBox property on page {}: expected Number", pageNumber);
                                throw new NullPointerException("TOCI: Invalid bBox property on page {}: expected Number");
                            }
                            ttWidth = widthObj.floatValue();
                            ttHeight = heightObj.floatValue();
                            ttLeft = leftObj.floatValue();
                            ttTop = topObj.floatValue();
                            ttAvgWordHeight = avgObj.floatValue();
                        } catch (JSONException | NullPointerException e) {
                            log.info("TOCI: Missing bBox properties (Width, Height, Left, or Top) on page {}: {}", pageNumber, e.getMessage());
                            throw new NullPointerException("TOCI: bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                        }
                        float x = ttLeft * pageWidth;
                        float y = (1 - ttTop - ttHeight) * pageHeight;
                        float width = (ttWidth * pageWidth) + 5;
                        float height = (ttHeight * pageHeight) + 1;
                        tagRole = StandardRoles.TOCI;
                        float bestFontSize = PdfFontUtils.calculateBestFitFontSize(arialMono, height);
                        tagPointer.addTag(tagRole);
                        TagReference tagRef = tagPointer.getTagReference();
                        pageCanvas.openTag(tagRef);
                        float textWidth = arialMono.getWidth(text, bestFontSize);
                        float textHeight = bestFontSize;
                        pageCanvas.beginText()
                                .setFontAndSize(arialMono, bestFontSize)
                                .setTextMatrix(width / textWidth, 0, 0, height / textHeight, x, y + (ttAvgWordHeight / 2) + 6)
                                .setExtGState(INVIS_FILL)
                                .showText(text)
                                .endText();
                        pageCanvas.closeTag();
                        tagPointer.moveToParent();
                    }
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    long tocEnd = System.currentTimeMillis();
                    long tocElapsedNanos = tocEnd - tocStart;
                    double tocElapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(tocElapsedNanos);
                    log.debug("TIMING: TOC tagging() took {} seconds", tocElapsedSeconds);

                } else if (op instanceof FormOp f) {
                    long formStart = System.currentTimeMillis();
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    List<FormLineOP> formLines = f.lines();
                    for (FormLineOP form : formLines) {
                        String id = form.id();
                        text = form.text();
                        if (text == null || text.isEmpty()) {
                            text = "";
                        }
                        contentType = form.contentType();
                        BBox bBox =  form.bBox();
                        float ttWidth = 0f;
                        float ttHeight = 0f;
                        float ttLeft = 0f;
                        float ttTop = 0f;
                        try {
                            Number widthObj = bBox.width();
                            Number heightObj = bBox.height();
                            Number leftObj = bBox.left();
                            Number topObj = bBox.top();
                            if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                                    !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                                log.info("{}: Invalid bBox property on page {}: expected Number", tagRole, 1);
                                throw new NullPointerException(tagRole + ": Invalid bBox property on page {}: expected Number");
                            }
                            ttWidth = widthObj.floatValue();
                            ttHeight = heightObj.floatValue();
                            ttLeft = leftObj.floatValue();
                            ttTop = topObj.floatValue();
                        } catch (JSONException | NullPointerException e) {
                            log.info("{}: Missing bBox properties (Width, Height, Left, or Top) on page {}: {}", tagRole, 1, e.getMessage());
                            throw new NullPointerException(tagRole + ": bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                        }
                        float x = ttLeft * pageWidth;
                        float width = ttWidth * pageWidth;
                        float height = ttHeight * pageHeight;
                        float y = (pageHeight - (pageHeight * ttTop) - height);
                        tagRole = StandardRoles.FORM;
                        float bestFontSize = PdfFontUtils.calculateBestFitFontSize(arialMono, height);
                        tagPointer.addTag(tagRole);
                        TagReference tagRef = tagPointer.getTagReference();
                        pageCanvas.openTag(tagRef);
                        float textWidth = arialMono.getWidth(text, bestFontSize);
                        float textHeight = bestFontSize;
                        pageCanvas.beginText()
                                .setFontAndSize(arialMono, bestFontSize)
                                .setTextMatrix(width / textWidth, 0, 0, height / textHeight, x, y - arialMono.getAscent("Hello", bestFontSize) + textHeight)
                                .setExtGState(INVIS_FILL)
                                .showText(text)
                                .endText();
                        pageCanvas.closeTag();

                        if (form.valueBox() != null) {
                            BBox value_bbox = form.valueBox();
                            float valueWidth = 0f;
                            float valueHeight = 0f;
                            float valueLeft = 0f;
                            float valueTop = 0f;

                            try {
                                Number widthObj1 = value_bbox.width();
                                Number heightObj1 = value_bbox.height();
                                Number leftObj1 = value_bbox.left();
                                Number topObj1 = value_bbox.top();

                                if (!(widthObj1 instanceof Number) || !(heightObj1 instanceof Number) ||
                                        !(leftObj1 instanceof Number) || !(topObj1 instanceof Number)) {
                                    log.info("{}: Invalid value_bbox property on page {}: expected Number", tagRole, 1);
                                    throw new NullPointerException(tagRole + ": Invalid value_bbox property on page {}: expected Number");
                                }
                                valueWidth = widthObj1.floatValue();
                                valueHeight = heightObj1.floatValue();
                                valueLeft = leftObj1.floatValue();
                                valueTop = topObj1.floatValue();
                            } catch (JSONException | NullPointerException e) {
                                log.info("{}: Missing value_bBox properties (Width, Height, Left, or Top) on page {}: {}", tagRole, 1, e.getMessage());
                                throw new NullPointerException(tagRole + ": value_bBox must contain 'Width', 'Height', 'Left', and 'Top'");
                            }

                            PdfCanvas pdfCanvas = new PdfCanvas(destPage);
                            pdfCanvas.openTag(tagRef);
                            pdfCanvas.setExtGState(INVIS_BOTH);
                            pdfCanvas.rectangle(new Rectangle(valueLeft, valueTop, valueWidth, valueHeight));
                            pdfCanvas.stroke();
                            pdfCanvas.closeTag();
                        }
                        tagPointer.moveToParent();
                    }
//                    tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
                    tagPointer.moveToRoot().moveToKid(sectIndex);
                    long formEnd = System.currentTimeMillis();
                    long formelapsedNanos = formEnd - formStart;
                    double formelapsedSeconds = TimeUnit.MILLISECONDS.toSeconds(formelapsedNanos);
                    log.debug("TIMING: Table tagging() took {} seconds", formelapsedSeconds);
                }
            }

        }
    }

    private void addFigures(List<FigureOp> figures, int pageNumber, PdfCanvas pageCanvas, float pageWidth, float pageHeight, TagTreePointer tagPointer, int sectIndex) {
        for (FigureOp f : figures) {
            BBox bBox = f.bBox();
            float ttWidth = 0f;
            float ttHeight = 0f;
            float ttLeft = 0f;
            float ttTop = 0f;
            try {
                Object widthObj = bBox.width();
                Object heightObj = bBox.height();
                Object leftObj = bBox.left();
                Object topObj = bBox.top();
                if (!(widthObj instanceof Number) || !(heightObj instanceof Number) ||
                        !(leftObj instanceof Number) || !(topObj instanceof Number)) {
                    log.info("Figure : Invalid bBox property on page {}: expected Number", pageNumber);
                    throw new NullPointerException("Figure : Invalid bBox property on page {}: expected Number");
                }
                ttWidth = ((Number) widthObj).floatValue();
                ttHeight = ((Number) heightObj).floatValue();
                ttLeft = ((Number) leftObj).floatValue();
                ttTop = ((Number) topObj).floatValue();
            } catch (JSONException | NullPointerException e) {
                log.info("Figure: Missing bBox properties (Width, Height, Left, or Top) on page {}: {}", pageNumber, e.getMessage());
                throw new NullPointerException("Figure: bBox must contain 'Width', 'Height', 'Left', and 'Top'");
            }
            float x = ttLeft * pageWidth;
            float width = ttWidth * pageWidth;
            float height = ttHeight * pageHeight;
            float y = pageHeight - (pageHeight * ttTop) - height;
//            tagPointer.moveToRoot().moveToKid(pageNumber - 1, StandardRoles.SECT);
            tagPointer.moveToParent().moveToKid(sectIndex);
            if (width <= 0 || height <= 0) {
                log.info("Figure: Invalid figure dimensions: width={}, height={}", width, height);
                throw new NullPointerException("Figure: Invalid figure dimensions: width= " + width + "height= " + height);
            }
            tagPointer.addTag(StandardRoles.FIGURE);
            tagPointer.getProperties().setAlternateDescription(f.text()); // preferred
            TagReference ref = tagPointer.getTagReference();
            pageCanvas.openTag(ref);
            pageCanvas.saveState();
            pageCanvas.setExtGState(INVIS_BOTH);
            pageCanvas.rectangle(new Rectangle(x, y, width, height));
            pageCanvas.fill();
            pageCanvas.restoreState();
            pageCanvas.closeTag();
            tagPointer.moveToParent();
        }
    }

    private int getSectIndex(TagTreePointer tp) {
        PdfStructElem created = pdf.getTagStructureContext().getPointerStructElem(tp);
        PdfStructElem parent  = (PdfStructElem) created.getParent();
        int idx = -1;
        var kids = parent.getKids();
        for (int i = 0; i < kids.size(); i++) {
            if (kids.get(i) instanceof PdfStructElem k &&
                    k.getPdfObject() == created.getPdfObject()) { // same indirect obj
                idx = i;
                break;
            }
        }
        return idx;
    }



}
