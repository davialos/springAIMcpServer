package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One worksheet. Rows and columns are 1-based. */
public final class XlsxSheet {

    /** Excel's limit on characters in one cell. */
    static final int MAX_CELL_CHARS = 32_767;

    private record Cell(@Nullable String text, double number, int style) {
    }

    private record DataBar(String range, String color, double min, double max) {
    }

    private record Link(String ref, String location, String tooltip) {
    }

    private final String name;
    private final TreeMap<Integer, TreeMap<Integer, Cell>> rows = new TreeMap<>();
    private final TreeMap<Integer, Double> widths = new TreeMap<>();
    private final TreeMap<Integer, Double> heights = new TreeMap<>();
    private final List<String> merges = new ArrayList<>();
    private final List<DataBar> dataBars = new ArrayList<>();
    private final List<Link> links = new ArrayList<>();
    private final List<XlsxChart> charts = new ArrayList<>();
    private boolean gridLines = true;
    private int frozenRows;
    private @Nullable String autoFilter;
    private @Nullable String tabColor;
    private boolean landscape;

    XlsxSheet(String name) {
        if (name.isEmpty() || name.length() > 31 || name.matches(".*[\\[\\]:*?/\\\\].*")) {
            throw new IllegalArgumentException("Invalid sheet name: " + name);
        }
        this.name = name;
    }

    /** @return the sheet name */
    public String name() {
        return name;
    }

    /**
     * @param sheet a sheet name
     * @return the name quoted for formulas, e.g. {@code 'Chart data'}
     */
    public static String quote(String sheet) {
        return "'" + sheet.replace("'", "''") + "'";
    }

    /**
     * @param row    row
     * @param column column
     * @param text   text
     * @param style  style index
     */
    public void text(int row, int column, String text, int style) {
        String t = text.length() > MAX_CELL_CHARS ? text.substring(0, MAX_CELL_CHARS) : text;
        rows.computeIfAbsent(row, r -> new TreeMap<>()).put(column, new Cell(t, 0, style));
    }

    /**
     * @param row    row
     * @param column column
     * @param value  number; non-finite values are written as an empty styled cell
     * @param style  style index
     */
    public void number(int row, int column, double value, int style) {
        if (!Double.isFinite(value)) {
            blank(row, column, style);
            return;
        }
        rows.computeIfAbsent(row, r -> new TreeMap<>()).put(column, new Cell(null, value, style));
    }

    /**
     * Styles a cell without a value (used for fills and borders), keeping any existing value.
     *
     * @param row    row
     * @param column column
     * @param style  style index
     */
    public void blank(int row, int column, int style) {
        rows.computeIfAbsent(row, r -> new TreeMap<>()).putIfAbsent(column, new Cell(null, Double.NaN, style));
    }

    /**
     * Merges a range and gives every cell in it the style (Excel draws fills and borders per cell).
     *
     * @param row1   first row
     * @param col1   first column
     * @param row2   last row
     * @param col2   last column
     * @param style  style index
     */
    public void merge(int row1, int col1, int row2, int col2, int style) {
        for (int r = row1; r <= row2; r++) {
            for (int c = col1; c <= col2; c++) {
                blank(r, c, style);
            }
        }
        if (row1 != row2 || col1 != col2) {
            merges.add(Xml.ref(row1, col1) + ":" + Xml.ref(row2, col2));
        }
    }

    /**
     * @param column column
     * @param width  width in characters
     */
    public void width(int column, double width) {
        widths.put(column, width);
    }

    /**
     * @param row    row
     * @param height height in points
     */
    public void height(int row, double height) {
        heights.put(row, height);
    }

    /** Hides the grid (dashboard look). */
    public void hideGridLines() {
        gridLines = false;
    }

    /** @param count rows to keep visible when scrolling */
    public void freezeRows(int count) {
        frozenRows = count;
    }

    /**
     * @param row1 header row
     * @param col1 first column
     * @param row2 last row
     * @param col2 last column
     */
    public void autoFilter(int row1, int col1, int row2, int col2) {
        autoFilter = Xml.ref(row1, col1) + ":" + Xml.ref(Math.max(row1, row2), col2);
    }

    /** @return the autofilter range, or {@code null} */
    @Nullable String autoFilterRange() {
        return autoFilter;
    }

    /**
     * Shows each value of a column range as an in-cell bar.
     *
     * @param row1   first row
     * @param row2   last row
     * @param column column
     * @param color  RGB hex
     * @param min    value of an empty bar
     * @param max    value of a full bar
     */
    public void dataBar(int row1, int row2, int column, String color, double min, double max) {
        if (row2 >= row1) {
            dataBars.add(new DataBar(Xml.ref(row1, column) + ":" + Xml.ref(row2, column), color, min, max));
        }
    }

    /**
     * Links a cell to a place in the workbook.
     *
     * @param row     row
     * @param column  column
     * @param sheet   target sheet
     * @param tooltip tooltip
     */
    public void link(int row, int column, String sheet, String tooltip) {
        links.add(new Link(Xml.ref(row, column), quote(sheet) + "!A1", tooltip));
    }

    /** @param chart a chart to draw over the sheet */
    public void chart(XlsxChart chart) {
        charts.add(chart);
    }

    /** @return the charts */
    List<XlsxChart> charts() {
        return charts;
    }

    /** @param rgb tab color */
    public void tabColor(String rgb) {
        tabColor = rgb;
    }

