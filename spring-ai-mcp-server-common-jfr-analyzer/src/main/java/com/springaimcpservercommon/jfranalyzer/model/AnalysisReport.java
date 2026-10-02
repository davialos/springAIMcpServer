package com.springaimcpservercommon.jfranalyzer.model;

import java.util.List;

/**
 * The complete result of analyzing one recording; rendered to HTML and JSON.
 *
 * @param meta       what was analyzed
 * @param executiveSummary status, score, key metrics, top issues and recommendations for teams and leadership
 * @param summary    headline numbers
 * @param findings   conclusions, most severe first
 * @param cpu        CPU hot spots
 * @param memory     heap and allocation
 * @param gc         garbage collection
 * @param threads    blocking and waiting
 * @param io         blocking I/O
 * @param exceptions exceptions
 */
public record AnalysisReport(ReportMeta meta, ExecutiveSummary executiveSummary, Summary summary,
                             List<Finding> findings, CpuReport cpu, MemoryReport memory, GcReport gc,
                             ThreadReport threads, IoReport io, ExceptionReport exceptions) {
}
