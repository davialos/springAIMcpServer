package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Package-level checks any spreadsheet application relies on: well-formed parts, complete content types and rels. */
public class XlsxWorkbookTest {

    @Test
    void packageIsCompleteAndWellFormed() throws Exception {
        XlsxWorkbook book = new XlsxWorkbook("t", Instant.parse("2026-10-01T00:00:00Z"),
                new XlsxStyles.Font("Calibri", 11, false, false, false, "000000"));
        int bold = book.styles().of(new XlsxStyles.Style(new XlsxStyles.Font("Calibri", 11, true, false, false,
                "000000"), "F3F2EE", XlsxStyles.Border.NONE, "#,##0", "left", "top", true, 0));
        XlsxSheet a = book.sheet("Dash & <board>");
        a.text(1, 1, "a < b & \"c\" \u0001", bold);
        a.number(2, 3, 1234.5, bold);
        a.merge(4, 1, 4, 3, bold);
        a.link(4, 1, "Data's", "go");
        a.dataBar(2, 3, 3, "86B6EF", 0, 100);
        XlsxSheet data = book.sheet("Data's");
        data.number(2, 1, 1, 0);
        data.number(3, 1, 2, 0);
        data.autoFilter(1, 1, 3, 2);
        data.freezeRows(1);
        a.chart(new XlsxChart.Scatter("s", 10.0, "x", "y", "0", "0", List.of(new XlsxChart.Series("n", "2A78D6",
                new XlsxChart.NumberRange("Data's", 1, 2, new double[]{1, 2}),
                new XlsxChart.NumberRange("Data's", 1, 2, new double[]{1, 2}), true, false)),
                new XlsxChart.Anchor(6, 1, 20, 8)));
        a.chart(new XlsxChart.Bar("b", "n", "2A78D6", new XlsxChart.TextRange("Data's", 2, 2, List.of("x", "y")),
                new XlsxChart.NumberRange("Data's", 1, 2, new double[]{1, 2}), "0.0", new XlsxChart.Anchor(6, 9, 20,
                16)));
        Map<String, byte[]> parts = unzip(book.toBytes());

        assertThat(parts).containsKeys("[Content_Types].xml", "_rels/.rels", "xl/workbook.xml", "xl/styles.xml",
                "xl/worksheets/sheet1.xml", "xl/worksheets/sheet2.xml", "xl/drawings/drawing1.xml",
                "xl/charts/chart1.xml", "xl/charts/chart2.xml", "docProps/core.xml");
        Map<String, Document> xml = new LinkedHashMap<>();
        for (Map.Entry<String, byte[]> e : parts.entrySet()) {
            xml.put(e.getKey(), parse(e.getValue()));
        }
        // Every override names an existing part.
        NodeList overrides = xml.get("[Content_Types].xml").getElementsByTagName("Override");
        for (int i = 0; i < overrides.getLength(); i++) {
            String part = ((Element) overrides.item(i)).getAttribute("PartName").substring(1);
            assertThat(parts).as("content type override for %s", part).containsKey(part);
        }
        // Every relationship target exists (relative to the source part's folder).
        for (String rels : parts.keySet().stream().filter(p -> p.endsWith(".rels")).toList()) {
            String folder = rels.replace("_rels/", "").replaceAll("[^/]*\\.rels$", "");
            NodeList list = xml.get(rels).getElementsByTagName("Relationship");
            for (int i = 0; i < list.getLength(); i++) {
                String target = ((Element) list.item(i)).getAttribute("Target");
                String resolved = java.nio.file.Path.of(folder.isEmpty() ? "." : folder).resolve(target).normalize()
                        .toString();
                assertThat(parts).as("%s → %s", rels, target).containsKey(resolved);
            }
        }
        String sheet1 = new String(parts.get("xl/worksheets/sheet1.xml"), StandardCharsets.UTF_8);
        assertThat(sheet1).contains("a &lt; b &amp; &quot;c&quot; ").doesNotContain("\u0001")
                .contains("<mergeCell ref=\"A4:C4\"/>").contains("location=\"&apos;Data&apos;&apos;s&apos;!A1\"")
                .contains("<drawing r:id=\"rId1\"/>");
        // Worksheet children must follow the schema order.
        assertThat(sheet1.indexOf("<sheetData>")).isLessThan(sheet1.indexOf("<mergeCells"));
        assertThat(sheet1.indexOf("<mergeCells")).isLessThan(sheet1.indexOf("<conditionalFormatting"));
        assertThat(sheet1.indexOf("<conditionalFormatting")).isLessThan(sheet1.indexOf("<hyperlinks>"));
        assertThat(sheet1.indexOf("<hyperlinks>")).isLessThan(sheet1.indexOf("<pageMargins"));
        assertThat(sheet1.indexOf("<pageMargins")).isLessThan(sheet1.indexOf("<drawing"));
        String workbook = new String(parts.get("xl/workbook.xml"), StandardCharsets.UTF_8);
        assertThat(workbook).contains("name=\"Dash &amp; &lt;board&gt;\"")
                .contains("<definedName name=\"_xlnm._FilterDatabase\" localSheetId=\"1\" hidden=\"1\">"
                        + "&apos;Data&apos;&apos;s&apos;!$A$1:$B$3</definedName>");
        String chart = new String(parts.get("xl/charts/chart1.xml"), StandardCharsets.UTF_8);
        assertThat(chart).contains("<c:f>&apos;Data&apos;&apos;s&apos;!$A$2:$A$3</c:f>").contains("<c:max val=\"10\"/>");
    }

