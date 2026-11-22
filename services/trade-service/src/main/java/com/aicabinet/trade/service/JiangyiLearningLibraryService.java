package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiDevice;

/**
 * 将邑映射复核判定（原「商品学习库」服务收缩后的残留，2026-11-21 收缩）。
 *
 * <p>背景：本类原是租户级学习库视图（模型/可学习名单/覆盖/凭据四块），2026-11-09
 * 旧学习链整链退场（台账 12B.8：厂商自采自训权限不在我方，第七章新采集链替代）时
 * 删掉了全部入口与 UI，但类体留着了——只剩 {@code isMappingVerified} 仍被
 * {@link JiangyiFlowSummaryService} 引用。2026-11-21 将邑流程审计（MA-383）把
 * 无调用方的 {@code view()} 与六个 DTO record 判死删除；复核语义的家在
 * {@link JiangyiMappingReviewService}（status/plan/apply）。</p>
 *
 * <p>🔴 P3-6（2026-11-21 用户复核）：原 {@code isMappingVerified} 是
 * {@link JiangyiMappingReviewService#status} 同一判据的第二份实现（两处各写一遍迟早漂移）。
 * 判据已合并到 {@link JiangyiMappingReviewService#isMappingVerified}，本类只留委托壳——
 * 不直接删文件是因为 fix-batch 历史登记（4f4d1c42 MA-383）声明过本文件，删除会让
 * 「登记文件在仓内不存在」门禁对历史批次误红。</p>
 */
final class JiangyiLearningLibraryService {

    private JiangyiLearningLibraryService() {
    }

    /** 委托给唯一权威判据，勿在此复制实现。 */
    static boolean isMappingVerified(JiangyiDevice device) {
        return JiangyiMappingReviewService.isMappingVerified(device);
    }
}
