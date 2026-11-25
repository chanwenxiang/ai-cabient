package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PlatformFundAccount;
import com.aicabinet.trade.domain.PlatformFundFlow;
import com.aicabinet.trade.mapper.PlatformFundAccountMapper;
import com.aicabinet.trade.mapper.PlatformFundFlowMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;

@Service
public class PlatformFundFlowService {

    public static final String FUND_BILL_REF = "WECHAT_FUND_BILL";

    private final PlatformFundAccountMapper accountMapper;
    private final PlatformFundFlowMapper flowMapper;

    public PlatformFundFlowService(PlatformFundAccountMapper accountMapper,
                                   PlatformFundFlowMapper flowMapper) {
        this.accountMapper = accountMapper;
        this.flowMapper = flowMapper;
    }

    @Transactional
    public boolean recordOnce(long accountId, String flowType, long amountCents,
                              String refType, String refId, Long operatorId, String remark) {
        PlatformFundAccount account = accountMapper.findByIdForUpdate(accountId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT, "平台资金账户不存在或已被删除"));
        if (refId != null && !refId.isBlank()) {
            if (PlatformFundFlow.TYPE_PAYOUT.equals(flowType)
                    && flowMapper.countByFlowTypeAndRefId(flowType, refId) > 0) {
                return false;
            }
            if (FUND_BILL_REF.equals(refType) && flowMapper.countByRefTypeAndRefId(refType, refId) > 0) {
                return false;
            }
        }
        if (!accountMapper.adjustBalance(accountId, amountCents)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "平台资金账户不存在或已被删除");
        }
        PlatformFundFlow flow = new PlatformFundFlow();
        flow.setAccountId(accountId);
        flow.setFlowType(flowType);
        flow.setAmountCents(amountCents);
        flow.setBalanceAfterCents(accountMapper.findById(accountId)
                .map(PlatformFundAccount::getBalanceCents).orElse(0L));
        flow.setRefType(refType);
        flow.setRefId(refId);
        flow.setOperatorId(operatorId);
        flow.setRemark(remark);
        flow.setCreatedAt(Instant.now());
        flowMapper.insert(flow);
        return true;
    }
}
