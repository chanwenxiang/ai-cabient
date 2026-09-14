package com.aicabinet.trade.support;

import com.aicabinet.trade.service.XxlJobManagedTasks;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScheduleZonesTest {

    @Test
    void zone_isAsiaShanghai() {
        assertEquals("Asia/Shanghai", ScheduleZones.ZONE_ID);
        assertEquals(ScheduleZones.ZONE_ID, ScheduleZones.ZONE.getId());
    }

    @Test
    void everyXxlManagedTask_hasRecommendedCron() {
        for (String key : XxlJobManagedTasks.KEYS) {
            String cron = XxlJobManagedTasks.recommendedXxlCron(key);
            assertTrue(cron != null && !cron.isBlank(), "missing XXL cron for " + key);
            assertTrue(cron.contains("?"), "Quartz DoW should use ?: " + key + " -> " + cron);
        }
        assertEquals(XxlJobManagedTasks.KEYS.size(), ScheduleZones.XXL_CRON_BY_TASK.size());
    }

    @Test
    void desc_appendsZone() {
        assertEquals("每日 01:30 (Asia/Shanghai)", ScheduleZones.desc("每日 01:30"));
    }

    @Test
    void springAndXxl_reconAligned() {
        assertEquals("0 30 1 * * *", ScheduleZones.SPRING_CRON_RECON);
        assertEquals("0 30 1 * * ?", ScheduleZones.XXL_CRON_BY_TASK.get("reconciliation"));
    }
}
