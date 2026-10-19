package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真机独有端点的兼容应答（逐条对齐 2026-10-19 log3 实测样本）。
 *
 * <p>本类锁的是<b>照抄准确性</b>：值一旦被人改成「我觉得应该怎样」，设备侧行为就偏离了
 * 云侧实测，而这套接口的唯一验收依据正是「与云侧同答」。证据计数写在注释里，改动须附新日志。</p>
 */
class DeviceCompatControllerTest {

    private final DeviceCompatController controller = new DeviceCompatController();
    private final HttpServletRequest request = authedRequest();

    private static MockHttpServletRequest authedRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID, "10720");
        request.setAttribute(DeviceAuthInterceptor.ATTR_IDENTIFIER, "CQYB10720");
        return request;
    }

    @Test
    void needCheckAnswersFalseLikeVendorCloud() {
        assertEquals(Map.of("status", 200, "msg", "success", "data", false), controller.needCheck(request));
    }

    @Test
    void tenantPidAndClassIdAnswersEmptyObjectNotNull() {
        Map<String, Object> resp = controller.tenantPidAndClassId(request);
        assertEquals(200, resp.get("status"));
        assertEquals(Map.of(), resp.get("data"));
    }

    @Test
    void getOtgStatusAndPicSwitchAnswerMeasuredStrings() {
        assertEquals("open", controller.getOtgStatus(request).get("data"));
        assertEquals("on", controller.getPicSwitch(request).get("data"));
    }

    @Test
    void pvalueConfigTablesAnswerNullWithMeasuredTolerance() {
        // 云侧对同一台设备回过 25 行表和 null（8/6 次样本）⇒ null 有实证容忍度，
        // 且调光表属厂商租户级标定数据，不复制进我方代码库
        assertNull(controller.getTenantPvalueConfig(request).get("data"));
        assertNull(controller.getTenantPvalueConfigClose(request).get("data"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void deviceBasicConfigReproducesMeasuredFieldSet() {
        Map<String, Object> resp = controller.getDeviceBasicConfig(request,
                Map.of("identifier", "CQYB10720", "doorNum", 0, "huodaoNum", 0, "modelNum", 0));
        Map<String, Object> cfg = (Map<String, Object>) resp.get("data");
        // log3 E/getDeviceBasicConfig 27 个样本的字段全集，一个不多一个不少
        assertEquals(List.of("dictValue", "lightAlwaysOn", "videoMappingMode", "imageUploadMode",
                        "isDeleteSourceImage", "isGetSdkVedio", "isUseTts", "dataAugmentationMode",
                        "moudleRunMode", "replenishmentVideoMode", "isShowAllLog", "isPlayVoice",
                        "debugStatus", "ttsModelUrl", "ttsModelVersion"),
                List.copyOf(cfg.keySet()));
        assertEquals("NORMAL", cfg.get("dictValue"));
        assertEquals(0, cfg.get("videoMappingMode"));
        assertEquals(0, cfg.get("isShowAllLog"));
    }

    @Test
    void baiduAudioCodeAnswersBusinessFailure666() {
        Map<String, Object> resp = controller.getBaiduAudioCode(request,
                Map.of("deviceSn", "YPKJ1JYKJ00000000000250"));
        assertEquals(666, resp.get("status"));
        assertTrue(((String) resp.get("msg")).contains("YPKJ1JYKJ00000000000250"));
    }

    @Test
    void uploadFamilyAnswersSuccessWithNullData() {
        Map<String, Object> body = Map.of("lockStatus", "success", "addGoodsId", "");
        assertNull(controller.uploadAddGoodsCloseStatus(request, body).get("data"));
        assertNull(controller.reportPValue(request, Map.of("pvalue", "i：2,c:3,")).get("data"));
        assertNull(controller.reportPValueByTransaction(request,
                Map.of("transactionId", "", "pvalue", "i：2,c:3,")).get("data"));
        assertNull(controller.selfCheckReport(request, Map.of("pass", true)).get("data"));
        assertNull(controller.reportDeviceStatus(request, Map.of("deviceStatus", 0)).get("data"));
        assertNull(controller.reportCameraRealInfo(request, Map.of("cameras", List.of())).get("data"));
        assertNull(controller.updateTrouble(request, Map.of("troubleStatus", "no")).get("data"));
        assertNull(controller.uploadDeviceDataCardNo(request, Map.of("cardNo", "898604")).get("data"));
        assertEquals(200, controller.uploadDeviceDataCardNo(request, Map.of("cardNo", "898604")).get("status"));
    }
}
