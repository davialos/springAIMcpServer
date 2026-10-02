package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import org.jspecify.annotations.Nullable;

/** Renders {@link XlsxChart}s as DrawingML chart parts. */
final class ChartXml {

    private static final String AXIS_COLOR = "C3C2B7";
    private static final String GRID_COLOR = "E1E0D9";
    private static final String INK = "0B0B0B";
    private static final String INK_2 = "52514E";

    private ChartXml() {
    }

    static String render(XlsxChart chart, String font) {
        StringBuilder x = new StringBuilder(8192);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<c:chartSpace xmlns:c=\"http://schemas.openxmlformats.org/drawingml/2006/chart\"")
                .append(" xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"")
                .append(" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")
                .append("<c:roundedCorners val=\"0\"/><c:chart>");
        title(x, chart.title(), 1200, true, INK);
        x.append("<c:autoTitleDeleted val=\"0\"/><c:plotArea><c:layout/>");
        switch (chart) {
            case XlsxChart.Scatter s -> scatter(x, s);
            case XlsxChart.Bar b -> bar(x, b);
        }
        x.append("<c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr></c:plotArea>");
        if (chart instanceof XlsxChart.Scatter s && s.series().size() > 1) {
            x.append("<c:legend><c:legendPos val=\"t\"/><c:overlay val=\"0\"/></c:legend>");
        }
        x.append("<c:plotVisOnly val=\"1\"/><c:dispBlanksAs val=\"gap\"/></c:chart>")
                .append("<c:spPr><a:solidFill><a:srgbClr val=\"FFFFFF\"/></a:solidFill><a:ln><a:noFill/></a:ln>")
                .append("</c:spPr>");
        textProperties(x, 900, false, INK_2, font);
        x.append("</c:chartSpace>");
        return x.toString();
    }

    private static void scatter(StringBuilder x, XlsxChart.Scatter s) {
        x.append("<c:scatterChart><c:scatterStyle val=\"lineMarker\"/><c:varyColors val=\"0\"/>");
        int i = 0;
        for (XlsxChart.Series ser : s.series()) {
            x.append("<c:ser><c:idx val=\"").append(i).append("\"/><c:order val=\"").append(i)
                    .append("\"/><c:tx><c:v>").append(Xml.esc(ser.name())).append("</c:v></c:tx><c:spPr>");
            if (ser.line()) {
                x.append("<a:ln w=\"25400\" cap=\"rnd\"><a:solidFill><a:srgbClr val=\"").append(ser.color())
                        .append("\"/></a:solidFill><a:round/></a:ln>");
            } else {
                x.append("<a:ln w=\"25400\"><a:noFill/></a:ln>");
            }
            x.append("</c:spPr><c:marker>");
            if (ser.markers()) {
                x.append("<c:symbol val=\"circle\"/><c:size val=\"6\"/><c:spPr><a:solidFill><a:srgbClr val=\"")
                        .append(ser.color()).append("\"/></a:solidFill><a:ln w=\"12700\"><a:solidFill>")
                        .append("<a:srgbClr val=\"FFFFFF\"/></a:solidFill></a:ln></c:spPr>");
            } else {
                x.append("<c:symbol val=\"none\"/>");
            }
            x.append("</c:marker><c:xVal>");
            numRef(x, ser.x(), "General");
            x.append("</c:xVal><c:yVal>");
            numRef(x, ser.y(), "General");
            x.append("</c:yVal><c:smooth val=\"0\"/></c:ser>");
            i++;
        }
        x.append("<c:axId val=\"500000001\"/><c:axId val=\"500000002\"/></c:scatterChart>");
        valueAxis(x, 500000001, 500000002, "b", s.xTitle(), s.xFormat(), false, s.xMax());
        valueAxis(x, 500000002, 500000001, "l", s.yTitle(), s.yFormat(), true, null);
    }

