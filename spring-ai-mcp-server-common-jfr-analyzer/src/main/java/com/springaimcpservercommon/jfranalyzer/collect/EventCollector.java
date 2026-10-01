package com.springaimcpservercommon.jfranalyzer.collect;

import jdk.jfr.consumer.RecordedEvent;

/**
 * Receives every event of the recording, in file order, and keeps what its section needs.
 */
public interface EventCollector {

    /**
     * @param type  event type name, e.g. {@code jdk.ExecutionSample}
     * @param event the event
     */
    void accept(String type, RecordedEvent event);
}
