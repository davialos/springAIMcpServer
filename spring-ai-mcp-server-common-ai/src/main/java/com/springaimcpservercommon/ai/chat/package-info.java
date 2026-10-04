/**
 * Chat-interface support of the agent runtime (LLD-13 §3, F-53): the interactive {@code choice} component the model
 * can show through the built-in {@code present_choices} tool, server-side validation of the user's answer, and the
 * {@link com.springaimcpservercommon.ai.chat.ChatUiState} SPI that keeps components, answers and like/dislike
 * feedback so a reloaded chat shows the same state on any replica.
 */
@NullMarked
package com.springaimcpservercommon.ai.chat;

import org.jspecify.annotations.NullMarked;
