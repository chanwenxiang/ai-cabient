package com.aicabinet.trade.service;

import com.aicabinet.trade.support.ScheduleZones;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MA-260：XXL 托管任务必须有 Spring 回退（XXL_JOB_ENABLED=false 时的逃生门）。
 * 生产 XXL_JOB_ENABLED=true 时由 tryBegin 让位，不会与调度中心双跑。
 */
class ManagedFundTaskScheduledFallbackTest {

    @Test
    void platformFundDaily_hasSpringFallbackMatchingXxlCron() throws Exception {
        assertFallback(PlatformFundService.class, "runDailyCheck", "platform-fund-daily");
    }

    @Test
    void deviceRentalDaily_hasSpringFallbackMatchingXxlCron() throws Exception {
        assertFallback(DeviceRentalService.class, "runDailyCheck", "device-rental-daily");
    }

    private static void assertFallback(Class<?> type, String methodName, String taskKey) throws Exception {
        assertTrue(XxlJobManagedTasks.isManaged(taskKey), taskKey + " 应在 XXL 托管清单");
        String xxlCron = ScheduleZones.XXL_CRON_BY_TASK.get(taskKey);
        assertNotNull(xxlCron, taskKey + " 缺 XXL 排期");

        Method method = type.getMethod(methodName);
        Scheduled scheduled = method.getAnnotation(Scheduled.class);
        assertNotNull(scheduled,
                type.getSimpleName() + "." + methodName + " 缺 @Scheduled：XXL 关闭时该任务不会跑");
        assertEquals(xxlCron, scheduled.cron(), "Spring 回退 cron 必须与 XXL 排期一致");
        assertTrue(scheduled.zone().contains("Asia/Shanghai"), "时区必须与全局调度一致");
    }
}
