package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiDevice;

/**
 * 将邑映射复核判定（原「商品学习库」服务收缩后的残留，2026-11-21 收缩）。
 *
 * <p>背景：本类原是租户级学习库视图（模型/可学习名单/覆盖/凭据四块），2026-11-09
 * 旧学习链整链退场（台账 12B.8：厂商自采自训权限不在我方，第七章新采集链替代）时
 * 删掉了全部入口与 UI，但类体留着了——只剩 {@link #isMappingVerified} 仍被
 * {@link JiangyiFlowSummaryService} 引用。2026-11-21 将邑流程审计（MA-383）把
 * 无调用方的 {@code view()} 与六个 DTO record 判死删除；复核语义的家在
 * {@link JiangyiMappingReviewService}（status/plan/apply）。</p>
 */
final class JiangyiLearningLibraryService {

    private JiangyiLearningLibraryService() {
    }

    /** verified ⇔ 柜机 classes_version 非空且等于复核戳版本（V400；两者任一为空都不算已复核）。 */
    static boolean isMappingVerified(JiangyiDevice device) {
        String version = device.getClassesVersion();
        return version != null && !version.isBlank()
                && version.trim().equals(device.getMappingVerifiedVersion());
    }
}
