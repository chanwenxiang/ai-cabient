package com.aicabinet.trade.service;

import com.aicabinet.common.dto.MerchantTaxProfileDto;
import com.aicabinet.trade.auth.AccessDeniedAudit;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.InvoiceRequestMapper;
import com.aicabinet.trade.mapper.MerchantTaxProfileMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.MerchantPortalGuard;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * AU-087 负向验证：税资料读写必须过门户统一闸（MerchantPortalGuard）——
 * 「未绑定商户⇒全局态」短路曾是跨租户 BOLA 通道，守卫接入后未绑定账号
 * 对任意 merchantId 一律 403；绑定账号只许 own scope（含下级）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MerchantTaxProfileScopeTest {

    private static final long UNBOUND_USER = 5001L;   // 未绑定商户（现模型 ⇒ 全局态）
    private static final long BOUND_USER = 5002L;     // 绑定 M1
    private static final String M1 = "M1";
    private static final String M2 = "M2";

    @Mock private InvoiceRequestMapper invoiceRepository;
    @Mock private MerchantTaxProfileMapper taxProfileRepository;
    @Mock private CabinetOrderMapper orderRepository;
    @Mock private DeviceInfoMapper deviceRepository;
    @Mock private PermissionService permissionService;
    @Mock private MerchantScopeService merchantScopeService;
    @Mock private DistributedLockService distributedLockService;
    @Mock private CabinetMetrics cabinetMetrics;
    @Mock private AccessDeniedAudit accessDeniedAudit;

    private InvoiceService service;

    @BeforeEach
    void setUp() {
        // 真守卫+假 scope：还原「未绑定⇒全局」模型（MerchantScopeService:76-89 语义）
        MerchantPortalGuard guard = new MerchantPortalGuard(
                permissionService, merchantScopeService, cabinetMetrics, accessDeniedAudit);
        service = new InvoiceService(invoiceRepository, taxProfileRepository, orderRepository,
                deviceRepository, permissionService, merchantScopeService, distributedLockService, guard);

        when(merchantScopeService.isGlobalScope(UNBOUND_USER)).thenReturn(true);
        when(merchantScopeService.isGlobalScope(BOUND_USER)).thenReturn(false);
        when(merchantScopeService.allowedMerchantIds(UNBOUND_USER)).thenReturn(Set.of(M1, M2));
        when(merchantScopeService.allowedMerchantIds(BOUND_USER)).thenReturn(Set.of(M1));
        when(distributedLockService.tryLock(anyString(), anyLong(), anyLong())).thenReturn(true);
        when(taxProfileRepository.findByIdForUpdate(any())).thenReturn(Optional.empty());
        when(taxProfileRepository.selectById(any())).thenReturn(null);
    }

    private static MerchantTaxProfileDto body(String merchantId) {
        return new MerchantTaxProfileDto(merchantId, "某公司", "91500000MA0000000X",
                null, null, null, null);
    }

    @Test
    void saveTaxProfile_unboundGlobalAccount_foreignMerchant_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveTaxProfile(UNBOUND_USER, body(M2)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void getTaxProfile_unboundGlobalAccount_foreignMerchant_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.getTaxProfile(UNBOUND_USER, M2));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }

    @Test
    void saveTaxProfile_boundOwnMerchant_stillAllowed() {
        assertEquals(M1, service.saveTaxProfile(BOUND_USER, body(M1)).merchantId());
    }

    @Test
    void saveTaxProfile_boundForeignMerchant_forbidden() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.saveTaxProfile(BOUND_USER, body(M2)));
        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
    }
}
