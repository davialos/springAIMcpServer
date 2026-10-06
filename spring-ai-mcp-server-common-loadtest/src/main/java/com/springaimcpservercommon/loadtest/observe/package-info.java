/**
 * Looking at the system under test while a load test runs: server-side checks sampled from its Prometheus endpoint
 * (connection pool, GC, threads, 5xx, log errors) that fail the run, and a Java Flight Recording of its JVM.
 */
@NullMarked
package com.springaimcpservercommon.loadtest.observe;

import org.jspecify.annotations.NullMarked;
