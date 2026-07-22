package com.aicabinet.common.storage;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * MinIO / OSS 对象键命名规范。
 *
 * <p>购物录像在<strong>上传时刻</strong>尚未完成商品识别，因此对象键使用
 * {@code 日期 / 设备 / 用户 / 会话 / 机位}，商品信息保存在 DB（订单行、识别结果）中。
 *
 * <pre>
 * videos/{yyyy}/{MM}/{dd}/{deviceId}/user-{userId}/{sessionId}-{camera}.{ext}
 * sim/{yyyy}/{MM}/{dd}/{deviceId}/user-{userId}/{sessionId}-{camera}.{ext}   // 开发/模拟
 * archive/{yyyy}/{MM}/{dd}/{skuId}/user-{userId}/{sessionId}-{camera}.{ext}  // 结算后按 SKU 归档
 * </pre>
 */
public final class ObjectStorageKeys {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._-]+");

    private ObjectStorageKeys() {
    }

    /** 生产柜机购物录像。 */
    public static String shoppingVideoKey(String deviceId, long userId, String sessionId, String camera, String extension) {
        return prefix("videos", deviceId, userId, sessionId, camera, extension, Instant.now());
    }

    /** 开发模拟器上传（与生产结构一致，前缀 {@code sim} 便于区分）。 */
    public static String simMediaKey(String deviceId, long userId, String sessionId, String camera, String extension) {
        return prefix("sim", deviceId, userId, sessionId, camera, extension, Instant.now());
    }

    /** 指定时刻（测试 / 离线续传补传仍用关门当天日期时可传入原始时间戳）。 */
    public static String shoppingVideoKeyAt(
            String deviceId, long userId, String sessionId, String camera, String extension, Instant at) {
        return prefix("videos", deviceId, userId, sessionId, camera, extension, at);
    }

    /** 结算完成后按 SKU 归档（每个订单行 SKU 各复制一份，便于按商品检索录像）。 */
    public static String archiveVideoKey(
            String skuId, long userId, String sessionId, String camera, String extension, Instant at) {
        ZonedDateTime zdt = at.atZone(ZONE);
        String date = String.format(
                Locale.ROOT, "%04d/%02d/%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String safeSku = sanitize(skuId, "unknown-sku");
        String safeSession = sanitize(sessionId, "session");
        String cam = normalizeCamera(camera);
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "archive/%s/%s/user-%d/%s-%s%s", date, safeSku, userId, safeSession, cam, ext);
    }

    /** 消费者申诉证据图。 */
    public static String disputeEvidenceKey(long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, "%04d/%02d/%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "dispute-evidence/%s/user-%d/%s%s", date, userId, token, ext);
    }

    /** 补货现场取证图。 */
    public static String replenishmentEvidenceKey(long taskId, long userId, String fileToken, String extension) {
        ZonedDateTime zdt = Instant.now().atZone(ZONE);
        String date = String.format(
                Locale.ROOT, "%04d/%02d/%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String token = sanitize(fileToken, "file");
        String ext = normalizeExtension(extension);
        return String.format(
                Locale.ROOT, "replenishment-evidence/%s/task-%d/user-%d/%s%s", date, taskId, userId, token, ext);
    }

    private static String prefix(
            String root,
            String deviceId,
            long userId,
            String sessionId,
            String camera,
            String extension,
            Instant at) {
        ZonedDateTime zdt = at.atZone(ZONE);
        String date = String.format(
                Locale.ROOT, "%04d/%02d/%02d", zdt.getYear(), zdt.getMonthValue(), zdt.getDayOfMonth());
        String safeDevice = sanitize(deviceId, "unknown-device");
        String safeSession = sanitize(sessionId, "session");
        String cam = normalizeCamera(camera);
        String ext = normalizeExtension(extension);
        return String.format(Locale.ROOT, "%s/%s/%s/user-%d/%s-%s%s", root, date, safeDevice, userId, safeSession, cam, ext);
    }

    private static String normalizeCamera(String camera) {
        if (camera == null || camera.isBlank()) {
            return "top";
        }
        return camera.trim().toLowerCase(Locale.ROOT);
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
