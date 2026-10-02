package com.springaimcpservercommon.jfranalyzer.report.xlsx;

/** XML text helpers. */
final class Xml {

    private Xml() {
    }

    /**
     * Escapes text for element content and attribute values, dropping characters XML 1.0 cannot carry.
     *
     * @param s text
     * @return escaped text
     */
    static String esc(String s) {
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&apos;");
                default -> {
                    if (c >= 0x20 || c == '\t' || c == '\n' || c == '\r') {
                        if (c != 0xFFFE && c != 0xFFFF) {
                            b.append(c);
                        }
                    }
                }
            }
        }
        return b.toString();
    }

    /**
     * @param value a finite number
     * @return its shortest XML/Excel representation
     */
    static String num(double value) {
        if (!Double.isFinite(value)) {
            return "0";
        }
        if (value == Math.rint(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    /**
     * @param column 1-based column
     * @return its letters, e.g. 1 → A, 28 → AB
     */
    static String column(int column) {
        StringBuilder b = new StringBuilder();
        int c = column;
        while (c > 0) {
            int rem = (c - 1) % 26;
            b.insert(0, (char) ('A' + rem));
            c = (c - 1) / 26;
        }
        return b.toString();
    }

    /**
     * @param row    1-based row
     * @param column 1-based column
     * @return the A1 reference
     */
    static String ref(int row, int column) {
        return column(column) + row;
    }

    /**
     * @param row    1-based row
     * @param column 1-based column
     * @return the absolute reference, e.g. {@code $B$3}
     */
    static String absRef(int row, int column) {
        return "$" + column(column) + "$" + row;
    }
}
