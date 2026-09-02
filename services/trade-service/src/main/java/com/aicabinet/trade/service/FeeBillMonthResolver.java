package com.aicabinet.trade.service;

import com.aicabinet.trade.config.FeeBillProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.DateTimeException;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** 周期费用账期解析：请求显式账期优先，否则按配置偏移推算。 */
@Component
public class FeeBillMonthResolver {

    private final FeeBillProperties properties;

    public FeeBillMonthResolver(FeeBillProperties properties) {
        this.properties = properties;
    }

    public ZoneId zoneId() {
        try {
            return ZoneId.of(properties.zone());
        } catch (DateTimeException e) {
            return ZoneId.of("Asia/Shanghai");
        }
    }

    /** 解析账期；blank 时用「今天 + offsetMonths」。 */
    public String resolve(String billMonthOrBlank) {
        if (billMonthOrBlank != null && !billMonthOrBlank.isBlank()) {
            return requireValid(billMonthOrBlank.trim());
        }
        return YearMonth.now(zoneId()).plusMonths(properties.billMonthOffsetMonths()).toString();
    }

    public String requireValid(String billMonth) {
        try {
            return YearMonth.parse(billMonth).toString();
        } catch (DateTimeParseException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "billMonth 须为 YYYY-MM");
        }
    }

    public int clampPageSize(Integer size) {
        int s = size == null ? properties.defaultPageSize() : size;
        return Math.min(properties.maxPageSize(), Math.max(1, s));
    }

    public int clampPage(Integer page) {
        return page == null || page < 1 ? 1 : page;
    }
}
