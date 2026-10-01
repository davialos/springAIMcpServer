package com.springaimcpservercommon.loadtest.data;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal RFC 4180 CSV reader (quoted fields, escaped quotes, CRLF), enough for user value files.
 */
final class Csv {

    private Csv() {
    }

    static List<List<String>> parse(String text) {
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                row.add(cell.toString());
                cell.setLength(0);
                if (!(row.size() == 1 && row.getFirst().isEmpty())) {
                    rows.add(row);
                }
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            rows.add(row);
        }
        return rows;
    }

    /** Numbers and booleans keep their type in JSON; everything else stays a string. */
    static Object typed(String cell) {
        if (cell.matches("-?(0|[1-9]\\d{0,17})")) {
            return Long.valueOf(cell);
        }
        if (cell.matches("-?\\d+\\.\\d+")) {
            return new BigDecimal(cell);
        }
        if (cell.equalsIgnoreCase("true") || cell.equalsIgnoreCase("false")) {
            return Boolean.valueOf(cell);
        }
        return cell;
    }
}
