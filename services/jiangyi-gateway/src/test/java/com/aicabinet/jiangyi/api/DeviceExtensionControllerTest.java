package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import com.aicabinet.jiangyi.client.TradeInternalClient.FloorGoodsView;
import com.aicabinet.jiangyi.client.TradeInternalClient.MappingView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.ResourceAccessException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机补齐的设备面扩展端点单测（getFloorByDevice / getDeviceSellProductClassIds）。
 * <p>核心契约：补货表<b>空表也回 200+products:[]</b>（固件只按 {@code "status":200} 判成功，
 * 回 666/503 会把「没有在售商品」和「拉取失败」混成同一个 onFailed 分支），
 * 仅 trade 不可达回 503；整型字段缺失一律回 0（固件侧是 Java int，null 属未定义行为）。</p>
 */
class DeviceExtensionControllerTest {

    private static final String DEVICE_ID = "100000000001";

    private TradeInternalClient trade;
    private DeviceExtensionController controller;

    @BeforeEach
    void setUp() {
        trade = Mockito.mock(TradeInternalClient.class);
        controller = new DeviceExtensionController(trade);
    }

    private MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, DEVICE_ID);
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, "CQYB10720");
        return request;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> products(Map<String, Object> resp) {
        return (List<Map<String, Object>>) ((Map<String, Object>) resp.get("data")).get("products");
    }

    // ---------- floor/getFloorByDevice ----------

    @Test
    void floorGoodsRowsFollowFirmwareFieldNames() {
        Mockito.when(trade.listFloorGoods(DEVICE_ID)).thenReturn(List.of(
                new FloorGoodsView(7, 100013, "可乐 330ml", 350, 5)));

        Map<String, Object> resp = controller.getFloorByDevice(authedRequest());

        assertEquals(200, resp.get("status"));
        assertEquals(1, products(resp).size());
        assertEquals(7, products(resp).get(0).get("classId"));
        assertEquals(100013, products(resp).get(0).get("thirdPid"));
        assertEquals("可乐 330ml", products(resp).get(0).get("productName"));
        assertEquals(350, products(resp).get(0).get("salePrice"));
        assertEquals(5, products(resp).get(0).get("stock"));
    }

    @Test
    void nullIntegersBecomeZeroNotNull() {
        Mockito.when(trade.listFloorGoods(DEVICE_ID)).thenReturn(List.of(
                new FloorGoodsView(7, null, "可乐", null, null)));

        Map<String, Object> row = products(controller.getFloorByDevice(authedRequest())).get(0);

        assertEquals(0, row.get("thirdPid"));
        assertEquals(0, row.get("salePrice"));
        assertEquals(0, row.get("stock"));
    }

    @Test
    void emptyTableIs200WithEmptyProducts() {
        Mockito.when(trade.listFloorGoods(DEVICE_ID)).thenReturn(List.of());

        Map<String, Object> resp = controller.getFloorByDevice(authedRequest());

        assertEquals(200, resp.get("status"));
        assertTrue(products(resp).isEmpty(), "空表必须是 200+[]，不能与拉取失败混态");
    }

    @Test
    void tradeUnreachableIs503() {
        Mockito.when(trade.listFloorGoods(DEVICE_ID))
                .thenThrow(new ResourceAccessException("trade down"));

        Map<String, Object> resp = controller.getFloorByDevice(authedRequest());

        assertEquals(503, resp.get("status"));
    }

    @Test
    void rowWithoutClassIdIsDropped() {
        // classId 是固件表的 key（addGoods 按 classId 建索引），null 行下发出去没有意义
        Mockito.when(trade.listFloorGoods(DEVICE_ID)).thenReturn(List.of(
                new FloorGoodsView(null, 1, "无名", 100, 1),
                new FloorGoodsView(7, 2, "可乐", 350, 1)));

        assertEquals(1, products(controller.getFloorByDevice(authedRequest())).size());
    }

    // ---------- floor/getDeviceSellProductClassIds ----------

    @Test
    void sellClassIdsOnlyReturnActiveMappings() {
        Mockito.when(trade.listMappings(DEVICE_ID)).thenReturn(List.of(
                new MappingView(7, "可乐", "SKU-7", "ACTIVE", "MANUAL"),
                new MappingView(9, "雪碧", "SKU-9", "DISABLED", "MODEL_SYNC"),
                new MappingView(null, "脏行", "SKU-X", "ACTIVE", "MANUAL")));

        Map<String, Object> resp = controller.getDeviceSellProductClassIds(authedRequest());

        assertEquals(200, resp.get("status"));
        assertEquals(List.of(7), resp.get("data"));
    }

    @Test
    void noSellProductIs666LikeVendorCloud() {
        Mockito.when(trade.listMappings(DEVICE_ID)).thenReturn(List.of());

        Map<String, Object> resp = controller.getDeviceSellProductClassIds(authedRequest());

        assertEquals(666, resp.get("status"));
        assertInstanceOf(String.class, resp.get("data"));
    }

    @Test
    void sellClassIdsTradeFailureIs503NotEmptyArray() {
        // 2026-10-19 修订：吞成 200+[] 会让设备把「trade 挂了」读成「营业中但没商品」而不重试
        Mockito.when(trade.listMappings(DEVICE_ID)).thenThrow(new ResourceAccessException("trade down"));

        assertEquals(503, controller.getDeviceSellProductClassIds(authedRequest()).get("status"));
    }
}
