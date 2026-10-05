package com.springaimcpservercommon.ruleengine.model;

/** The environment an API endpoint belongs to. */
public enum ApiEnvironment {
    /** Development API. */
    DEV,
    /** QA / test / staging API. */
    QA,
    /** Production API. */
    PROD,
    /** Not classifiable (outside our environments); needs a recorded confirmation. */
    EXTERNAL
}
