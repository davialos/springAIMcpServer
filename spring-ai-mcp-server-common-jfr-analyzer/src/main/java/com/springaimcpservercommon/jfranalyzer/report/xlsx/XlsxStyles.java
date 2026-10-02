package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registers cell styles and renders {@code xl/styles.xml}. Equal styles share one index. */
public final class XlsxStyles {

    /**
     * A font.
     *
     * @param name      typeface
     * @param size      points
     * @param bold      bold
     * @param italic    italic
     * @param underline single underline
     * @param color     RGB hex, e.g. {@code 0B0B0B}
     */
    public record Font(String name, double size, boolean bold, boolean italic, boolean underline, String color) {
    }

    /**
     * One border edge.
     *
     * @param style {@code thin}, {@code medium}, {@code thick}, {@code hair}
     * @param color RGB hex
     */
    public record Edge(String style, String color) {
    }

    /**
     * Borders; {@code null} edges are absent.
     *
     * @param left   left
     * @param right  right
     * @param top    top
     * @param bottom bottom
     */
    public record Border(@Nullable Edge left, @Nullable Edge right, @Nullable Edge top, @Nullable Edge bottom) {
        /** No border. */
        public static final Border NONE = new Border(null, null, null, null);
    }

    /**
     * A complete cell style.
     *
     * @param font         font
     * @param fill         solid fill RGB hex, or {@code null}
     * @param border       borders
     * @param numberFormat Excel number format code, or {@code null} for General
     * @param horizontal   {@code general}, {@code left}, {@code center}, {@code right}
     * @param vertical     {@code top}, {@code center}, {@code bottom}
     * @param wrap         wrap text
     * @param indent       left indent level
     */
    public record Style(Font font, @Nullable String fill, Border border, @Nullable String numberFormat,
                        String horizontal, String vertical, boolean wrap, int indent) {

        /** @return a copy with another font */
        public Style withFont(Font f) {
            return new Style(f, fill, border, numberFormat, horizontal, vertical, wrap, indent);
        }

        /** @return a copy with another fill */
        public Style withFill(@Nullable String f) {
            return new Style(font, f, border, numberFormat, horizontal, vertical, wrap, indent);
        }

        /** @return a copy with other borders */
        public Style withBorder(Border b) {
            return new Style(font, fill, b, numberFormat, horizontal, vertical, wrap, indent);
        }

        /** @return a copy with another number format */
        public Style withFormat(@Nullable String f) {
            return new Style(font, fill, border, f, horizontal, vertical, wrap, indent);
        }

        /** @return a copy with another alignment */
        public Style withAlign(String h, String v, boolean w) {
            return new Style(font, fill, border, numberFormat, h, v, w, indent);
        }

        /** @return a copy with another indent */
        public Style withIndent(int i) {
            return new Style(font, fill, border, numberFormat, horizontal, vertical, wrap, i);
        }
    }

    private final Font defaultFont;
    private final List<Font> fonts = new ArrayList<>();
    private final List<String> fills = new ArrayList<>();
    private final List<Border> borders = new ArrayList<>();
    private final Map<String, Integer> numberFormats = new LinkedHashMap<>();
    private final List<Style> xfs = new ArrayList<>();
    private final Map<Style, Integer> index = new HashMap<>();

    /** @param defaultFont font of the Normal style */
    public XlsxStyles(Font defaultFont) {
        this.defaultFont = defaultFont;
        fonts.add(defaultFont);
        borders.add(Border.NONE);
        Style normal = new Style(defaultFont, null, Border.NONE, null, "general", "bottom", false, 0);
        xfs.add(normal);
        index.put(normal, 0);
    }

    /** @return the font of the Normal style */
    public Font defaultFont() {
        return defaultFont;
    }

    /**
     * @param style a style
     * @return its cell-format index
     */
    public int of(Style style) {
        return index.computeIfAbsent(style, s -> {
            xfs.add(s);
            return xfs.size() - 1;
        });
    }

