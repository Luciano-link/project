package com.luciano.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AgentTaskDetectorTest {

    @Test
    void detectsTravelPlanGoal() {
        assertTrue(AgentTaskDetector.isAgentTask("帮我做一份上海三日游完整出行方案"));
    }

    @Test
    void ignoresSimpleChat() {
        assertFalse(AgentTaskDetector.isAgentTask("你好"));
        assertFalse(AgentTaskDetector.isAgentTask("北京天气怎么样"));
    }
}
