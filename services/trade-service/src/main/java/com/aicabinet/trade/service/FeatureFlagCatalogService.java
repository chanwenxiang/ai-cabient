package com.aicabinet.trade.service;

import com.aicabinet.common.dto.FeatureFlagCatalogDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/**
 * 功能开关注册表（{@code ops/feature-flags.json}）的读取入口。
 *
 * <p>这份清单纯属**元数据**，不是第二份真相：键的读写仍以
 * {@link SystemConfigService} 为准。它的职责是让运营台能「一眼看全所有开关」，
 * 并按类型渲染合适的控件（开关 / 数字 / 文本 / 下拉）。</p>
 *
 * <p>一致性由构建期门禁 {@code scripts/check-feature-flags.mjs} 双向守卫
 * （seed ↔ 注册表 ↔ 读取点），所以运行期只做「解析 + 缓存」，
 * 且刻意**不吞异常**：文件坏掉就要立刻 500 暴露，而不是悄悄返回一个空面板
 * （空面板会被读成「系统没有开关」，那是最坏的一种骗读者）。</p>
 */
@Service
public class FeatureFlagCatalogService {

    private static final Logger log = LoggerFactory.getLogger(FeatureFlagCatalogService.class);

    /** 注册表资源路径，与门禁里的 REGISTRY_REL 指向同一份文件。 */
    static final String CATALOG_RESOURCE = "ops/feature-flags.json";

    private final ObjectMapper objectMapper;
    private volatile FeatureFlagCatalogDto cached;

    public FeatureFlagCatalogService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 启动时预热并留痕，避免第一次打开面板才发现资源缺失。 */
    @PostConstruct
    void warmUp() {
        try {
            FeatureFlagCatalogDto catalog = catalog();
            log.info("功能开关注册表已加载：{} 个开关 / {} 个分组",
                    catalog.flags() == null ? 0 : catalog.flags().size(),
                    catalog.groups() == null ? 0 : catalog.groups().size());
        } catch (RuntimeException e) {
            // 不让元数据问题拖垮整个应用启动，但必须留下 ERROR 级痕迹
            log.error("功能开关注册表加载失败（运营台「功能开关」面板将不可用）: {}", e.toString(), e);
        }
    }

    public FeatureFlagCatalogDto catalog() {
        FeatureFlagCatalogDto current = cached;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (cached == null) {
                cached = load();
            }
            return cached;
        }
    }

    private FeatureFlagCatalogDto load() {
        ClassPathResource resource = new ClassPathResource(CATALOG_RESOURCE);
        if (!resource.exists()) {
            throw new IllegalStateException("classpath 下找不到功能开关注册表: " + CATALOG_RESOURCE);
        }
        try (InputStream in = resource.getInputStream()) {
            FeatureFlagCatalogDto parsed = objectMapper.readValue(in, FeatureFlagCatalogDto.class);
            if (parsed.flags() == null || parsed.flags().isEmpty()) {
                throw new IllegalStateException(
                        "功能开关注册表解析出 0 个开关，" + CATALOG_RESOURCE + " 可能已损坏");
            }
            return parsed;
        } catch (IOException e) {
            throw new UncheckedIOException("解析功能开关注册表失败: " + CATALOG_RESOURCE, e);
        }
    }
}
