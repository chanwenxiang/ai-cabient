package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.mapper.JiangyiSkuJiangyiLinkMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * §7.10 商品状态轮询：把「手机图审核中 / 柜内图审核中」的挂接行逐分钟刷一次，
 * 让审核结果（READY / CABINET_PENDING / REJECTED + 驳回原因）自动落到我方快照，
 * 运营不再需要手点「刷新状态」。
 *
 * <p>只用 §7.10 批量接口（1~100 条/次）：§7.5 有写副作用（首次查询会建采集任务），
 * 不能拿来做轮询。厂商门禁关闭（503「商户没有 _ID-OK 设备」）时本轮记 FAILED 不重抛，
 * 下一分钟自然重试。</p>
 */
@Service
public class JiangyiProductStatePollScheduler {

    private static final Logger log = LoggerFactory.getLogger(JiangyiProductStatePollScheduler.class);
    private static final String TASK_KEY = "jiangyi-product-state-poll";
    private static final int BATCH_LIMIT = 100;

    private final JiangyiSkuJiangyiLinkMapper linkMapper;
    private final JiangyiProductStateService productStateService;
    private final ScheduledTaskService taskService;

    public JiangyiProductStatePollScheduler(JiangyiSkuJiangyiLinkMapper linkMapper,
                                            JiangyiProductStateService productStateService,
                                            ScheduledTaskService taskService) {
        this.linkMapper = linkMapper;
        this.productStateService = productStateService;
        this.taskService = taskService;
    }

    @Scheduled(fixedRate = 60_000)
    public void pollReviewingProducts() {
        long start = System.nanoTime();
        if (!taskService.tryBegin(TASK_KEY, 600)) {
            return;
        }
        String summary = "本次无需轮询";
        try {
            List<JiangyiSkuJiangyiLink> pollable = linkMapper.listPollable(BATCH_LIMIT);
            List<String> barCodes = pollable.stream()
                    .map(JiangyiSkuJiangyiLink::getBarCode)
                    .filter(b -> b != null && !b.isBlank())
                    .distinct()
                    .toList();
            if (barCodes.isEmpty()) {
                taskService.finish(TASK_KEY, "SUCCESS", summary, start);
                return;
            }
            int refreshed = productStateService.refresh(barCodes);
            summary = "轮询 " + refreshed + " 条审核中商品";
            taskService.finish(TASK_KEY, "SUCCESS", summary, start);
        } catch (Exception e) {
            // 厂商门禁关闭/网络抖动属可恢复：记 FAILED，下一轮自然重试，不外抛刷栈
            log.warn("jiangyi product state poll failed: {}", e.getMessage());
            taskService.finish(TASK_KEY, "FAILED", e.getMessage(), start);
        }
    }
}