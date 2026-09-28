/**
 * Model routing port for the agent runtime (LLD-06 §5).
 *
 * <p>{@link com.springaimcpservercommon.ai.model.ModelRouter} is an SPI; the autoconfigure module
 * registers a default implementation that looks up {@link org.springframework.ai.chat.model.ChatModel}
 * beans by provider id.
 */
@NullMarked
package com.springaimcpservercommon.ai.model;

import org.jspecify.annotations.NullMarked;
