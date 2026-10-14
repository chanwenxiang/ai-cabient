package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.MerchantFeeLedger;
import com.aicabinet.trade.mapper.MerchantFeeLedgerMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

/**
 * 商户费用台账查询/结清（V355，架构门禁：Controller 禁直调 Mapper，2026-10-14 下沉）。
 * 结清口径：线下收款后标记 SETTLED；幂等（已结清直接返回）。
 */
@Service
public class MerchantFeeLedgerService {

    private final MerchantFeeLedgerMapper feeLedgerMapper;

    public MerchantFeeLedgerService(MerchantFeeLedgerMapper feeLedgerMapper) {
        this.feeLedgerMapper = feeLedgerMapper;
    }

    /** 台账列表（按商户/状态/费用类型过滤）。 */
    public List<MerchantFeeLedger> list(String merchantId, String status, String feeType) {
        return feeLedgerMapper.selectList(Wrappers.<MerchantFeeLedger>lambdaQuery()
                .eq(merchantId != null && !merchantId.isBlank(), MerchantFeeLedger::getMerchantId, merchantId)
                .eq(status != null && !status.isBlank(), MerchantFeeLedger::getStatus, status)
                .eq(feeType != null && !feeType.isBlank(), MerchantFeeLedger::getFeeType, feeType)
                .orderByDesc(MerchantFeeLedger::getLedgerId));
    }

    /** 标记结清（线下收款后；幂等：已结清直接返回）。 */
    public MerchantFeeLedger settle(Long ledgerId, String remark) {
        MerchantFeeLedger row = feeLedgerMapper.selectById(ledgerId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "台账记录不存在");
        }
        if ("SETTLED".equals(row.getStatus())) {
            return row;
        }
        row.setStatus("SETTLED");
        row.setSettledAt(Instant.now());
        if (remark != null && !remark.isBlank()) {
            row.setRemark(remark.trim());
        }
        feeLedgerMapper.updateById(row);
        return row;
    }
}
