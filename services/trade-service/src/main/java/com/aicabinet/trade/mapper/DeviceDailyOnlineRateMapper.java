package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.DeviceDailyOnlineRate;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Mapper
public interface DeviceDailyOnlineRateMapper extends BaseTradeMapper<DeviceDailyOnlineRate> {

    /** 联合主键点查（实体无 @TableId，禁用 selectById）。 */
    default DeviceDailyOnlineRate findByDateAndDevice(LocalDate kpiDate, String deviceId) {
        return selectOne(Wrappers.<DeviceDailyOnlineRate>lambdaQuery()
                .eq(DeviceDailyOnlineRate::getKpiDate, kpiDate)
                .eq(DeviceDailyOnlineRate::getDeviceId, deviceId));
    }

    /** 幂等 upsert：联合主键判存，存在则按条件更新，否则插入。 */
    default void upsert(DeviceDailyOnlineRate row) {
        DeviceDailyOnlineRate existing = findByDateAndDevice(row.getKpiDate(), row.getDeviceId());
        if (existing == null) {
            insert(row);
        } else {
            update(row, Wrappers.<DeviceDailyOnlineRate>lambdaQuery()
                    .eq(DeviceDailyOnlineRate::getKpiDate, row.getKpiDate())
                    .eq(DeviceDailyOnlineRate::getDeviceId, row.getDeviceId()));
        }
    }

    /**
     * 近 N 天按设备的在线率均值（报表「近7日在线率」用）。
     * 行数 = 设备×天数（几百×7），量小，Java 侧聚合即可，不引自定义 SQL。
     * 返回 deviceId → 平均 rate；无样本的设备不出现。
     */
    default Map<String, Double> avgRateByDeviceSince(LocalDate fromDate) {
        List<DeviceDailyOnlineRate> rows = selectList(Wrappers.<DeviceDailyOnlineRate>lambdaQuery()
                .ge(DeviceDailyOnlineRate::getKpiDate, fromDate));
        Map<String, double[]> acc = new HashMap<>();
        for (DeviceDailyOnlineRate r : rows) {
            if (r.getDeviceId() == null || r.getRate() == null) {
                continue;
            }
            acc.computeIfAbsent(r.getDeviceId(), k -> new double[2]);
            double[] a = acc.get(r.getDeviceId());
            a[0] += r.getRate();
            a[1] += 1;
        }
        Map<String, Double> out = new HashMap<>();
        for (Map.Entry<String, double[]> e : acc.entrySet()) {
            if (e.getValue()[1] > 0) {
                out.put(e.getKey(), e.getValue()[0] / e.getValue()[1]);
            }
        }
        return out;
    }
}
