package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.PromotionActivity;
import com.aicabinet.trade.mapper.PromotionPlacementMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * 活动配置校验（CB-082 v1）：三种 SKU 级类型必填时间窗 / 参与商品 / 对应价格参数；
 * 范围 全部 / 指定柜机 / 指定商户（商户范围只对 SKU 级活动开放）；
 * 上线前必须有轮播横幅。历史券类活动（FULL_REDUCE 等）行为不变，避免存量数据被拦。
 */
@Service
public class PromotionActivityValidator {

    public static final Set<String> SKU_TYPES = Set.of(
            "SKU_REDUCE", "SKU_DISCOUNT", "SKU_FIXED_PRICE");
    private static final Set<String> SCOPES = Set.of("ALL", "SPECIFIC", "MERCHANT");

    private final ObjectMapper objectMapper;
    private final PromotionPlacementMapper placementRepository;

    public PromotionActivityValidator(ObjectMapper objectMapper,
                                      PromotionPlacementMapper placementRepository) {
        this.objectMapper = objectMapper;
        this.placementRepository = placementRepository;
    }

    public static boolean isSkuActivity(String type) {
        return type != null && SKU_TYPES.contains(type.trim().toUpperCase(Locale.ROOT));
    }

    /** 创建/编辑保存时校验（允许草稿状态，但 SKU 级活动的时间窗/商品/价格必须齐）。 */
    public void validateForSave(PromotionActivity a) {
        boolean sku = isSkuActivity(a.getActivityType());
        if (sku) {
            if (a.getStartTime() == null || a.getEndTime() == null) {
                throw badRequest("请填写活动时间范围");
            }
            if (!a.getEndTime().isAfter(a.getStartTime())) {
                throw badRequest("结束时间需晚于开始时间");
            }
            if (stringList(a, "skuIds").isEmpty()) {
                throw badRequest("请选择参与活动的商品");
            }
            validatePrice(a);
        }
        validateScope(a, sku);
        validateRuleNumbers(a);
    }

    /** 上线前校验：保存规则 + 轮播横幅必选。 */
    public void validateForLaunch(PromotionActivity a) {
        validateForSave(a);
        if (isSkuActivity(a.getActivityType())
                && placementRepository.countByActivityIdAndPosition(a.getActivityId(), "BANNER") < 1) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "请先配置轮播横幅图片再启用活动");
        }
    }

    private void validatePrice(PromotionActivity a) {
        String type = a.getActivityType().trim().toUpperCase(Locale.ROOT);
        switch (type) {
            case "SKU_REDUCE" -> {
                if (a.getReduceCents() == null || a.getReduceCents() < 1) {
                    throw badRequest("商品立减金额需大于 0");
                }
            }
            case "SKU_DISCOUNT" -> {
                Integer bps = a.getDiscountBps();
                if (bps == null || bps <= 0 || bps >= 10_000) {
                    throw badRequest("商品折扣需在 0-100% 之间（不含两端）");
                }
            }
            case "SKU_FIXED_PRICE" -> {
                if (a.getFixedPriceCents() == null || a.getFixedPriceCents() < 1) {
                    throw badRequest("一口价需大于 0（不允许 0 元购）");
                }
            }
            default -> throw badRequest("不支持的活动类型: " + a.getActivityType());
        }
    }

    private void validateScope(PromotionActivity a, boolean sku) {
        String scope = a.getDeviceScope() == null
                ? "ALL" : a.getDeviceScope().trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(scope)) {
            throw badRequest("未知的投放范围: " + a.getDeviceScope());
        }
        if ("SPECIFIC".equals(scope) && stringList(a, "deviceIds").isEmpty()) {
            throw badRequest("指定柜机时请至少选择一台柜机");
        }
        if ("MERCHANT".equals(scope)) {
            if (!sku) {
                throw badRequest("指定商户范围仅支持商品立减/商品折扣/一口价活动");
            }
            if (stringList(a, "merchantIds").isEmpty()) {
                throw badRequest("指定商户时请至少选择一个商户");
            }
        }
    }

    private void validateRuleNumbers(PromotionActivity a) {
        JsonNode node = rule(a);
        if (node == null) {
            return;
        }
        JsonNode total = node.get("totalOrderLimit");
        if (total != null && !total.isNull() && !total.isIntegralNumber()) {
            throw badRequest("活动总单数限制需为整数");
        }
    }

    /** rule_config 的字符串数组字段；缺失/类型不符按空处理（保存校验只拦「空」，不拦脏数据回显）。 */
    public java.util.List<String> stringList(PromotionActivity a, String field) {
        JsonNode node = rule(a);
        if (node == null) {
            return java.util.List.of();
        }
        JsonNode arr = node.get(field);
        if (arr == null || !arr.isArray()) {
            return java.util.List.of();
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode item : arr) {
            if (item != null && !item.isNull()) {
                String v = item.asText("").trim();
                if (!v.isEmpty()) {
                    out.add(v);
                }
            }
        }
        return out;
    }

    private JsonNode rule(PromotionActivity a) {
        String json = a.getRuleConfig();
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(json);
        } catch (Exception e) {
            throw badRequest("活动规则配置不是合法 JSON");
        }
    }

    private static ResponseStatusException badRequest(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
