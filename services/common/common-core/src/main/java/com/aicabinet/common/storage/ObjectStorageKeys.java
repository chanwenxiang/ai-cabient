package com.aicabinet.common.storage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MinIO / OSS 对象键命名规范。
 *
 * <p>旧购物录像键（{@code videos/}、{@code sim/}、{@code archive/}）随 DC-27(b) 批6
 * 一起删除——将邑柜录像走 OSS 台账（{@code jiangyi_order_video}），本类只服务
 * MinIO 上的证据图 / 媒体资产 / 品牌素材。
 *
 * <pre>
 * dispute-evidence/{yyyy}/{MM}/{dd}/user-{userId}/{fileToken}.{ext}
 * replenishment-evidence/... | replenishment-request/... | stocktake-evidence/...
 * device-stocktake-evidence/... | sku-images/... | ops-avatars/... | ops-brand/...
 * </pre>
 */
public final class ObjectStorageKeys {
    private static final String V_04D_02D_02D = "%04d/%02d/%02d";

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._-]+");

    private ObjectStorageKeys() {
    }

    /** 消费者申诉证据图。 */
    public static String disputeEvidenceKey(long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "dispute-evidence/%s/user-%d/%s%s", date, userId, token, ext);
    }

    /** 补货现场取证图。 */
    public static String replenishmentEvidenceKey(long taskId, long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(
                Locale.ROOT, "replenishment-evidence/%s/task-%d/user-%d/%s%s", date, taskId, userId, token, ext);
    }


    /** 商户要货申请附图（已绑定要货单）。 */
    public static String replenishmentRequestEvidenceKey(long requestId, long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(
                Locale.ROOT, "replenishment-request/%s/request-%d/user-%d/%s%s", date, requestId, userId, token, ext);
    }

    /** 仓库盘点现场取证图（商户小程序 V349）。 */
    public static String stocktakeEvidenceKey(long stocktakeId, long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(
                Locale.ROOT, "stocktake-evidence/%s/stocktake-%d/user-%d/%s%s", date, stocktakeId, userId, token, ext);
    }

    /** 柜机扫码盘点凭证（CB-050 扩展）：按柜机归档，url 回填 photoEvidenceUrl。 */
    public static String deviceStocktakeEvidenceKey(String deviceId, long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(
                Locale.ROOT, "device-stocktake-evidence/%s/%s/user-%d/%s%s",
                date, sanitize(deviceId, "device"), userId, token, ext);
    }

    /** 商品主图（运营后台上传）。 */
    public static String skuImageKey(long operatorId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "sku-images/%s/op-%d/%s%s", date, operatorId, token, ext);
    }

    /** 运营账号头像 / Logo。 */
    public static String opsAvatarKey(long operatorId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "ops-avatars/%s/op-%d/%s%s", date, operatorId, token, ext);
    }

    /** 运营后台品牌 Logo。 */
    public static String opsBrandLogoKey(long operatorId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, V_04D_02D_02D, zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "ops-brand/%s/op-%d/%s%s", date, operatorId, token, ext);
    }

    private static String normalizeExtension(String extension) {
        if (extension == null || extension.isBlank()) {
            return ".mp4";
        }
        String ext = extension.trim().toLowerCase(Locale.ROOT);
        return ext.startsWith(".") ? ext : "." + ext;
    }

    private static String sanitize(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String cleaned = UNSAFE.matcher(value.trim()).replaceAll("_");
        return cleaned.isBlank() ? fallback : cleaned;
    }
}
