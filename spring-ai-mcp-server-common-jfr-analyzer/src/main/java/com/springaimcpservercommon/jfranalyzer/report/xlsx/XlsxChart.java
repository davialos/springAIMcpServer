package com.springaimcpservercommon.jfranalyzer.report.xlsx;

import org.jspecify.annotations.Nullable;

import java.util.List;

/** A native Excel chart placed over a cell range. Data lives in cells; the cached values are written too. */
public sealed interface XlsxChart permits XlsxChart.Scatter, XlsxChart.Bar {

    /**
     * Where the chart sits: from the top-left corner of the first cell to the top-left corner of the last.
     *
     * @param fromRow    1-based first row
     * @param fromColumn 1-based first column
     * @param toRow      1-based row after the chart
     * @param toColumn   1-based column after the chart
     */
    record Anchor(int fromRow, int fromColumn, int toRow, int toColumn) {
    }

    /**
     * Cells holding one series' numbers, with their values for the cache.
     *
     * @param sheet  sheet name
     * @param column 1-based column
     * @param first  1-based first row
     * @param values the values, one per row from {@code first}
     */
    record NumberRange(String sheet, int column, int first, double[] values) {

        String formula() {
            return XlsxSheet.quote(sheet) + "!" + Xml.absRef(first, column) + ":"
                    + Xml.absRef(first + Math.max(values.length, 1) - 1, column);
        }
    }

    /**
     * Cells holding category labels.
     *
     * @param sheet  sheet name
     * @param column 1-based column
     * @param first  1-based first row
     * @param labels the labels
     */
    record TextRange(String sheet, int column, int first, List<String> labels) {

        String formula() {
            return XlsxSheet.quote(sheet) + "!" + Xml.absRef(first, column) + ":"
                    + Xml.absRef(first + Math.max(labels.size(), 1) - 1, column);
        }
    }

    /**
     * One series of a scatter chart.
     *
     * @param name    legend name
     * @param color   RGB hex
     * @param x       x values
     * @param y       y values
     * @param line    draw a 2 px line through the points
     * @param markers draw round markers
     */
    record Series(String name, String color, NumberRange x, NumberRange y, boolean line, boolean markers) {
    }

    /** @return title shown above the plot */
    String title();

    /** @return placement */
    Anchor anchor();

    /**
     * Numeric x/y chart (used for time series).
     *
     * @param title        title
     * @param xMax         x-axis maximum, or {@code null} to let the application choose
     * @param xTitle       x-axis title
     * @param yTitle       y-axis title
     * @param xFormat      x-axis number format
     * @param yFormat      y-axis number format
     * @param series       series, in legend order
     * @param anchor       placement
     */
    record Scatter(String title, @Nullable Double xMax, String xTitle, String yTitle, String xFormat,
                   String yFormat, List<Series> series, Anchor anchor) implements XlsxChart {
    }

    /**
     * Horizontal bar chart, first category at the top, values printed at the bar ends.
     *
     * @param title        title
     * @param seriesName   series name
     * @param color        RGB hex
     * @param categories   category labels
     * @param values       values
     * @param valueFormat  number format of the value labels
     * @param anchor       placement
     */
    record Bar(String title, String seriesName, String color, TextRange categories, NumberRange values,
               String valueFormat, Anchor anchor) implements XlsxChart {
    }
}