    /** Prints landscape, fitted to the page width. */
    public void landscape() {
        landscape = true;
    }

    String toXml(boolean selected, @Nullable String drawingRelId) {
        StringBuilder x = new StringBuilder(64 * 1024);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"")
                .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<sheetPr>");
        if (tabColor != null) {
            x.append("<tabColor rgb=\"FF").append(tabColor).append("\"/>");
        }
        x.append("<pageSetUpPr fitToPage=\"1\"/></sheetPr>");
        int lastRow = Math.max(1, Math.max(rows.isEmpty() ? 1 : rows.lastKey(), heights.isEmpty() ? 1
                : heights.lastKey()));
        int lastCol = rows.values().stream().filter(r -> !r.isEmpty()).mapToInt(TreeMap::lastKey).max().orElse(1);
        x.append("<dimension ref=\"A1:").append(Xml.ref(lastRow, lastCol)).append("\"/>")
                .append("<sheetViews><sheetView workbookViewId=\"0\"");
        if (!gridLines) {
            x.append(" showGridLines=\"0\"");
        }
        if (selected) {
            x.append(" tabSelected=\"1\"");
        }
        x.append('>');
        if (frozenRows > 0) {
            String topLeft = Xml.ref(frozenRows + 1, 1);
            x.append("<pane ySplit=\"").append(frozenRows).append("\" topLeftCell=\"").append(topLeft)
                    .append("\" activePane=\"bottomLeft\" state=\"frozen\"/><selection pane=\"bottomLeft\" activeCell=\"")
                    .append(topLeft).append("\" sqref=\"").append(topLeft).append("\"/>");
        }
        x.append("</sheetView></sheetViews><sheetFormatPr defaultRowHeight=\"15\"/>");
        if (!widths.isEmpty()) {
            x.append("<cols>");
            widths.forEach((c, w) -> x.append("<col min=\"").append(c).append("\" max=\"").append(c)
                    .append("\" width=\"").append(Xml.num(w)).append("\" customWidth=\"1\"/>"));
            x.append("</cols>");
        }
        x.append("<sheetData>");
        TreeMap<Integer, Object> allRows = new TreeMap<>();
        rows.keySet().forEach(r -> allRows.put(r, Boolean.TRUE));
        heights.keySet().forEach(r -> allRows.put(r, Boolean.TRUE));
        for (Integer r : allRows.keySet()) {
            x.append("<row r=\"").append(r).append('"');
            Double h = heights.get(r);
            if (h != null) {
                x.append(" ht=\"").append(Xml.num(h)).append("\" customHeight=\"1\"");
            }
            x.append('>');
            Map<Integer, Cell> cells = rows.getOrDefault(r, new TreeMap<>());
            for (Map.Entry<Integer, Cell> e : cells.entrySet()) {
                Cell cell = e.getValue();
                String ref = Xml.ref(r, e.getKey());
                x.append("<c r=\"").append(ref).append('"');
                if (cell.style() != 0) {
                    x.append(" s=\"").append(cell.style()).append('"');
                }
                if (cell.text() != null) {
                    x.append(" t=\"inlineStr\"><is><t xml:space=\"preserve\">").append(Xml.esc(cell.text()))
                            .append("</t></is></c>");
                } else if (Double.isNaN(cell.number())) {
                    x.append("/>");
                } else {
                    x.append("><v>").append(Xml.num(cell.number())).append("</v></c>");
                }
            }
            x.append("</row>");
        }
        x.append("</sheetData>");
        if (autoFilter != null) {
            x.append("<autoFilter ref=\"").append(autoFilter).append("\"/>");
        }
        if (!merges.isEmpty()) {
            x.append("<mergeCells count=\"").append(merges.size()).append("\">");
            merges.forEach(m -> x.append("<mergeCell ref=\"").append(m).append("\"/>"));
            x.append("</mergeCells>");
        }
        int priority = 1;
        for (DataBar bar : dataBars) {
            x.append("<conditionalFormatting sqref=\"").append(bar.range()).append("\"><cfRule type=\"dataBar\"")
                    .append(" priority=\"").append(priority++).append("\"><dataBar><cfvo type=\"num\" val=\"")
                    .append(Xml.num(bar.min())).append("\"/><cfvo type=\"num\" val=\"").append(Xml.num(bar.max()))
                    .append("\"/><color rgb=\"FF").append(bar.color()).append("\"/></dataBar></cfRule>")
                    .append("</conditionalFormatting>");
        }
        if (!links.isEmpty()) {
            x.append("<hyperlinks>");
            links.forEach(l -> x.append("<hyperlink ref=\"").append(l.ref()).append("\" location=\"")
                    .append(Xml.esc(l.location())).append("\" tooltip=\"").append(Xml.esc(l.tooltip()))
                    .append("\" display=\"").append(Xml.esc(l.tooltip())).append("\"/>"));
            x.append("</hyperlinks>");
        }
        x.append("<pageMargins left=\"0.4\" right=\"0.4\" top=\"0.5\" bottom=\"0.5\" header=\"0.3\" footer=\"0.3\"/>")
                .append("<pageSetup paperSize=\"9\" orientation=\"").append(landscape ? "landscape" : "portrait")
                .append("\" fitToWidth=\"1\" fitToHeight=\"0\"/>");
        if (drawingRelId != null) {
            x.append("<drawing r:id=\"").append(drawingRelId).append("\"/>");
        }
        x.append("</worksheet>");
        return x.toString();
    }
}
