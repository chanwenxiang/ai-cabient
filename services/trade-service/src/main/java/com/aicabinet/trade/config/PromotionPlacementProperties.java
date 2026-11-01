package com.aicabinet.trade.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 活动展示位图片尺寸限制（CB-082 v1）。用户 2026-11-01 拍板「每个位置要一张图，还得限制图的尺寸」。
 *
 * <p>默认值按小程序展示比例给出：轮播横幅约 2.5:1（750×300），弹窗约 3:4（750×1000）。
 * 运营需要调整时改配置即可，不在代码里堆魔法数。
 */
@ConfigurationProperties(prefix = "aicabinet.promotion.placement")
public record PromotionPlacementProperties(Rule banner, Rule popup) {

    public PromotionPlacementProperties {
        banner = banner == null ? Rule.bannerDefault() : banner;
        popup = popup == null ? Rule.popupDefault() : popup;
    }

    public record Rule(int minWidth, int maxWidth, int minHeight, int maxHeight,
                       double minAspect, double maxAspect) {

        static Rule bannerDefault() {
            return new Rule(600, 2000, 200, 800, 1.8, 3.5);
        }

        static Rule popupDefault() {
            return new Rule(600, 1500, 800, 2000, 0.5, 0.9);
        }

        public boolean matches(Integer width, Integer height) {
            if (width == null || height == null || width <= 0 || height <= 0) {
                return false;
            }
            if (width < minWidth || width > maxWidth || height < minHeight || height > maxHeight) {
                return false;
            }
            double aspect = (double) width / height;
            return aspect >= minAspect && aspect <= maxAspect;
        }

        public String describe() {
            return String.format("宽 %d-%d、高 %d-%d、宽高比 %.1f-%.1f",
                    minWidth, maxWidth, minHeight, maxHeight, minAspect, maxAspect);
        }
    }
}
