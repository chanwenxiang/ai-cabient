package com.aicabinet.trade.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.ArraySchema;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * springdoc / swagger-core 在 Boot 3.5 后会把 {@code OrderReadModel} 的 Admin/Merchant
 * {@code @JsonView} 错误折叠进 {@code OrderReadModel_Consumer}，导致组件里缺失
 * {@code OrderReadModel_Admin}/{@code OrderReadModel_Merchant}，前端别名生成失败。
 * <p>
 * 按 {@link com.aicabinet.common.dto.OrderReadModel} 字段上的 JsonView 从全量 schema
 * 裁剪并写回正确组件，同时修正 ApiResponse / PageResult 的 $ref。
 */
@Component
public class OrderReadModelOpenApiCustomizer implements OpenApiCustomizer {

    private static final String FULL = "OrderReadModel";
    private static final String ADMIN = "OrderReadModel_Admin";
    private static final String MERCHANT = "OrderReadModel_Merchant";
    private static final String CONSUMER = "OrderReadModel_Consumer";

    /** 与 OrderReadModel 上 Admin 可见字段对齐（含 Public）。 */
    private static final Set<String> ADMIN_PROPS = Set.of(
            "orderId",
            "sessionId",
            "userId",
            "deviceId",
            "merchantId",
            "deviceName",
            "merchantName",
            "totalAmountCents",
            "originalAmountCents",
            "couponDiscountCents",
            "memberDiscountCents",
            "status",
            "payChannel",
            "lineCount",
            "lineSummary",
            "payTradeNo",
            "paymentOperationId",
            "refundedAt",
            "refundedCents",
            "inventoryDeducted",
            "refundPolicy",
            "createdAt",
            "paidAt",
            "splitStatus",
            "lines",
            "balanceBeforeCents",
            "balanceAfterCents");

    /** 与 OrderReadModel 上 Merchant 可见字段对齐（含 Public）。 */
    private static final Set<String> MERCHANT_PROPS = Set.of(
            "orderId",
            "sessionId",
            "deviceId",
            "merchantId",
            "deviceName",
            "merchantName",
            "totalAmountCents",
            "originalAmountCents",
            "couponDiscountCents",
            "memberDiscountCents",
            "status",
            "payChannel",
            "lineCount",
            "lineSummary",
            "payTradeNo",
            "paymentOperationId",
            "refundedAt",
            "refundedCents",
            "refundPolicy",
            "createdAt",
            "paidAt",
            "splitStatus",
            "lines");

    @Override
    public void customise(OpenAPI openApi) {
        Components components = openApi.getComponents();
        if (components == null) {
            return;
        }
        Map<String, Schema> schemas = components.getSchemas();
        if (schemas == null || schemas.isEmpty()) {
            return;
        }

        Schema<?> full = schemas.get(FULL);
        if (full == null || full.getProperties() == null || full.getProperties().isEmpty()) {
            return;
        }

        schemas.put(ADMIN, subsetSchema(full, ADMIN, ADMIN_PROPS));
        schemas.put(MERCHANT, subsetSchema(full, MERCHANT, MERCHANT_PROPS));
        // Consumer 若已被错误折叠，用全量裁剪覆盖，保证字段集正确
        if (!schemas.containsKey(CONSUMER) || isLikelyCollapsed(schemas.get(CONSUMER), full)) {
            Set<String> consumerProps = new LinkedHashSet<>(MERCHANT_PROPS);
            consumerProps.remove("merchantId");
            consumerProps.remove("splitStatus");
            consumerProps.add("balanceBeforeCents");
            consumerProps.add("balanceAfterCents");
            schemas.put(CONSUMER, subsetSchema(full, CONSUMER, consumerProps));
        }

        schemas.put("PageResultOrderReadModel_Admin", pageResultSchema(ADMIN));
        schemas.put("PageResultOrderReadModel_Merchant", pageResultSchema(MERCHANT));
        if (!schemas.containsKey("PageResultOrderReadModel_Consumer")) {
            schemas.put("PageResultOrderReadModel_Consumer", pageResultSchema(CONSUMER));
        } else {
            setItemsRef(schemas.get("PageResultOrderReadModel_Consumer"), CONSUMER);
        }

        setDataRef(schemas.get("ApiResponseOrderReadModel_Admin"), ADMIN);
        setDataRef(schemas.get("ApiResponseOrderReadModel_Merchant"), MERCHANT);
        setDataRef(schemas.get("ApiResponseOrderReadModel_Consumer"), CONSUMER);
        setDataRef(schemas.get("ApiResponsePageResultOrderReadModel_Admin"), "PageResultOrderReadModel_Admin");
        setDataRef(schemas.get("ApiResponsePageResultOrderReadModel_Merchant"), "PageResultOrderReadModel_Merchant");
        setDataRef(schemas.get("ApiResponsePageResultOrderReadModel_Consumer"), "PageResultOrderReadModel_Consumer");
    }

    /**
     * 若 Consumer schema 误含 Admin 专有字段（userId），视为折叠损坏，需重建。
     * 当前故障形态是 Admin/Merchant 引用 Consumer；Consumer 本身字段通常仍对。
     */
    private static boolean isLikelyCollapsed(Schema<?> consumer, Schema<?> full) {
        if (consumer == null || consumer.getProperties() == null) {
            return true;
        }
        // 正常 Consumer 不应有 userId / inventoryDeducted
        Map<String, Schema> props = consumer.getProperties();
        return props.containsKey("userId") || props.containsKey("inventoryDeducted");
    }

    private static Schema<?> subsetSchema(Schema<?> full, String name, Set<String> allowed) {
        Schema<?> out = new Schema<>();
        out.setType("object");
        out.setName(name);
        Map<String, Schema> props = new LinkedHashMap<>();
        for (Map.Entry<String, Schema> e : full.getProperties().entrySet()) {
            if (allowed.contains(e.getKey())) {
                props.put(e.getKey(), e.getValue());
            }
        }
        out.setProperties(props);
        if (full.getRequired() != null) {
            List<String> required = full.getRequired().stream().filter(allowed::contains).toList();
            if (!required.isEmpty()) {
                out.setRequired(required);
            }
        }
        return out;
    }

    private static Schema<?> pageResultSchema(String itemSchemaName) {
        Schema<?> page = new Schema<>();
        page.setType("object");
        Map<String, Schema> props = new LinkedHashMap<>();
        ArraySchema items = new ArraySchema();
        items.setItems(new Schema<>().$ref("#/components/schemas/" + itemSchemaName));
        props.put("items", items);
        props.put("page", new IntegerSchema().format("int32"));
        props.put("size", new IntegerSchema().format("int32"));
        props.put("total", new IntegerSchema().format("int64"));
        page.setProperties(props);
        return page;
    }

    @SuppressWarnings("rawtypes")
    private static void setDataRef(Schema schema, String targetSchemaName) {
        if (schema == null || schema.getProperties() == null) {
            return;
        }
        schema.getProperties().put("data", new Schema<>().$ref("#/components/schemas/" + targetSchemaName));
    }

    @SuppressWarnings("rawtypes")
    private static void setItemsRef(Schema pageResult, String itemSchemaName) {
        if (pageResult == null || pageResult.getProperties() == null) {
            return;
        }
        ArraySchema items = new ArraySchema();
        items.setItems(new Schema<>().$ref("#/components/schemas/" + itemSchemaName));
        pageResult.getProperties().put("items", items);
    }
}
