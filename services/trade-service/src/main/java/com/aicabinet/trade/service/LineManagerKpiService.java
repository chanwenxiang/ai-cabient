package com.aicabinet.trade.service;

import com.aicabinet.common.dto.LineManagerKpiDto;
import com.aicabinet.trade.domain.LineCommissionDaily;
import com.aicabinet.trade.domain.LineDevice;
import com.aicabinet.trade.mapper.LineCommissionDailyMapper;
import com.aicabinet.trade.mapper.LineDeviceMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class LineManagerKpiService {

    private final LineCommissionDailyMapper commissionDailyMapper;
    private final LineDeviceMapper deviceMapper;
    private final LineManagerService lineManagerService;
    private final PermissionService permissionService;

    public LineManagerKpiService(LineCommissionDailyMapper commissionDailyMapper,
                                 LineDeviceMapper deviceMapper,
                                 LineManagerService lineManagerService,
                                 PermissionService permissionService) {
        this.commissionDailyMapper = commissionDailyMapper;
        this.deviceMapper = deviceMapper;
        this.lineManagerService = lineManagerService;
        this.permissionService = permissionService;
    }

    @Transactional(readOnly = true)
    public LineManagerKpiDto kpi(Long operatorId, Long managerId, LocalDate from, LocalDate to) {
        permissionService.requireAnyPermission(operatorId, "ops:line-manager:list", "ops:finance:view");
        lineManagerService.requireManager(managerId);
        LocalDate end = to == null ? LocalDate.now() : to;
        LocalDate start = from == null ? end.minusDays(29) : from;
        if (start.isAfter(end)) {
            LocalDate tmp = start;
            start = end;
            end = tmp;
        }
        List<LineCommissionDaily> rows = commissionDailyMapper.selectList(
                Wrappers.<LineCommissionDaily>lambdaQuery()
                        .eq(LineCommissionDaily::getManagerId, managerId)
                        .ge(LineCommissionDaily::getBizDate, start)
                        .le(LineCommissionDaily::getBizDate, end));
        Map<LocalDate, LineManagerKpiDto.Daily> byDay = new LinkedHashMap<>();
        long gmv = 0;
        long commission = 0;
        for (LineCommissionDaily row : rows) {
            long dayGmv = row.getGmvCents() == null ? 0 : row.getGmvCents();
            long dayComm = row.getCommissionCents() == null ? 0 : row.getCommissionCents();
            int orders = row.getOrderCount() == null ? 0 : row.getOrderCount();
            gmv += dayGmv;
            commission += dayComm;
            byDay.merge(row.getBizDate(),
                    new LineManagerKpiDto.Daily(row.getBizDate(), dayGmv, dayComm, orders),
                    (a, b) -> new LineManagerKpiDto.Daily(
                            a.bizDate(),
                            a.gmvCents() + b.gmvCents(),
                            a.commissionCents() + b.commissionCents(),
                            a.orderCount() + b.orderCount()));
        }
        int deviceCount = Math.toIntExact(deviceMapper.selectCount(Wrappers.<LineDevice>lambdaQuery()
                .eq(LineDevice::getManagerId, managerId)));
        List<LineManagerKpiDto.Daily> dailies = new ArrayList<>(byDay.values());
        dailies.sort((a, b) -> b.bizDate().compareTo(a.bizDate()));
        return new LineManagerKpiDto(managerId, start, end, gmv, commission, deviceCount,
                byDay.size(), dailies);
    }
}
