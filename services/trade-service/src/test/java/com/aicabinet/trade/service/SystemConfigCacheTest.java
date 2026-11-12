package com.aicabinet.trade.service;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.config.PayScoreProperties;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.config.SecurityProperties;
import com.aicabinet.trade.config.WeChatMiniAppProperties;
import com.aicabinet.trade.config.WeChatPayProperties;
import com.aicabinet.trade.config.WeChatWebProperties;
import com.aicabinet.trade.domain.SystemConfig;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.SystemConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P3-7：配置读缓存（进程内 TTL）——「每单 N 次查库」的根因修复验证。
 *
 * <p>守住两条不变式：</p>
 * <ol>
 *   <li><b>同一键多次读只落一次库</b>：{@code getValue/getInt/getBoolean/getDouble} 全部汇聚到
 *       {@code loadConfigCached}，第一次读库、其余命中内存缓存；把「每单结算 7 次查库」压到首读一次。</li>
 *   <li><b>写后精准失效</b>：{@code upsert/delete} 后同键再次读必须回源DB拿到新值，不能停留在 TTL 内的旧值。</li>
 * </ol>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SystemConfigCacheTest {

    private static final String KEY = "pricing.time_window.discount_percent";
    private static final String KEY2 = "recharge.max_cents";

    @Mock private SystemConfigMapper repository;
    @Mock private SystemConfigHistoryMapper historyRepository;
    @Mock private AdminAuditService auditService;
    @Mock private DistributedLockService distributedLockService;

    private SystemConfigService service;
    /** 内存「数据库」：upsert/save 写回，findById 读取，便于模拟写后失效。 */
    private final Map<String, SystemConfig> db = new HashMap<>();

    @BeforeEach
    void setUp() {
        service = new SystemConfigService(
                repository,
                historyRepository,
                auditService,
                new SecurityProperties(false),
                new AlipayProperties(false, "", "", "", "", "", "", "", "", "", ""),
                new WeChatPayProperties(false, "", "", "", "", "", "", "", true),
                new PayScoreProperties(false, false, 550, false, "", "", "", ""),
                new WeChatWebProperties(false, "", ""),
                new WeChatMiniAppProperties(false, "", "", "", "", "", "", "", "", "", ""),
                new QrProperties("", "", "", "", ""),
                distributedLockService,
                null);
        ReflectionTestUtils.setField(service, "self", service);

        db.clear();
        db.put(KEY, config(KEY, "30"));
        db.put(KEY2, config(KEY2, "500000"));
        lenient().when(repository.findById(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));
        lenient().when(repository.findByIdForUpdate(anyString()))
                .thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));
        lenient().when(repository.save(any()))
                .thenAnswer(inv -> {
                    SystemConfig c = inv.getArgument(0);
                    db.put(c.getConfigKey(), c);
                    return c;
                });
        // upsert/delete 包在 runWithConfigLock 内；放行分布式锁让写路径跑通
        lenient().when(distributedLockService.tryLock(anyString(), anyLong(), anyLong()))
                .thenReturn(true);
    }

    private static SystemConfig config(String key, String value) {
        SystemConfig c = new SystemConfig();
        c.setConfigKey(key);
        c.setConfigValue(value);
        return c;
    }

    @Test
    void sameKeyReadHitsDbOnceAcrossAllGetters() {
        // 首次读：落库一次
        assertEquals("30", service.getValue(KEY, "0"));
        verify(repository, times(1)).findById(KEY);

        // 同键再读：命中缓存，不再落库
        assertEquals("30", service.getValue(KEY, "0"));
        assertEquals(30, service.getInt(KEY, 0));
        service.getBoolean(KEY + ".bool", false); // 不同键，仅验证不影响 KEY 的 findById 计数
        verify(repository, times(1)).findById(KEY);

        // 另一键首次读：各自独立缓存，KEY 仍是 1 次
        assertEquals("500000", service.getValue(KEY2, "0"));
        assertEquals(500000, service.getInt(KEY2, 0));
        verify(repository, times(1)).findById(KEY);
        verify(repository, times(1)).findById(KEY2);
    }

    @Test
    void writeInvalidatesCacheSoNextReadSeesNewValue() {
        // 预热缓存
        assertEquals("30", service.getValue(KEY, "0"));
        verify(repository, times(1)).findById(KEY);

        // 模拟外部把库里值改成 99（未失效前仍应读缓存旧值）
        db.put(KEY, config(KEY, "99"));
        assertEquals("30", service.getValue(KEY, "0"));
        verify(repository, times(1)).findById(KEY);

        // 走正式写路径：upsert 内部用 findByIdForUpdate 而非 findById(KEY)，故 KEY 的 findById 计数不变
        service.upsert(KEY, "99", "changed by test");
        verify(repository, times(1)).findById(KEY);

        // 失效后再次读必须回源拿到新值
        assertEquals("99", service.getValue(KEY, "0"));
        verify(repository, times(2)).findById(KEY);
    }
}
