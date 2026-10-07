/**
 * Resilience experiments: network faults (latency, outage, throttled bandwidth, reset connections …) injected through
 * <a href="https://github.com/Shopify/toxiproxy">Toxiproxy</a> while the load runs. The k6 suite injects and judges
 * them ({@code lib/resilience.js}); this package sets a suite up for them and talks to the Toxiproxy server.
 */
@NullMarked
package com.springaimcpservercommon.loadtest.resilience;

import org.jspecify.annotations.NullMarked;
