package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * An .xlsx workbook: sheets, shared styles and document properties, packaged as an OPC zip. Entries carry a fixed
 * timestamp so the same content always produces the same bytes.
 */
public final class XlsxWorkbook {

    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2000, 1, 1, 0, 0);
    private static final String REL = "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships";

    private final List<XlsxSheet> sheets = new ArrayList<>();
    private final XlsxStyles styles;
    private final String title;
    private final Instant created;

    /**
     * @param title       document title (file properties)
     * @param created     creation time (file properties)
     * @param defaultFont font of the Normal style
     */
    public XlsxWorkbook(String title, Instant created, XlsxStyles.Font defaultFont) {
        this.title = title;
        this.created = created;
        this.styles = new XlsxStyles(defaultFont);
    }

    /** @return the style registry */
    public XlsxStyles styles() {
        return styles;
    }

    /**
     * @param name sheet name (at most 31 characters, no {@code []:*?/\})
     * @return the new sheet, added after the existing ones
     */
    public XlsxSheet sheet(String name) {
        XlsxSheet sheet = new XlsxSheet(name);
        sheets.add(sheet);
        return sheet;
    }

    /** @return the .xlsx bytes */
    public byte[] toBytes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256 * 1024);
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            List<String> overrides = new ArrayList<>();
            StringBuilder workbookRels = new StringBuilder();
            StringBuilder sheetList = new StringBuilder();
            StringBuilder definedNames = new StringBuilder();
            List<String[]> parts = new ArrayList<>();
            int chartNo = 0;
            int drawingNo = 0;
            for (int i = 0; i < sheets.size(); i++) {
                XlsxSheet sheet = sheets.get(i);
                int n = i + 1;
                String drawingRel = null;
                if (!sheet.charts().isEmpty()) {
                    drawingNo++;
                    drawingRel = "rId1";
                    parts.add(new String[]{"xl/worksheets/_rels/sheet" + n + ".xml.rels", rels(
                            "<Relationship Id=\"rId1\" Type=\"" + REL + "/drawing\" Target=\"../drawings/drawing"
                                    + drawingNo + ".xml\"/>")});
                    StringBuilder drawing = new StringBuilder();
                    StringBuilder drawingRels = new StringBuilder();
                    int local = 0;
                    for (XlsxChart chart : sheet.charts()) {
                        chartNo++;
                        local++;
                        parts.add(new String[]{"xl/charts/chart" + chartNo + ".xml",
                                ChartXml.render(chart, styles.defaultFont().name())});
                        overrides.add(override("/xl/charts/chart" + chartNo + ".xml",
                                "application/vnd.openxmlformats-officedocument.drawingml.chart+xml"));
                        drawingRels.append("<Relationship Id=\"rId").append(local).append("\" Type=\"").append(REL)
                                .append("/chart\" Target=\"../charts/chart").append(chartNo).append(".xml\"/>");
                        anchor(drawing, chart.anchor(), local);
                    }
                    parts.add(new String[]{"xl/drawings/drawing" + drawingNo + ".xml",
                            "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<xdr:wsDr xmlns:xdr=\""
                                    + "http://schemas.openxmlformats.org/drawingml/2006/spreadsheetDrawing\" xmlns:a=\""
                                    + "http://schemas.openxmlformats.org/drawingml/2006/main\" xmlns:r=\"" + REL
                                    + "\" xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\">"
                                    + drawing + "</xdr:wsDr>"});
                    parts.add(new String[]{"xl/drawings/_rels/drawing" + drawingNo + ".xml.rels",
                            rels(drawingRels.toString())});
                    overrides.add(override("/xl/drawings/drawing" + drawingNo + ".xml",
                            "application/vnd.openxmlformats-officedocument.drawing+xml"));
                }
                parts.add(new String[]{"xl/worksheets/sheet" + n + ".xml", sheet.toXml(i == 0, drawingRel)});
                overrides.add(override("/xl/worksheets/sheet" + n + ".xml",
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"));
                workbookRels.append("<Relationship Id=\"rId").append(n).append("\" Type=\"").append(REL)
                        .append("/worksheet\" Target=\"worksheets/sheet").append(n).append(".xml\"/>");
                sheetList.append("<sheet name=\"").append(Xml.esc(sheet.name())).append("\" sheetId=\"").append(n)
                        .append("\" r:id=\"rId").append(n).append("\"/>");
                String filter = sheet.autoFilterRange();
                if (filter != null) {
                    String[] ends = filter.split(":");
                    definedNames.append("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"").append(i)
                            .append("\" hidden=\"1\">").append(Xml.esc(XlsxSheet.quote(sheet.name()))).append('!')
                            .append(absolute(ends[0])).append(':').append(absolute(ends[1])).append("</definedName>");
                }
            }
            int stylesId = sheets.size() + 1;
            workbookRels.append("<Relationship Id=\"rId").append(stylesId).append("\" Type=\"").append(REL)
                    .append("/styles\" Target=\"styles.xml\"/>");

            put(zip, "[Content_Types].xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                    + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                    + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                    + override("/xl/workbook.xml",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml")
                    + override("/xl/styles.xml", "application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml")
                    + override("/docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")
                    + override("/docProps/app.xml",
                    "application/vnd.openxmlformats-officedocument.extended-properties+xml")
                    + String.join("", overrides) + "</Types>");
            put(zip, "_rels/.rels", rels("<Relationship Id=\"rId1\" Type=\"" + REL
                    + "/officeDocument\" Target=\"xl/workbook.xml\"/><Relationship Id=\"rId2\" Type=\"" + PKG_REL
                    + "/metadata/core-properties\" Target=\"docProps/core.xml\"/><Relationship Id=\"rId3\" Type=\""
                    + REL + "/extended-properties\" Target=\"docProps/app.xml\"/>"));
            put(zip, "docProps/core.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\""
                    + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\""
                    + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"><dc:title>" + Xml.esc(title)
                    + "</dc:title><dc:creator>JFR Analyzer</dc:creator><dcterms:created xsi:type=\"dcterms:W3CDTF\">"
                    + created + "</dcterms:created></cp:coreProperties>");
            put(zip, "docProps/app.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\">"
                    + "<Application>JFR Analyzer</Application></Properties>");
            put(zip, "xl/workbook.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
                    + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"" + REL
                    + "\"><bookViews><workbookView activeTab=\"0\"/></bookViews><sheets>" + sheetList + "</sheets>"
                    + (definedNames.isEmpty() ? "" : "<definedNames>" + definedNames + "</definedNames>")
                    + "</workbook>");
            put(zip, "xl/_rels/workbook.xml.rels", rels(workbookRels.toString()));
            put(zip, "xl/styles.xml", styles.toXml());
            for (String[] part : parts) {
                put(zip, part[0], part[1]);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static void anchor(StringBuilder x, XlsxChart.Anchor a, int id) {
        x.append("<xdr:twoCellAnchor editAs=\"oneCell\"><xdr:from><xdr:col>").append(a.fromColumn() - 1)
                .append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(a.fromRow() - 1)
                .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from><xdr:to><xdr:col>").append(a.toColumn() - 1)
                .append("</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>").append(a.toRow() - 1)
                .append("</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to><xdr:graphicFrame macro=\"\">")
                .append("<xdr:nvGraphicFramePr><xdr:cNvPr id=\"").append(id + 1).append("\" name=\"Chart ").append(id)
                .append("\"/><xdr:cNvGraphicFramePr/></xdr:nvGraphicFramePr><xdr:xfrm><a:off x=\"0\" y=\"0\"/>")
                .append("<a:ext cx=\"0\" cy=\"0\"/></xdr:xfrm><a:graphic><a:graphicData uri=\"")
                .append("http://schemas.openxmlformats.org/drawingml/2006/chart\"><c:chart r:id=\"rId").append(id)
                .append("\"/></a:graphicData></a:graphic></xdr:graphicFrame><xdr:clientData/></xdr:twoCellAnchor>");
    }

    private static String absolute(String ref) {
        int digit = 0;
        while (digit < ref.length() && !Character.isDigit(ref.charAt(digit))) {
            digit++;
        }
        return "$" + ref.substring(0, digit) + "$" + ref.substring(digit);
    }

    private static String rels(String body) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<Relationships xmlns=\"" + PKG_REL
                + "\">" + body + "</Relationships>";
    }

    private static String override(String part, String type) {
        return "<Override PartName=\"" + part + "\" ContentType=\"" + type + "\"/>";
    }

    private static void put(ZipOutputStream zip, String name, String content) throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTimeLocal(ENTRY_TIME);
        zip.putNextEntry(entry);
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
