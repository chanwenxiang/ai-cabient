package com.aicabinet.common.dto;

/**
 * admin 单柜改价请求（V350，CB-037）。
 *
 * @param priceCents      null=清除覆盖价（恢复跟随基准）；否则新覆盖价（限价校验见 SkuPriceBounds）
 * @param enabled         覆盖价启用开关（CB-037 语义 C）；null=保持原值，新建覆盖时缺省 true
 * @param expectedVersion 乐观锁：已有覆盖价时必填且须匹配，否则 409
 */
public record UpdateDeviceSkuPriceRequest(
        Integer priceCents,
        Boolean enabled,
        Long expectedVersion
) {}
