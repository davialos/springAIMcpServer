package com.springaimcpservercommon.persistence.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Result of an audit append.
 *
 * @param id         event id
 * @param chainId    chain
 * @param chainSeq   assigned sequence number
 * @param occurredAt stored event time (microseconds)
 * @param hash       event hash (new chain head)
 */
public record AppendedAuditEvent(UUID id, String chainId, long chainSeq, Instant occurredAt, String hash) {
}
