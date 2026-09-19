package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FeatureFlagCatalogDto;
import com.aicabinet.common.dto.FeatureFlagDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 功能开关注册表的**真实加载**测试。
 *
 * <p>刻意不 mock：直接读 classpath 上的 {@code ops/feature-flags.json}，
 * 这样「资源没打进 jar」「字段改名导致解析出 0 条」「类型标注被写坏」都能在这里被拦住。</p>
 */
class FeatureFlagCatalogServiceTest {

    private FeatureFlagCatalogService service;

    @BeforeEach
    void setUp() {
        service = new FeatureFlagCatalogService(new ObjectMapper());
    }

    @Test
    void catalog_loadsRealResourceWithAllFlags() {
        FeatureFlagCatalogDto catalog = service.catalog();

        assertNotNull(catalog.flags());
        assertTrue(
                catalog.flags().size() >= 40,
                "注册表条目数断崖式下跌通常意味着解析或字段改名出了问题，实际=" + catalog.flags().size());
        assertNotNull(catalog.groups());
        assertFalse(catalog.groups().isEmpty(), "分组列表不能为空（运营台按它渲染顺序）");
    }

    @Test
    void catalog_keysAreUniqueAndGroupsAreDeclared() {
        FeatureFlagCatalogDto catalog = service.catalog();

        Set<String> seen = new HashSet<>();
        for (FeatureFlagDto flag : catalog.flags()) {
            assertNotNull(flag.key(), "键不能为空");
            assertTrue(seen.add(flag.key()), "键重复: " + flag.key());
            assertTrue(
                    catalog.groups().contains(flag.group()),
                    flag.key() + " 的分组「" + flag.group() + "」不在 groups 列表里");
        }
    }

    @Test
    void catalog_everyFlagCarriesRenderableMetadata() {
        FeatureFlagCatalogDto catalog = service.catalog();
        Set<String> allowed = Set.of("BOOLEAN", "NUMBER", "TEXT", "TEXTAREA", "SELECT");

        for (FeatureFlagDto flag : catalog.flags()) {
            assertNotNull(flag.type(), flag.key() + " 缺 type");
            assertTrue(allowed.contains(flag.type()), flag.key() + " 的 type 非法: " + flag.type());
            assertNotNull(flag.defaultValue(), flag.key() + " 缺默认值（default 字段映射可能漏了）");
            assertNotNull(flag.description(), flag.key() + " 缺说明");
            if ("BOOLEAN".equals(flag.type())) {
                assertTrue(
                        List.of("true", "false").contains(flag.defaultValue()),
                        flag.key() + " 是 BOOLEAN 但默认值不是 true/false: " + flag.defaultValue());
            }
            if ("SELECT".equals(flag.type())) {
                assertNotNull(flag.options(), flag.key() + " 是 SELECT 却没有 options");
                assertTrue(flag.options().size() >= 2, flag.key() + " 的 options 少于 2 项");
            }
        }
    }

    @Test
    void catalog_containsTheObservabilitySwitchesAddedByP06() {
        FeatureFlagCatalogDto catalog = service.catalog();

        FeatureFlagDto tracing = find(catalog, SystemConfigService.OPS_OBSERVABILITY_TRACING_ENABLED);
        // 三态（跟随/强制开/强制关）布尔表达不了第三态，所以登记为 SELECT 且默认值必须是可选项之一
        assertEquals("SELECT", tracing.type(), "追踪总开关是三态，应登记为 SELECT");
        assertEquals("", tracing.defaultValue(), "默认必须留空=跟随，保证零行为变化");
        assertNotNull(tracing.options(), "三态必须给出候选项，否则运营台只能手打字符串");
        List<String> optionValues = tracing.options().stream().map(o -> o.value()).toList();
        assertEquals(List.of("", "true", "false"), optionValues, "三态选项应为 跟随/强制开/强制关");
        assertTrue(optionValues.contains(tracing.defaultValue()), "默认值必须是可选项之一");

        FeatureFlagDto endpoint = find(catalog, SystemConfigService.OPS_OBSERVABILITY_OTLP_ENDPOINT);
        assertEquals("TEXT", endpoint.type());
        assertEquals("", endpoint.defaultValue(), "默认留空=回退 OTLP_ENDPOINT，保证零行为变化");
    }

    @Test
    void catalog_marksLedgerRelatedRetentionSwitchAsDeprecated() {
        FeatureFlagCatalogDto catalog = service.catalog();

        FeatureFlagDto points = find(catalog, "ops.log_retention.points_months");
        assertTrue(points.deprecated(), "积分流水禁止删除，该开关必须标为废弃，否则运营台会误导运营去开它");
        assertNotNull(points.deprecatedNote());
        assertFalse(points.deprecatedNote().isBlank(), "废弃项必须写明原因");
    }

    @Test
    void catalog_cachesResultAndDoesNotReloadEachCall() {
        FeatureFlagCatalogDto first = service.catalog();
        FeatureFlagCatalogDto second = service.catalog();
        assertTrue(first == second, "注册表应在进程内缓存，避免每开一次面板都读盘解析");
    }

    private static FeatureFlagDto find(FeatureFlagCatalogDto catalog, String key) {
        return catalog.flags().stream()
                .filter(f -> key.equals(f.key()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("注册表里找不到开关: " + key));
    }
}
