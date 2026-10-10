package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.client.TradeInternalClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 将邑云学习完成回调（CB-023，采集文档 §4.4.3 finishNotifyUrl）。
 *
 * <p>公开面（无设备 JWT，GatewayWebConfig 排除缺口）：将邑侧无法持设备 JWT，文档明文
 * 「此url不能有token及其他限制」。防伪造不在本层——trade 侧 finishNotifyId 一次性凭据
 * （jiangyi_training_ticket）+ 将邑侧反查交叉验证承担；未知 finishNotifyId 静默 202，
 * 不回错误详情（不给探测者信息）。</p>
 */
@RestController
@RequestMapping("/jiangyi/api")
public class GatherNotifyController {

    private static final Logger log = LoggerFactory.getLogger(GatherNotifyController.class);

    private final TradeInternalClient tradeInternalClient;

    public GatherNotifyController(TradeInternalClient tradeInternalClient) {
        this.tradeInternalClient = tradeInternalClient;
    }

    /** 回调体：{"finishNotifyId":"324","msg":"success"}（文档 §4.4.3 原文）。 */
    @PostMapping("/gather-finish-notify")
    public Map<String, Object> gatherFinishNotify(@RequestBody Map<String, Object> body) {
        String finishNotifyId = body.get("finishNotifyId") == null ? null : body.get("finishNotifyId").toString();
        String msg = body.get("msg") == null ? null : body.get("msg").toString();
        if (finishNotifyId == null || finishNotifyId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "finishNotifyId required");
        }
        try {
            tradeInternalClient.gatherFinishNotify(finishNotifyId, msg);
        } catch (Exception e) {
            // 静默 202 语义：不回错误详情（无鉴权面不暴露内部状态），trade 侧已有幂等保护
            log.warn("jiangyi gather finish-notify forward failed finishNotifyId={}: {}", finishNotifyId, e.getMessage());
        }
        return Map.of("status", 202, "msg", "accepted");
    }
}
