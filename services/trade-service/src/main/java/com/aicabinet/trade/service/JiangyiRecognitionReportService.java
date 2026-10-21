package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.JiangyiRecognitionReport;
import com.aicabinet.trade.mapper.JiangyiRecognitionReportMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;

/**
 * 将邑识别上报留档写入（CB-062，V396）。
 *
 * <p><b>刻意 append-only</b>：同一 orderNo 重复上报（二次甄别、固件重试）每次都落新行，
 * 本服务无任何 UPDATE/DELETE——存档侧的幂等会把「第 1 次报了什么、第 2 次改成什么」抹平，
 * 而那正是识别准确率排查与争议举证的样本（同行口径：幂等作用于结算，不作用于留档）。</p>
 *
 * <p><b>与结算解耦</b>：{@code vendorAmountCents} 只写不读，A 档下金额一律由
 * {@code MerchantSkuPricingService} 按我方 SKU 现价计算；本表任何写入失败都不能影响结算
 * （调用方 gateway 以 fire-and-forget 方式上报）。</p>
 */
@Service
public class JiangyiRecognitionReportService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiRecognitionReportService.class);

    private final JiangyiRecognitionReportMapper reportMapper;

    public JiangyiRecognitionReportService(JiangyiRecognitionReportMapper reportMapper) {
        this.reportMapper = reportMapper;
    }

    /** 一到达一行；返回落库主键（gateway 只记日志，不消费）。 */
    public Long archive(JiangyiRecognitionReport report) {
        if (report.getReportedAt() == null) {
            report.setReportedAt(Instant.now());
        }
        report.setReportId(null);
        reportMapper.insert(report);
        log.info("jiangyi recognition report archived deviceId={} orderNo={} outcome={} forms={} items={}",
                report.getDeviceId(), report.getOrderNo(), report.getOutcome(),
                report.getFormCount(), report.getItemCount());
        return report.getReportId();
    }
}
