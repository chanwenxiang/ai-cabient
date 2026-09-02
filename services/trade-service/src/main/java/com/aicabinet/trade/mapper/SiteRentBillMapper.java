package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.SiteRentBill;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface SiteRentBillMapper extends BaseTradeMapper<SiteRentBill> {

    default List<SiteRentBill> findByContractAndMonth(Long contractId, String billMonth) {
        return selectList(Wrappers.<SiteRentBill>lambdaQuery()
                .eq(SiteRentBill::getContractId, contractId)
                .eq(SiteRentBill::getBillMonth, billMonth)
                .orderByAsc(SiteRentBill::getBillId));
    }

    default Page<SiteRentBill> searchPage(String billMonth, String status, Long contractId, int page, int size) {
        var q = Wrappers.<SiteRentBill>lambdaQuery()
                .eq(billMonth != null && !billMonth.isBlank(), SiteRentBill::getBillMonth, billMonth)
                .eq(status != null && !status.isBlank(), SiteRentBill::getStatus, status)
                .eq(contractId != null, SiteRentBill::getContractId, contractId)
                .orderByDesc(SiteRentBill::getBillMonth)
                .orderByDesc(SiteRentBill::getBillId);
        return selectPage(new Page<>(page, size), q);
    }

    default long countNonVoidByContractAndMonth(Long contractId, String billMonth) {
        return selectCount(Wrappers.<SiteRentBill>lambdaQuery()
                .eq(SiteRentBill::getContractId, contractId)
                .eq(SiteRentBill::getBillMonth, billMonth)
                .ne(SiteRentBill::getStatus, "VOID"));
    }
}
