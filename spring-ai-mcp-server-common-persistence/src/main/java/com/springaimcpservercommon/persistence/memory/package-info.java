/**
 * Model chat memory shared by all replicas (OQ-45, ADR-0021, migration V9): the message window a model is shown
 * for a conversation, separate from the user-visible transcript in {@code telemetry}.
 */
@NullMarked
package com.springaimcpservercommon.persistence.memory;

import org.jspecify.annotations.NullMarked;
