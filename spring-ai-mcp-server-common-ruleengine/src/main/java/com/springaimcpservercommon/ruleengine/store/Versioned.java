package com.springaimcpservercommon.ruleengine.store;

/**
 * A loaded value together with the change-marker version it was read at.
 *
 * @param version marker version read before the data (a later write bumps it, so the next poll reloads)
 * @param value   the data
 * @param <T>     data type
 */
public record Versioned<T>(long version, T value) {
}