    String toXml() {
        // Resolve components in registration order so ids are stable.
        List<Integer> fontIds = new ArrayList<>();
        List<Integer> fillIds = new ArrayList<>();
        List<Integer> borderIds = new ArrayList<>();
        List<Integer> formatIds = new ArrayList<>();
        for (Style s : xfs) {
            fontIds.add(indexOf(fonts, s.font()));
            fillIds.add(s.fill() == null ? 0 : 2 + indexOf(fills, s.fill()));
            borderIds.add(indexOf(borders, s.border()));
            formatIds.add(s.numberFormat() == null ? 0
                    : numberFormats.computeIfAbsent(s.numberFormat(), f -> 164 + numberFormats.size()));
        }
        StringBuilder x = new StringBuilder(4096);
        x.append("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n")
                .append("<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (!numberFormats.isEmpty()) {
            x.append("<numFmts count=\"").append(numberFormats.size()).append("\">");
            numberFormats.forEach((code, id) -> x.append("<numFmt numFmtId=\"").append(id)
                    .append("\" formatCode=\"").append(Xml.esc(code)).append("\"/>"));
            x.append("</numFmts>");
        }
        x.append("<fonts count=\"").append(fonts.size()).append("\">");
        for (Font f : fonts) {
            x.append("<font>");
            if (f.bold()) {
                x.append("<b/>");
            }
            if (f.italic()) {
                x.append("<i/>");
            }
            if (f.underline()) {
                x.append("<u/>");
            }
            x.append("<sz val=\"").append(Xml.num(f.size())).append("\"/><color rgb=\"FF").append(f.color())
                    .append("\"/><name val=\"").append(Xml.esc(f.name())).append("\"/><family val=\"2\"/></font>");
        }
        x.append("</fonts><fills count=\"").append(fills.size() + 2).append("\">")
                .append("<fill><patternFill patternType=\"none\"/></fill>")
                .append("<fill><patternFill patternType=\"gray125\"/></fill>");
        for (String rgb : fills) {
            x.append("<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FF").append(rgb)
                    .append("\"/><bgColor indexed=\"64\"/></patternFill></fill>");
        }
        x.append("</fills><borders count=\"").append(borders.size()).append("\">");
        for (Border b : borders) {
            x.append("<border>");
            edge(x, "left", b.left());
            edge(x, "right", b.right());
            edge(x, "top", b.top());
            edge(x, "bottom", b.bottom());
            x.append("<diagonal/></border>");
        }
        x.append("</borders><cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/>")
                .append("</cellStyleXfs><cellXfs count=\"").append(xfs.size()).append("\">");
        for (int i = 0; i < xfs.size(); i++) {
            Style s = xfs.get(i);
            x.append("<xf numFmtId=\"").append(formatIds.get(i)).append("\" fontId=\"").append(fontIds.get(i))
                    .append("\" fillId=\"").append(fillIds.get(i)).append("\" borderId=\"").append(borderIds.get(i))
                    .append("\" xfId=\"0\"");
            if (i > 0) {
                x.append(" applyNumberFormat=\"1\" applyFont=\"1\" applyFill=\"1\" applyBorder=\"1\""
                        + " applyAlignment=\"1\"><alignment horizontal=\"").append(s.horizontal())
                        .append("\" vertical=\"").append(s.vertical()).append("\"");
                if (s.wrap()) {
                    x.append(" wrapText=\"1\"");
                }
                if (s.indent() > 0) {
                    x.append(" indent=\"").append(s.indent()).append("\"");
                }
                x.append("/></xf>");
            } else {
                x.append("/>");
            }
        }
        x.append("</cellXfs><cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/>")
                .append("</cellStyles><dxfs count=\"0\"/><tableStyles count=\"0\"/></styleSheet>");
        return x.toString();
    }

    private static void edge(StringBuilder x, String name, @Nullable Edge e) {
        if (e == null) {
            x.append('<').append(name).append("/>");
        } else {
            x.append('<').append(name).append(" style=\"").append(e.style()).append("\"><color rgb=\"FF")
                    .append(e.color()).append("\"/></").append(name).append('>');
        }
    }

    private static <T> int indexOf(List<T> list, T value) {
        int i = list.indexOf(value);
        if (i >= 0) {
            return i;
        }
        list.add(value);
        return list.size() - 1;
    }
}
