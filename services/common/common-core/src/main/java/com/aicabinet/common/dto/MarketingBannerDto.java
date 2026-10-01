package com.aicabinet.common.dto;

public record MarketingBannerDto(
        Long id,
        String title,
        String subtitle,
        String tone,
        String emoji,
        Long campaignId,
        String ctaPath,
        /** P3-6：广告轮播位的图片直链（同源媒体代理）；活动横幅为 null */
        String imageUrl,
        /** P3-6：广告投放计划 id（曝光/点击留痕用）；活动横幅为 null */
        Long adCampaignId,
        /** P3-6：广告素材 id（留痕用）；活动横幅为 null */
        Long assetId
) {
    public MarketingBannerDto(Long id, String title, String subtitle, String tone, String emoji,
                              Long campaignId, String ctaPath) {
        this(id, title, subtitle, tone, emoji, campaignId, ctaPath, null, null, null);
    }
}
