package com.springaimcpservercommon.ruleengine.api;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * A receiver for API channels so the seeded "Risk webhook" works on a local machine: it keeps the last events in
 * memory. A real deployment points the API channel at the caller's own service instead.
 */
@RestController
public class LocalWebhookController {

    private static final int KEEP = 50;
    private final ConcurrentLinkedDeque<Map<String, Object>> events = new ConcurrentLinkedDeque<>();

    @PostMapping("/hooks/rule-events")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void receive(@RequestBody Map<String, Object> event) {
        events.addFirst(event);
        while (events.size() > KEEP) {
            events.removeLast();
        }
    }

    @GetMapping("/hooks/rule-events")
    public List<Map<String, Object>> received() {
        return new ArrayList<>(events);
    }
}
