package com.aicabinet.trade.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlanogramTemplateServiceTest {

    @Test
    void standardTemplate_hasEightSlots() {
        assertEquals(8, PlanogramTemplateService.standardTemplate().size());
    }

    @Test
    void compactTemplate_hasSixSlots() {
        assertEquals(6, PlanogramTemplateService.compactTemplate().size());
    }

    @Test
    void templateFor_unknownType_fallsBackToStandard() {
        assertEquals(
                PlanogramTemplateService.standardTemplate().size(),
                PlanogramTemplateService.templateFor("UNKNOWN").size());
    }

    @Test
    void templateFor_compactType_returnsCompact() {
        assertEquals(6, PlanogramTemplateService.templateFor("AI_CABINET_COMPACT").size());
    }
}
