package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SkuSceneLimitItem;
import com.aicabinet.trade.mapper.DeviceSkuInventoryMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SkuSceneLimitMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * V404 场景字典后台可配的门语义回归钉：
 * ① 字典有 ACTIVE 项 ⇒ 以字典为准（运营新增 SCenic 可写入、停用 OFFICE 拒**新写入**）；
 * ② 字典无 ACTIVE 项 ⇒ 回落内置 9 值（fail-safe：不瘫痪业务、不放开任意值）；
 * ③ 停用只拦新写入（validateRequestItems / assertSceneSwitchAllowed 两个校验点），存量行不动。
 */
@ExtendWith(MockitoExtension.class)
class SkuSceneLimitDictGateTest {

    @Mock private SkuSceneLimitMapper limitRepository;
    @Mock private SkuCatalogMapper skuCatalogRepository;
    @Mock private DeviceSkuInventoryMapper inventoryRepository;
    @Mock private SysDictService sysDictService;

    private SkuSceneLimitService service;

    @BeforeEach
    void setUp() {
        service = new SkuSceneLimitService(limitRepository, skuCatalogRepository, inventoryRepository, sysDictService);
        // 默认桩：委托 fallback（与「字典空」等价，各用例再覆写）
        lenient().when(sysDictService.isKnownDictValue(eq(SysDictService.DEVICE_SCENE_TYPE), anyString(), anySet()))
                .thenAnswer(inv -> {
                    String v = inv.getArgument(1);
                    @SuppressWarnings("unchecked")
                    Set<String> fb = (Set<String>) inv.getArgument(2);
                    return v != null && fb.contains(v.trim());
                });
    }

    private void dictAllows(String... values) {
        when(sysDictService.isKnownDictValue(eq(SysDictService.DEVICE_SCENE_TYPE), anyString(), anySet()))
                .thenAnswer(inv -> List.of(values).contains(inv.getArgument(1)));
    }

    private static List<SkuSceneLimitItem> items(String scene, int maxQty) {
        return List.of(new SkuSceneLimitItem(scene, maxQty));
    }

    @Test
    void dictEmpty_fallsBackToBuiltinNine() {
        // 字典无 ACTIVE 项（hasActiveItems=false 的等价桩=回落分支）：内置 9 值可写、未知值拒
        assertDoesNotThrow(() -> service.validateRequestItems(items("GYM", 5)));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validateRequestItems(items("SCENIC", 5)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void dictActive_newConfiguredValueAccepted() {
        dictAllows("SCENIC", "OFFICE");
        assertDoesNotThrow(() -> service.validateRequestItems(items("SCENIC", 5)));
    }

    @Test
    void dictDeactivated_rejectsNewWrite_only() {
        // OFFICE 被运营停用 ⇒ 新写入拒
        dictAllows("SCENIC");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.validateRequestItems(items("OFFICE", 5)));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertEquals("未知投放场景：OFFICE", ex.getReason());
        // 「只拦新写入」的存量不动由本服务不提供任何存量改写 API 保证（replaceLimits 也走同一门 ⇒
        // 存量行仅在整单替换再触 OFFICE 时被拒；不触碰存量的路径不存在）
    }

    @Test
    void sceneSwitch_sameGate() {
        dictAllows("SCENIC");
        // 换场景白名单同一字典门（设备侧 update 路径）
        assertDoesNotThrow(() -> service.assertSceneSwitchAllowed("CAB-1", "SCENIC"));
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.assertSceneSwitchAllowed("CAB-1", "OFFICE"));
        assertEquals("未知投放场景：OFFICE", ex.getReason());
    }
}
