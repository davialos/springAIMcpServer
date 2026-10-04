package com.springaimcpservercommon.ai.runtime;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.ArrayList;
import java.util.List;

/**
 * Side channel of one turn for events produced outside the model's text stream: step progress, tool calls and
 * components shown by tools (LLD-13 §3). Tools may run on other threads, possibly in parallel (ADR-0017), so every
 * method is synchronized. A streamed turn merges {@link #flux()} into its event stream; a synchronous turn reads
 * {@link #collected()} afterwards.
 */
final class TurnEvents {

    private final boolean streaming;
    private final Sinks.Many<StreamEvent> sink = Sinks.many().unicast().onBackpressureBuffer();
    private final List<StreamEvent> collected = new ArrayList<>();
    private boolean completed;

    TurnEvents(boolean streaming) {
        this.streaming = streaming;
    }

    synchronized void emit(StreamEvent event) {
        if (completed) {
            return;
        }
        collected.add(event);
        if (streaming) {
            sink.tryEmitNext(event);
        }
    }

    synchronized void complete() {
        if (!completed) {
            completed = true;
            sink.tryEmitComplete();
        }
    }

    Flux<StreamEvent> flux() {
        return sink.asFlux();
    }

    synchronized List<StreamEvent> collected() {
        return List.copyOf(collected);
    }
}