    private static void bar(StringBuilder x, XlsxChart.Bar b) {
        x.append("<c:barChart><c:barDir val=\"bar\"/><c:grouping val=\"clustered\"/><c:varyColors val=\"0\"/>")
                .append("<c:ser><c:idx val=\"0\"/><c:order val=\"0\"/><c:tx><c:v>").append(Xml.esc(b.seriesName()))
                .append("</c:v></c:tx><c:spPr><a:solidFill><a:srgbClr val=\"").append(b.color())
                .append("\"/></a:solidFill></c:spPr><c:invertIfNegative val=\"0\"/>")
                .append("<c:dLbls><c:numFmt formatCode=\"").append(Xml.esc(b.valueFormat()))
                .append("\" sourceLinked=\"0\"/><c:spPr><a:noFill/><a:ln><a:noFill/></a:ln></c:spPr>")
                .append("<c:dLblPos val=\"outEnd\"/><c:showLegendKey val=\"0\"/><c:showVal val=\"1\"/>")
                .append("<c:showCatName val=\"0\"/><c:showSerName val=\"0\"/><c:showPercent val=\"0\"/>")
                .append("<c:showBubbleSize val=\"0\"/></c:dLbls><c:cat><c:strRef><c:f>")
                .append(Xml.esc(b.categories().formula())).append("</c:f><c:strCache><c:ptCount val=\"")
                .append(b.categories().labels().size()).append("\"/>");
        for (int i = 0; i < b.categories().labels().size(); i++) {
            x.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(Xml.esc(b.categories().labels().get(i)))
                    .append("</c:v></c:pt>");
        }
        x.append("</c:strCache></c:strRef></c:cat><c:val>");
        numRef(x, b.values(), "General");
        x.append("</c:val></c:ser><c:gapWidth val=\"60\"/><c:axId val=\"500000003\"/><c:axId val=\"500000004\"/>")
                .append("</c:barChart>")
                .append("<c:catAx><c:axId val=\"500000003\"/><c:scaling><c:orientation val=\"maxMin\"/></c:scaling>")
                .append("<c:delete val=\"0\"/><c:axPos val=\"l\"/><c:numFmt formatCode=\"General\" sourceLinked=\"0\"/>")
                .append("<c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>");
        line(x, AXIS_COLOR);
        x.append("<c:crossAx val=\"500000004\"/><c:crosses val=\"autoZero\"/><c:auto val=\"1\"/>")
                .append("<c:lblAlgn val=\"ctr\"/><c:lblOffset val=\"100\"/><c:noMultiLvlLbl val=\"0\"/></c:catAx>");
        // The value axis is hidden: every bar carries its own label.
        x.append("<c:valAx><c:axId val=\"500000004\"/><c:scaling><c:orientation val=\"minMax\"/><c:min val=\"0\"/>")
                .append("</c:scaling><c:delete val=\"1\"/><c:axPos val=\"b\"/><c:numFmt formatCode=\"")
                .append(Xml.esc(b.valueFormat())).append("\" sourceLinked=\"0\"/><c:majorTickMark val=\"none\"/>")
                .append("<c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/><c:crossAx val=\"500000003\"/>")
                .append("<c:crosses val=\"max\"/><c:crossBetween val=\"between\"/></c:valAx>");
    }

    private static void valueAxis(StringBuilder x, long id, long cross, String position, String title, String format,
                                  boolean gridlines, @Nullable Double max) {
        x.append("<c:valAx><c:axId val=\"").append(id).append("\"/><c:scaling><c:orientation val=\"minMax\"/>");
        if (max != null && max > 0) {
            x.append("<c:max val=\"").append(Xml.num(max)).append("\"/>");
        }
        x.append("<c:min val=\"0\"/></c:scaling><c:delete val=\"0\"/><c:axPos val=\"")
                .append(position).append("\"/>");
        if (gridlines) {
            x.append("<c:majorGridlines>");
            line(x, GRID_COLOR);
            x.append("</c:majorGridlines>");
        }
        if (!title.isEmpty()) {
            title(x, title, 900, false, INK_2);
        }
        x.append("<c:numFmt formatCode=\"").append(Xml.esc(format)).append("\" sourceLinked=\"0\"/>")
                .append("<c:majorTickMark val=\"none\"/><c:minorTickMark val=\"none\"/><c:tickLblPos val=\"nextTo\"/>");
        if (gridlines) {
            x.append("<c:spPr><a:ln><a:noFill/></a:ln></c:spPr>");
        } else {
            line(x, AXIS_COLOR);
        }
        x.append("<c:crossAx val=\"").append(cross).append("\"/><c:crosses val=\"autoZero\"/>")
                .append("<c:crossBetween val=\"midCat\"/></c:valAx>");
    }

    private static void numRef(StringBuilder x, XlsxChart.NumberRange r, String format) {
        x.append("<c:numRef><c:f>").append(Xml.esc(r.formula())).append("</c:f><c:numCache><c:formatCode>")
                .append(format).append("</c:formatCode><c:ptCount val=\"").append(r.values().length).append("\"/>");
        for (int i = 0; i < r.values().length; i++) {
            x.append("<c:pt idx=\"").append(i).append("\"><c:v>").append(Xml.num(r.values()[i]))
                    .append("</c:v></c:pt>");
        }
        x.append("</c:numCache></c:numRef>");
    }

    private static void line(StringBuilder x, String color) {
        x.append("<c:spPr><a:ln w=\"9525\"><a:solidFill><a:srgbClr val=\"").append(color)
                .append("\"/></a:solidFill></a:ln></c:spPr>");
    }

    private static void title(StringBuilder x, String text, int size, boolean bold, String color) {
        x.append("<c:title><c:tx><c:rich><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"").append(size)
                .append("\" b=\"").append(bold ? 1 : 0).append("\"/></a:pPr><a:r><a:rPr lang=\"en-US\" sz=\"")
                .append(size).append("\" b=\"").append(bold ? 1 : 0).append("\"><a:solidFill><a:srgbClr val=\"")
                .append(color).append("\"/></a:solidFill></a:rPr><a:t>").append(Xml.esc(text))
                .append("</a:t></a:r></a:p></c:rich></c:tx><c:overlay val=\"0\"/></c:title>");
    }

    private static void textProperties(StringBuilder x, int size, boolean bold, String color, String font) {
        x.append("<c:txPr><a:bodyPr/><a:lstStyle/><a:p><a:pPr><a:defRPr sz=\"").append(size).append("\" b=\"")
                .append(bold ? 1 : 0).append("\"><a:solidFill><a:srgbClr val=\"").append(color)
                .append("\"/></a:solidFill><a:latin typeface=\"").append(Xml.esc(font))
                .append("\"/></a:defRPr></a:pPr><a:endParaRPr lang=\"en-US\"/></a:p></c:txPr>");
    }
}
