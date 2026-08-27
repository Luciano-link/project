package com.luciano.agent;

import com.luciano.agent.TaskState.AgentPlan;
import com.luciano.llm.LlmService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class PlannerServiceTest {

    private PlannerService plannerService;

    @BeforeEach
    void setUp() {
        plannerService = new PlannerService(mock(LlmService.class));
    }

    @Test
    void parsePlan_parsesValidJson() {
        String json = """
                {"subtasks":[
                  {"id":1,"title":"查天气","description":"查上海天气","toolHint":"get_weather"},
                  {"id":2,"title":"规划行程","description":"安排景点","toolHint":"search"},
                  {"id":3,"title":"汇总","description":"输出方案","toolHint":"none"}
                ]}
                """;
        AgentPlan plan = plannerService.parsePlan("上海三日游", json);

        assertNotNull(plan);
        assertEquals(3, plan.subtasks().size());
        assertEquals("查天气", plan.subtasks().get(0).title());
    }

    @Test
    void defaultTravelPlan_hasAtLeastThreeSteps() {
        AgentPlan plan = plannerService.defaultTravelPlan("杭州三日游");

        assertTrue(plan.subtasks().size() >= 3);
    }
}
