package com.aicabinet.trade.service;

import com.aicabinet.common.dto.GravityDeltaRequest;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 补货重力**证据**判定（原「关门快照回写货道实测」，该写面已随批 3b 第十五刀③退场）。
 *
 * <p>{@code hasRealGravitySnapshot} 是补货完成时「保不保留关门实测」的判据（P0-2），
 * 与货道无关，仍在用。类名已于批 3b 第二十一刀从 {@code RestockSnapshotService} 收口为
 * {@code RestockGravityEvidenceService}——它只回答「有没有真实重力实测」，不做任何快照/回写。</p>
 */
@Service
public class RestockGravityEvidenceService {

    private final GravitySettlementHelper gravityHelper;
    private final ShoppingSessionMapper sessionRepository;

    public RestockGravityEvidenceService(GravitySettlementHelper gravityHelper,
                                         ShoppingSessionMapper sessionRepository) {
        this.gravityHelper = gravityHelper;
        this.sessionRepository = sessionRepository;
    }

    /**
     * 该补货任务的关门会话是否产生过**真实重力实测**（P0-2，2026-10-15）：
     * 有真实实测 ⇒ 补货完成时保留快照值，不用回写后账面覆盖（差异留在 qtyDiff 列可追
     * 原 SLOT_DISCREPANCY 告警面已随货道退场，批 3b 第十一刀）。
     * 会话 gravityDeltas 解析出任一非空增量即认定真实实测；无会话/无增量返回 false。
     */
    @Transactional(readOnly = true)
    public boolean hasRealGravitySnapshot(Long taskId) {
        if (taskId == null) {
            return false;
        }
        for (ShoppingSession session : sessionRepository.findByReplenishmentTaskId(taskId)) {
            List<GravityDeltaRequest.GravityDeltaItem> deltas =
                    gravityHelper.parse(session.getGravityDeltas());
            if (!deltas.isEmpty()) {
                return true;
            }
        }
        return false;
    }
}
