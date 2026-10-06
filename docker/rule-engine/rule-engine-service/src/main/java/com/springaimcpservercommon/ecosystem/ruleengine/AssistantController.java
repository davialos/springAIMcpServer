package com.springaimcpservercommon.ecosystem.ruleengine;

import com.springaimcpservercommon.ecosystem.ruleengine.Dtos.AssistantInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tells the console whether the AI assistant is available and which agent to chat with, making it available first when
 * the signed-in user's tenant has none yet. The chat itself is the library's own endpoint
 * {@code POST /dynamic-ai/api/agents/{slug}/chat/stream}, called by the console with the same access token.
 */
@RestController
@RequestMapping("/api/v1/assistant")
class AssistantController {

    private final AssistantProvisioner provisioner;

    AssistantController(AssistantProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    @GetMapping
    AssistantInfo info(Caller c, @RequestHeader("Authorization") String authorization) {
        return provisioner.prepare(c, authorization);
    }
}
