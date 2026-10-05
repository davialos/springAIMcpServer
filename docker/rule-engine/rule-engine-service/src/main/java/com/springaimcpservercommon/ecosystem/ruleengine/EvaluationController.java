package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.Decision;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.EvaluateRequest;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerDecision;
import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.TriggerRequestBody;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** The test bench: ask what the engine decides for the facts you supply. Nothing is sent to anyone. */
@RestController
@RequestMapping("/api/v1")
class EvaluationController {

    private final EvaluationService evaluation;

    EvaluationController(EvaluationService evaluation) {
        this.evaluation = evaluation;
    }

    @PostMapping("/evaluations")
    Decision evaluate(Caller c, @RequestBody EvaluateRequest request) {
        return evaluation.evaluate(c, request);
    }

    @PostMapping("/triggers/evaluate")
    TriggerDecision trigger(Caller c, @RequestBody TriggerRequestBody request) {
        return evaluation.trigger(c, request);
    }
}
