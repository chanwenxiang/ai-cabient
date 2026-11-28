package com.aicabinet.trade.api;

import com.aicabinet.trade.config.GlobalExceptionHandler;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.AmapGeocodeService;
import com.aicabinet.trade.service.CompetitiveGapService;
import com.aicabinet.trade.service.DeviceAssetService;
import com.aicabinet.trade.service.FundBillService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 从 HTTP 出口验证缺 Key 的契约：状态探测成功，业务调用返回 409 + 中文，不返回 5xx/假数据。 */
@ExtendWith(MockitoExtension.class)
class OpsGapFeaturesGeoControllerTest {

    @Mock private FundBillService fundBillService;
    @Mock private CompetitiveGapService gapService;
    @Mock private DeviceAssetService deviceAssetService;
    @Mock private AdminAuditService auditService;

    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AmapGeocodeService geocodeService = new AmapGeocodeService("", new ObjectMapper());
        mvc = MockMvcBuilders.standaloneSetup(new OpsGapFeaturesController(
                        fundBillService, gapService, deviceAssetService, geocodeService, auditService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void geoStatus_reportsUnconfiguredAsSuccessfulReadOnlyProbe() throws Exception {
        mvc.perform(get("/api/v2/ops/admin/geo/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.configured").value(false));
    }

    @Test
    void geocode_missingKeyReturns409ChineseBusinessErrorAndNoData() throws Exception {
        mvc.perform(get("/api/v2/ops/admin/geo/geocode")
                        .param("address", "上海市杨浦区某门店")
                        .param("city", "310100")
                        .param("district", "310110"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("地址解析服务未启用，请手动填写地址和坐标"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AMAP_WEB_KEY"))));
    }

    @Test
    void districts_missingKeyReturns409ChineseBusinessErrorAndNoData() throws Exception {
        mvc.perform(get("/api/v2/ops/admin/geo/districts"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("行政区划服务未启用，请手动填写详细地址"))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("AMAP_WEB_KEY"))));
    }
}
