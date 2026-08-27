package com.luciano.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RagServiceTest {

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService();
        ReflectionTestUtils.setField(ragService, "enabled", true);
        ragService.init();
    }

    @Test
    void retrieve_longTravelTask_prefersTemplateOverPackingList() {
        String result = ragService.retrieve("帮我做北京三日游完整出行方案,列一下打包清单");

        assertNotNull(result);
        assertTrue(result.contains("完整出行方案成品应至少包含"));
        assertTrue(result.contains("三日游通用结构"));
    }

    @Test
    void retrieve_shanghaiOnly_whenCityMentioned() {
        String result = ragService.retrieve("上海三日游去外滩");

        assertNotNull(result);
        assertTrue(result.contains("仅适用于上海"));
    }

    @Test
    void retrieve_beijing_doesNotIncludeShanghaiGuide() {
        String result = ragService.retrieve("北京三日游完整出行方案");

        assertNotNull(result);
        assertTrue(!result.contains("仅适用于上海"));
    }

    @Test
    void cityAllowed_requiresCityMatchForCitySpecificEntry() {
        RagService.Knowledge shanghai = new RagService.Knowledge();
        shanghai.cities = List.of("上海", "魔都");
        shanghai.keywords = List.of("外滩");

        assertTrue(ragService.cityAllowed("上海三日游", shanghai));
        assertTrue(!ragService.cityAllowed("北京三日游", shanghai));
    }

    @Test
    void retrieve_disabled_returnsNull() {
        ReflectionTestUtils.setField(ragService, "enabled", false);

        assertNull(ragService.retrieve("上海三日游"));
    }
}