    @Test
    void sameContentSameBytes() {
        XlsxWorkbook a = new XlsxWorkbook("t", Instant.EPOCH, new XlsxStyles.Font("Calibri", 11, false, false,
                false, "000000"));
        a.sheet("S").text(1, 1, "x", 0);
        XlsxWorkbook b = new XlsxWorkbook("t", Instant.EPOCH, new XlsxStyles.Font("Calibri", 11, false, false,
                false, "000000"));
        b.sheet("S").text(1, 1, "x", 0);
        assertThat(a.toBytes()).isEqualTo(b.toBytes());
    }

    @Test
    void referencesAndNames() {
        assertThat(Xml.column(1)).isEqualTo("A");
        assertThat(Xml.column(26)).isEqualTo("Z");
        assertThat(Xml.column(27)).isEqualTo("AA");
        assertThat(Xml.column(703)).isEqualTo("AAA");
        assertThat(Xml.num(3.0)).isEqualTo("3");
        assertThat(Xml.num(Double.NaN)).isEqualTo("0");
        assertThat(XlsxSheet.quote("It's")).isEqualTo("'It''s'");
        XlsxWorkbook book = new XlsxWorkbook("t", Instant.EPOCH, new XlsxStyles.Font("Calibri", 11, false, false,
                false, "000000"));
        assertThatThrownBy(() -> book.sheet("a/b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> book.sheet("x".repeat(32))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void stylesAreSharedAndCustomFormatsNumbered() {
        XlsxStyles styles = new XlsxStyles(new XlsxStyles.Font("Calibri", 11, false, false, false, "000000"));
        XlsxStyles.Style s = new XlsxStyles.Style(styles.defaultFont(), "FFFFFF", XlsxStyles.Border.NONE, "0.0",
                "left", "top", false, 0);
        assertThat(styles.of(s)).isEqualTo(styles.of(s)).isEqualTo(1);
        assertThat(styles.toXml()).contains("<numFmt numFmtId=\"164\" formatCode=\"0.0\"/>")
                .contains("<fills count=\"3\">");
    }

    /** @return part name → bytes */
    public static Map<String, byte[]> unzip(byte[] xlsx) throws IOException {
        Map<String, byte[]> parts = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(xlsx))) {
            ZipEntry e;
            while ((e = in.getNextEntry()) != null) {
                parts.put(e.getName(), in.readAllBytes());
            }
        }
        return parts;
    }

    private static Document parse(byte[] xml) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
    }
}
