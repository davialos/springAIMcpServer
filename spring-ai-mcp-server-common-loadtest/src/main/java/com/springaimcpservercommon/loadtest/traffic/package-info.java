/**
 * Production traffic as input to a load test: Prometheus {@code http_server_requests} metrics and access logs become
 * the endpoint mix, the arrival rate, observed latencies/error rates and the endpoint-to-endpoint transitions of
 * sessions, written into a generated suite.
 */
@NullMarked
package com.springaimcpservercommon.loadtest.traffic;

import org.jspecify.annotations.NullMarked;
