package com.aicabinet.common.dto;

/**
 * 地理编码结果（高德）。
 *
 * <p>除坐标与规范化地址外，还回带**高德认定的行政区划与匹配层级**，目的有两个：
 * <ol>
 *   <li>让运营在点「解析坐标」之后能一眼核对「它到底解析到哪儿了」——
 *       实测不带城市限定时「测试门店」会被解析到广东省梅州市兴宁市；</li>
 *   <li>{@code level} 用于识别**粗匹配**：{@code 省 / 市 / 区县} 级说明只匹配到了行政区中心点，
 *       不是门店级坐标，前端应提示补齐详细地址，而不是当作可用点位直接保存。</li>
 * </ol>
 *
 * @param longitude        经度
 * @param latitude         纬度
 * @param formattedAddress 高德规范化后的地址
 * @param adcode           解析结果所在行政区划编码
 * @param province         省
 * @param city             市
 * @param district         区/县
 * @param level            匹配层级：兴趣点 / 门址 / 道路 / 区县 / 市 / 省
 */
public record GeocodeResponse(
        Double longitude,
        Double latitude,
        String formattedAddress,
        String adcode,
        String province,
        String city,
        String district,
        String level) {}
