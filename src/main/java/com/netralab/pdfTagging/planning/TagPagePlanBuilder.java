package com.netralab.pdfTagging.planning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.netralab.pdfTagging.planning.domain.*;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

import static com.netralab.pdfTagging.planning.domain.BBox.toBBox;
import static com.netralab.pdfTagging.planning.domain.CellOp.str;

public class TagPagePlanBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public List<PagePlan> build(JSONArray jsonArray, Function<String, Integer> pageNumberExtractor) {
        int nThreads = Math.min(jsonArray.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(nThreads);
        try {
            List<CompletableFuture<PagePlan>> futures = new ArrayList<>(jsonArray.size());

            for (Object obj : jsonArray) {
                JSONObject pageJson = (JSONObject) obj;
                futures.add(CompletableFuture.supplyAsync(() -> parsePage(pageJson), pool));
            }
            List<PagePlan> plans = futures.stream()
                    .map(CompletableFuture::join)
                    .sorted(Comparator.comparingInt(PagePlan::pageNumber))
                    .collect(java.util.stream.Collectors.toList());
            return plans;
        } finally {
            pool.shutdown();
        }
    }

    // ------- one page ----------
    private PagePlan parsePage(JSONObject pageJson) {
        Number pn = (Number) pageJson.get("Page");
        int pageNumber = (pn != null) ? pn.intValue() : 1;

        JSONArray tagObjects = (JSONArray) pageJson.get("TagObjects");
        List<Op> ops = new ArrayList<>();
        if (tagObjects != null) {
            for (Object tobj : tagObjects) {
                JSONObject tag = (JSONObject) tobj;
                String tagType = (String) tag.get("tag");
                if (tagType == null || tagType.isEmpty()) {
                    throw new IllegalArgumentException("Missing tag for page " + pageNumber);
                }

                switch (tagType) {

                    // Text-like roles
                    case "H1":
                    case "H2":
                    case "H3":
                    case "H4":
                    case "H5":
                    case "H6":
                    case "P":
                    case "Formula":
                    case "Link":
                    case "Note":
                    case "Caption":
                    case "BlockQuote": {
                        List<LineOp> linesOp = new ArrayList<>();
                        JSONArray lines = (JSONArray) tag.get("lines");
                        requireNonEmpty(lines, tagType + ": lines[] required on page " + pageNumber);
                        for (Object lineObj : lines) {
                            JSONObject line = (JSONObject) lineObj;
                            String rawText = String.valueOf(line.getOrDefault("text", ""));
                            String text = jsonSafe(rawText);
                            String mathMl = String.valueOf(line.getOrDefault("MathML", ""));
                            BBox box = toBBox((JSONObject) line.get("bBox"), tagType, true);
                            linesOp.add(new LineOp(text, box, mathMl.isEmpty() ? null : mathMl));
                        }
                        ops.add(new TextOp(linesOp, tagType));
                        break;
                    }
                    case "Form": {
                        JSONArray lines = (JSONArray) tag.get("lines");
                        requireNonEmpty(lines, "Form: lines[] required on page " + pageNumber);
                        List<FormLineOP> formLines = new ArrayList<>();
                        String tagRole = "Form";
                        for (Object formObj : lines) {
                            JSONObject form = (JSONObject) formObj;
                            String id = str(form, "Id", true);
                            String text = jsonSafe(String.valueOf(form.getOrDefault("text", "")));
                            String contentType = str(form, "contentType", false);
                            BBox labelBox = toBBox((JSONObject) form.get("bBox"), tagRole, true);
                            BBox valueBox = form.containsKey("value_bBox") ? toBBox((JSONObject) form.get("value_bBox"), tagRole, true) : null;
                            formLines.add(new FormLineOP(id, text, contentType, labelBox, valueBox));
                        }
                        ops.add(new FormOp(tagRole,formLines));
                        break;
                    }
                    case "Table":
                    case "TH":
                    case "TD": {

                        JSONArray cells = (JSONArray) tag.get("cells");
                        requireNonEmpty(cells, "Table: cells[] required on page " + pageNumber);
                        List<CellOp> cellsOp = new ArrayList<>();
                        for (Object cellObj : cells) {
                            JSONObject cell = (JSONObject) cellObj;
                            String text = jsonSafe(String.valueOf(cell.getOrDefault("text", "")));
                            String cellType = str(cell, "cellType", true); // "TH" or "TD"
                            int rowIndex = ((Number) cell.get("RowIndex")).intValue();
                            int colIndex = ((Number) cell.get("ColumnIndex")).intValue();
                            int rowSpan = ((Number) cell.get("RowSpan")).intValue();
                            int columnSpan = ((Number) cell.get("ColumnSpan")).intValue();
                            BBox box = toBBox((JSONObject) cell.get("bBox"), tagType, true);
                            cellsOp.add(new CellOp(text, cellType, box, rowIndex, colIndex, rowSpan, columnSpan));
                        }
                        ops.add(new TableOp(cellsOp, tagType));
                        break;
                    }
                    case "LI":
                    case "LIST": {
                        JSONArray bullets = (JSONArray) tag.get("bullets");
                        requireNonEmpty(bullets, "LIST: bullets[] required on page " + pageNumber);
                        String tagRole = tagType.equals("LI") ? "LI" : "LIST";
                        List<BulletOp> bulletsOp = new ArrayList<>();
                        for (Object bulletObj : bullets) {
                            JSONObject bullet = (JSONObject) bulletObj;
                            String text = jsonSafe(String.valueOf(bullet.getOrDefault("text", "")));
                            String contentType = str(bullet, "contentType", true);
                            long indentLevel = ((Number) bullet.getOrDefault("indentLevel", 0)).longValue();
                            BBox box = toBBox((JSONObject) bullet.get("bBox"), tagRole, true);
                            bulletsOp.add(new BulletOp(text,contentType, box, indentLevel));
                        }
                        ops.add(new ListOp(tagRole, bulletsOp, toBBox((JSONObject) tag.get("parent_coor"), tagRole, true)));
                        break;
                    }
                    case "TOCI": {
                        JSONArray items = (JSONArray) tag.get("tocItems");
                        requireNonEmpty(items, "TOCI: tocItems[] required on page " + pageNumber);
                        String tagRole = "TOCI";
                        List<TociItem> tociItems = new ArrayList<>();
                        for (Object itemObj : items) {
                            JSONObject item = (JSONObject) itemObj;
                            String text = jsonSafe(String.valueOf(item.getOrDefault("text", "")));
                            String contentType = str(item, "contentType", true);
                            String font = str(item, "font", false);
                            BBox box = toBBox((JSONObject) item.get("bBox"), tagRole, true);
                            tociItems.add(new TociItem(text, contentType, font,box));
                        }
                        ops.add(new TociOp(tagRole,tociItems));
                        break;
                    }
                    case "Figure": {
                        JSONArray figs = (JSONArray) tag.get("Figure");
                        BBox parentCoor = toBBox((JSONObject) tag.get("parent_coor"), "Figure", false);
                        List<FigureOp> figures = new ArrayList<>();
                        requireNonEmpty(figs, "Figure: Figure[] required on page " + pageNumber);
                        for (Object figObj : figs) {
                            JSONObject fig = (JSONObject) figObj;
                            String text = jsonSafe(String.valueOf(fig.getOrDefault("text", "")));
                            BBox box = toBBox((JSONObject) fig.get("bBox"), "Figure", true);
                            String contentType = str(fig, "contentType", false);
                            figures.add(new FigureOp(text, contentType, box));
                        }
                        FigureTagOP figure = new FigureTagOP(figures, parentCoor);
                        ops.add(figure);
                        break;
                    }
                    default:
                        // ignore or log as needed
                }
            }
        }

        return new PagePlan(pageNumber, List.copyOf(ops));
    }

    private static void requireNonEmpty(JSONArray arr, String msg) {
        if (arr == null || arr.isEmpty()) throw new IllegalArgumentException(msg);
    }

    private static String jsonSafe(String s) {
        try {
            return MAPPER.writeValueAsString(s);
        } catch (Exception e) {
            throw new RuntimeException("JSON encode failed", e);
        }
    }

}
