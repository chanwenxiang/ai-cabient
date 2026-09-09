package com.aicabinet.trade.api;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DemoDataInternalControllerGuardTest {

    @Test
    void controller_onlyLoadsWhenMockEnabled() {
        ConditionalOnProperty cond = DemoDataInternalController.class.getAnnotation(ConditionalOnProperty.class);
        assertNotNull(cond);
        assertEquals("aicabinet.security.mock-enabled", cond.name()[0]);
        assertEquals("true", cond.havingValue());
        assertEquals("/internal/v1/demo",
                DemoDataInternalController.class.getAnnotation(RequestMapping.class).value()[0]);
        assertNotNull(DemoDataInternalController.class.getAnnotation(RestController.class));
    }
}
