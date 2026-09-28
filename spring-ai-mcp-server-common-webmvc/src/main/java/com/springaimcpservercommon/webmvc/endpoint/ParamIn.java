package com.springaimcpservercommon.webmvc.endpoint;

/**
 * Where a parameter is bound in the HTTP request (LLD-04 §2).
 */
public enum ParamIn {
    PATH,
    QUERY,
    HEADER,
    BODY
}
