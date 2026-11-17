package com.aicabinet.trade.service;

import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.ReplenishmentFulfillmentTaskDto;
import com.aicabinet.common.dto.ReplenishmentRouteDto;
import com.aicabinet.trade.support.ApiMessages;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * R23X C43：补货三个列表（路线 / 履约任务 / 到期下架）+ 库存台账必须把**账号设备范围下推**，
 * 而不是把 {@code operatorId} 收下再丢掉。
 *
 * <p>为什么必须钉「下推的参数长什么样」而不只钉「没抛异常」：修复前的形态是
 * {@code listRoutesPage(deviceId, page, size)}——签名里根本没有范围位，任何断言都会绿。
 * 现在钉的是「传给下层的是 pushdownDeviceIds 的返回值」，所以把范围解析改成 {@code null}
 * 或改成原始 deviceId 字符串都会红。
 */
@ExtendWith(MockitoExtension.class)
class OpsReplenishmentScopePushdownTest {

    private static final long OPERATOR = 100000003L;

    @Mock private PermissionService permissionService;
    @Mock private ReplenishmentService replenishmentService;
    @Mock private InventoryOpsService inventoryOpsService;
    @Mock private DeviceSlotService deviceSlotService;
    @Mock private MerchantReplenishmentService merchantReplenishmentService;
    @Mock private MerchantScopeService merchantScopeService;

    private OpsReplenishmentAdminService service;

    @BeforeEach
    void setUp() {
        service = new OpsReplenishmentAdminService(permissionService, replenishmentService,
                inventoryOpsService, deviceSlotService, merchantReplenishmentService, merchantScopeService);
    }

    @Test
    @DisplayName("路线分页：范围集合原样下推，不退回单值 deviceId")
    void listRoutesPage_pushesDownScopeSet() {
        when(merchantScopeService.pushdownDeviceIds(OPERATOR, "DEV-1")).thenReturn(List.of("DEV-1"));
        when(replenishmentService.listRoutesPage(List.of("DEV-1"), 0, 20))
                .thenReturn(new PageResult<ReplenishmentRouteDto>(List.of(), 0, 20, 0));

        service.listRoutesPage(OPERATOR, "DEV-1", 0, 20);

        verify(replenishmentService).listRoutesPage(List.of("DEV-1"), 0, 20);
    }

    @Test
    @DisplayName("履约任务分页：不传柜号时下推的是「我的可见柜机」集合（不是 null=全平台）")
    void listFulfillmentTasksPage_pushesDownAllowedDevices() {
        Set<String> allowed = Set.of("DEV-A", "DEV-B");
        when(merchantScopeService.pushdownDeviceIds(OPERATOR, null)).thenReturn(allowed);
        when(replenishmentService.listFulfillmentTasksPage(allowed, "OPEN", 0, 20))
                .thenReturn(new PageResult<ReplenishmentFulfillmentTaskDto>(List.of(), 0, 20, 0));

        service.listFulfillmentTasksPage(OPERATOR, null, "OPEN", 0, 20);

        verify(replenishmentService).listFulfillmentTasksPage(allowed, "OPEN", 0, 20);
    }

    @Test
    @DisplayName("到期下架分页：越权柜号 403 且不触发任何取数（不泄露「这柜有没有告警」）")
    void listExpiryAlertsPage_deniesOutOfScopeDeviceBeforeQuery() {
        when(merchantScopeService.pushdownDeviceIds(OPERATOR, "DEV-OTHER"))
                .thenThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.listExpiryAlertsPage(OPERATOR, "DEV-OTHER", 0, 20));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(replenishmentService);
    }

    @Test
    @DisplayName("库存台账：全局账号（null=不限）保持不限，别把 null 当成「无可见柜机」返回空")
    void listInventory_keepsNullForGlobalScope() {
        when(merchantScopeService.pushdownDeviceIds(OPERATOR, null)).thenReturn(null);
        when(replenishmentService.listInventory(null, true)).thenReturn(List.of());

        service.listInventory(OPERATOR, null, true);

        verify(replenishmentService).listInventory(null, true);
    }

    @Test
    @DisplayName("履约任务分页：范围为空 ⇒ 空集下推（不是空指针，也不是全量）")
    void listFulfillmentTasksPage_emptyScopePushesEmptySet() {
        when(merchantScopeService.pushdownDeviceIds(OPERATOR, null)).thenReturn(Set.of());
        when(replenishmentService.listFulfillmentTasksPage(Set.of(), null, 0, 20))
                .thenReturn(new PageResult<ReplenishmentFulfillmentTaskDto>(List.of(), 0, 20, 0));

        var page = service.listFulfillmentTasksPage(OPERATOR, null, null, 0, 20);

        assertEquals(0, page.total());
        verify(replenishmentService).listFulfillmentTasksPage(Set.of(), null, 0, 20);
        verify(replenishmentService, never()).listRoutesPage(null, 0, 20);
    }

    // ── N-01 邻接（2026-11-17 F2）：三个**写**入口的请求体 deviceId 必须过范围闸 ──────
    // 为什么读面钉下推、写面必须单独钉：读面越权只是看不见，写面越权是**改别人的账/派别人的单**。
    // 这三个端点的 deviceId 都在 @RequestBody 里，OpsDeviceScopeInterceptor 只拦路径变量，
    // 所以「service 入口有没有调闸」是唯一可钉的地方。

    @Test
    @DisplayName("库存台账直写：越权柜号 403，且一次写都不发生")
    void upsertInventory_deniesOutOfScopeDeviceBeforeWrite() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED))
                .when(merchantScopeService).requireDeviceFilter(OPERATOR, "DEV-OTHER");
        var body = new com.aicabinet.common.dto.DeviceInventoryDto(
                "DEV-OTHER", "SKU-1", 10, 20, 3, null, 0L, null, null, null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.upsertInventory(OPERATOR, body));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(replenishmentService);
    }

    @Test
    @DisplayName("建议建单：越权柜号 403，不生成路线也不派单")
    void createTaskFromSuggestion_deniesOutOfScopeDevice() {
        org.mockito.Mockito.doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED))
                .when(merchantScopeService).requireDeviceFilter(OPERATOR, "DEV-OTHER");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.createTaskFromSuggestion(OPERATOR, "DEV-OTHER", 100000009L));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        verifyNoInteractions(replenishmentService);
    }

    @Test
    @DisplayName("路线规划：批量柜号逐台过闸，越权一台即整体拒绝（不静默裁剪）")
    void planRoute_deniesWhenAnyDeviceOutOfScope() {
        // lenient：本用例先以 DEV-OK 调用同一个桩（严格模式下会被判成「桩参数不匹配」）
        org.mockito.Mockito.lenient()
                .doThrow(new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED))
                .when(merchantScopeService).requireDeviceFilter(OPERATOR, "DEV-OTHER");
        var body = new com.aicabinet.common.dto.PlanRouteRequest(
                "路线A", 100000009L, null, List.of("DEV-OK", "DEV-OTHER"), 1.0, 2.0);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.planRoute(OPERATOR, body));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        // 范围内的那台也要被检查过（顺序：先 OK 后 OTHER），且整条路线一条都不落库
        verify(merchantScopeService).requireDeviceFilter(OPERATOR, "DEV-OK");
        verifyNoInteractions(replenishmentService);
    }
}
