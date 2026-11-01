package com.aicabinet.trade.util;

import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * 只读文件头解析图片像素尺寸（PNG/JPEG/GIF/WebP），不做整图解码。
 *
 * <p>用途：活动展示位按位置校验图片尺寸（CB-082）。上传时记录到 media_asset，
 * 老素材没有尺寸时由配置端拦下，要求重新上传，避免拿客户端自报尺寸做校验。
 */
public final class ImageDimensionReader {

    private ImageDimensionReader() {
    }

    /** @return [width, height]；格式不支持或数据不完整时 empty。 */
    public static Optional<int[]> read(byte[] bytes) {
        if (bytes == null || bytes.length < 16) {
            return Optional.empty();
        }
        try {
            if (isPng(bytes)) {
                return readPng(bytes);
            }
            if (isGif(bytes)) {
                return readGif(bytes);
            }
            if (isJpeg(bytes)) {
                return readJpeg(bytes);
            }
            if (isWebp(bytes)) {
                return readWebp(bytes);
            }
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return Optional.empty();
    }

    private static boolean isPng(byte[] b) {
        return b.length > 24
                && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G';
    }

    private static Optional<int[]> readPng(byte[] b) {
        int w = readIntBE(b, 16);
        int h = readIntBE(b, 20);
        return valid(w, h);
    }

    private static boolean isGif(byte[] b) {
        return b.length > 10 && b[0] == 'G' && b[1] == 'I' && b[2] == 'F';
    }

    private static Optional<int[]> readGif(byte[] b) {
        int w = readShortLE(b, 6);
        int h = readShortLE(b, 8);
        return valid(w, h);
    }

    private static boolean isJpeg(byte[] b) {
        return (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8;
    }

    /** 顺序扫描 SOFn 段（C0–CF，排除 C4 DHT / C8 JPG / CC DAC）。 */
    private static Optional<int[]> readJpeg(byte[] b) {
        int i = 2;
        while (i + 9 < b.length) {
            if ((b[i] & 0xFF) != 0xFF) {
                i++;
                continue;
            }
            int marker = b[i + 1] & 0xFF;
            if (marker == 0xD8 || marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) {
                i += 2;
                continue;
            }
            int len = readShortBE(b, i + 2);
            if (len < 2) {
                return Optional.empty();
            }
            boolean sof = marker >= 0xC0 && marker <= 0xCF
                    && marker != 0xC4 && marker != 0xC8 && marker != 0xCC;
            if (sof) {
                int h = readShortBE(b, i + 5);
                int w = readShortBE(b, i + 7);
                return valid(w, h);
            }
            i += 2 + len;
        }
        return Optional.empty();
    }

    private static boolean isWebp(byte[] b) {
        if (b.length < 30) {
            return false;
        }
        String riff = new String(b, 0, 4, StandardCharsets.US_ASCII);
        String webp = new String(b, 8, 4, StandardCharsets.US_ASCII);
        return "RIFF".equals(riff) && "WEBP".equals(webp);
    }

    private static Optional<int[]> readWebp(byte[] b) {
        String chunk = new String(b, 12, 4, StandardCharsets.US_ASCII);
        switch (chunk) {
            case "VP8X" -> {
                int w = readInt24LE(b, 24) + 1;
                int h = readInt24LE(b, 27) + 1;
                return valid(w, h);
            }
            case "VP8 " -> {
                int w = readShortLE(b, 26) & 0x3FFF;
                int h = readShortLE(b, 28) & 0x3FFF;
                return valid(w, h);
            }
            case "VP8L" -> {
                int bits = readIntLE(b, 21);
                int w = (bits & 0x3FFF) + 1;
                int h = ((bits >> 14) & 0x3FFF) + 1;
                return valid(w, h);
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    private static Optional<int[]> valid(int w, int h) {
        return w > 0 && h > 0 ? Optional.of(new int[]{w, h}) : Optional.empty();
    }

    private static int readIntBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 24) | ((b[off + 1] & 0xFF) << 16)
                | ((b[off + 2] & 0xFF) << 8) | (b[off + 3] & 0xFF);
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readInt24LE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8) | ((b[off + 2] & 0xFF) << 16);
    }

    private static int readShortBE(byte[] b, int off) {
        return ((b[off] & 0xFF) << 8) | (b[off + 1] & 0xFF);
    }

    private static int readShortLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }
}
