package com.aicabinet.trade.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderReadModelOpenApiCustomizerTest {

    @Test
    void restoresAdminAndMerchantSchemasAndFixesApiResponseRefs() {
        OpenAPI openApi = new OpenAPI().components(new Components());
        Map<String, Schema> schemas = new LinkedHashMap<>();

        Schema<?> full = new Schema<>().type("object");
        Map<String, Schema> fullProps = new LinkedHashMap<>();
        fullProps.put("orderId", new Schema<>().type("string"));
        fullProps.put("userId", new Schema<>().type("integer").format("int64"));
        fullProps.put("merchantId", new Schema<>().type("string"));
        fullProps.put("inventoryDeducted", new Schema<>().type("boolean"));
        fullProps.put("splitStatus", new Schema<>().type("string"));
        fullProps.put("balanceBeforeCents", new Schema<>().type("integer"));
        full.setProperties(fullProps);
        schemas.put("OrderReadModel", full);

        Schema<?> consumer = new Schema<>().type("object");
        Map<String, Schema> consumerProps = new LinkedHashMap<>();
        consumerProps.put("orderId", new Schema<>().type("string"));
        consumerProps.put("balanceBeforeCents", new Schema<>().type("integer"));
        consumer.setProperties(consumerProps);
        schemas.put("OrderReadModel_Consumer", consumer);

        schemas.put(
                "ApiResponseOrderReadModel_Admin",
                apiResponseRef("OrderReadModel_Consumer"));
        schemas.put(
                "ApiResponseOrderReadModel_Merchant",
                apiResponseRef("OrderReadModel_Consumer"));
        schemas.put(
                "ApiResponseOrderReadModel_Consumer",
                apiResponseRef("OrderReadModel_Consumer"));
        schemas.put(
                "ApiResponsePageResultOrderReadModel_Admin",
                apiResponseRef("PageResultOrderReadModel_Consumer"));
        schemas.put(
                "ApiResponsePageResultOrderReadModel_Merchant",
                apiResponseRef("PageResultOrderReadModel_Consumer"));
        schemas.put(
                "ApiResponsePageResultOrderReadModel_Consumer",
                apiResponseRef("PageResultOrderReadModel_Consumer"));

        openApi.getComponents().schemas(schemas);

        new OrderReadModelOpenApiCustomizer().customise(openApi);

        Schema<?> admin = schemas.get("OrderReadModel_Admin");
        Schema<?> merchant = schemas.get("OrderReadModel_Merchant");
        assertNotNull(admin);
        assertNotNull(merchant);
        assertTrue(admin.getProperties().containsKey("userId"));
        assertTrue(admin.getProperties().containsKey("inventoryDeducted"));
        assertTrue(merchant.getProperties().containsKey("merchantId"));
        assertTrue(merchant.getProperties().containsKey("splitStatus"));
        assertTrue(!merchant.getProperties().containsKey("userId"));

        assertEquals(
                "#/components/schemas/OrderReadModel_Admin",
                dataRef(schemas.get("ApiResponseOrderReadModel_Admin")));
        assertEquals(
                "#/components/schemas/OrderReadModel_Merchant",
                dataRef(schemas.get("ApiResponseOrderReadModel_Merchant")));
        assertEquals(
                "#/components/schemas/PageResultOrderReadModel_Admin",
                dataRef(schemas.get("ApiResponsePageResultOrderReadModel_Admin")));
        assertNotNull(schemas.get("PageResultOrderReadModel_Admin"));
        assertNotNull(schemas.get("PageResultOrderReadModel_Merchant"));
    }

    @SuppressWarnings("rawtypes")
    private static String dataRef(Schema apiResponse) {
        Object data = apiResponse.getProperties().get("data");
        assertTrue(data instanceof Schema, "data property should be Schema");
        return ((Schema<?>) data).get$ref();
    }

    private static Schema<?> apiResponseRef(String dataSchema) {
        Schema<?> api = new Schema<>().type("object");
        Map<String, Schema> props = new LinkedHashMap<>();
        props.put("code", new Schema<>().type("integer"));
        props.put("message", new Schema<>().type("string"));
        props.put("data", new Schema<>().$ref("#/components/schemas/" + dataSchema));
        api.setProperties(props);
        return api;
    }
}
