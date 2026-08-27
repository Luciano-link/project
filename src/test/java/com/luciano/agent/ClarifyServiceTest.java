package com.luciano.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ClarifyServiceTest {

    private ClarifyService clarifyService;

    @BeforeEach
    void setUp() {
        clarifyService = new ClarifyService();
    }

    @Test
    void needClarification_whenCityAndDaysPresent_returnsNull() {
        assertNull(clarifyService.needClarification("帮我做上海三日游完整出行方案"));
    }

    @Test
    void needClarification_whenMissingCity_asksForCity() {
        assertNotNull(clarifyService.needClarification("帮我做三日游完整出行方案"));
    }
}
