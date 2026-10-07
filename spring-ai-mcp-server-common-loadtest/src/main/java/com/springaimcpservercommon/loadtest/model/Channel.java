package com.springaimcpservercommon.loadtest.model;

import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

import java.util.List;

/**
 * A non-request/response endpoint of the project: a WebSocket, a STOMP-over-WebSocket endpoint, a Server-Sent Events
 * stream or a Kafka topic. They are not part of the per-API load (a request that never ends would hang it); the suite
 * runs them in their own {@code channels-<profile>} mode.
 *
 * @param id        identifier (a valid JS identifier)
 * @param kind      protocol
 * @param path      URL path of the WebSocket / STOMP endpoint / SSE stream; {@code null} for Kafka
 * @param send      where to send: STOMP application destinations ({@code /app/chat}), Kafka topics
 * @param subscribe what to listen to: STOMP broker destinations ({@code /topic/chat})
 * @param sample    a sample message (JSON) built from the handler's payload type, or {@code null}
 * @param source    where it was found (class or file), for the README
 */
public record Channel(String id, Kind kind, @Nullable String path, List<String> send, List<String> subscribe,
                      @Nullable JsonNode sample, String source) {

    /** The protocol of a channel. */
    public enum Kind {
        /** Plain WebSocket ({@code WebSocketHandler}, {@code @ServerEndpoint}). */
        WS,
        /** STOMP over WebSocket ({@code @MessageMapping}). */
        STOMP,
        /** Server-Sent Events. */
        SSE,
        /** Apache Kafka topic ({@code @KafkaListener}). */
        KAFKA
    }

    /** Compact constructor: defensive copies. */
    public Channel {
        send = List.copyOf(send);
        subscribe = List.copyOf(subscribe);
    }
}
