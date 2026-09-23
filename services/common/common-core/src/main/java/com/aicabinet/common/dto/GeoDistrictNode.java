package com.aicabinet.common.dto;

/**
 * 行政区划节点（高德「行政区划查询」）。
 *
 * <p>只暴露级联选择需要的三个字段：名称、adcode、层级。
 * 之所以要把 {@code adcode} 交给前端：它是**地理编码唯一的收敛依据** ——
 * 实测（2026-09-23）不带 adcode/city 时，「万达广场」会被解析到
 * 四川省南充市南部县、「测试门店」会被解析到广东省梅州市兴宁市，
 * 而带上 {@code city=310000} 后同一串「万达广场」稳定命中上海市杨浦区。
 *
 * @param adcode 行政区划编码（省/市/区三级）
 * @param name   名称，如「浙江省」「宁波市」「海曙区」
 * @param level  高德层级：province / city / district
 */
public record GeoDistrictNode(String adcode, String name, String level) {}
