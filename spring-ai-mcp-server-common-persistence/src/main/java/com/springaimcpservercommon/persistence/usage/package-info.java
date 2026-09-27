/**
 * Model prices, budgets and the hourly usage ledger (LLD-10 §5–6, F-70, V5 migration). Money is always
 * {@code long} micros plus an ISO-4217 currency code; periods and hourly buckets are UTC.
 */
@NullMarked
package com.springaimcpservercommon.persistence.usage;

import org.jspecify.annotations.NullMarked;
