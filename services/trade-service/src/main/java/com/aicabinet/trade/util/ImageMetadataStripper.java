package com.aicabinet.trade.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Iterator;
import java.util.Locale;

/**
 * 上传图片时剥离 EXIF/GPS 等元数据，避免补货员位置等信息泄露。
 * 通过无元数据重编码实现；无法解码时原样返回。
 */
public final class ImageMetadataStripper {
    private static final Logger log = LoggerFactory.getLogger(ImageMetadataStripper.class);

    private ImageMetadataStripper() {
    }

    /**
     * @param bytes       原始图片字节
     * @param contentType MIME，如 image/jpeg
     * @return 去元数据后的字节；失败则返回原 bytes
     */
    public static byte[] strip(byte[] bytes, String contentType) {
        if (bytes == null || bytes.length == 0) {
            return bytes;
        }
        String format = formatName(contentType);
        if (format == null) {
            return bytes;
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                return bytes;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
            if ("jpg".equals(format) || "jpeg".equals(format)) {
                // JPEG 无 alpha；重编码为无 EXIF/GPS 的标准 RGB
                writeJpeg(toRgb(image), out);
            } else if (!ImageIO.write(image, format, out)) {
                // PNG/GIF：ImageIO.write 默认不写 EXIF；保留原像素（含 alpha）
                return bytes;
            }
            byte[] cleaned = out.toByteArray();
            return cleaned.length > 0 ? cleaned : bytes;
        } catch (Exception e) {
            log.warn("strip image metadata failed, keep original: {}", e.toString());
            return bytes;
        }
    }

    private static BufferedImage toRgb(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) {
            return src;
        }
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.createGraphics().drawImage(src, 0, 0, java.awt.Color.WHITE, null);
        return rgb;
    }

    private static void writeJpeg(BufferedImage image, ByteArrayOutputStream out) throws Exception {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            ImageIO.write(image, "jpg", out);
            return;
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(0.92f);
            }
            // IIOImage 不传 metadata → 无 EXIF/GPS
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
    }

    private static String formatName(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }
        String type = contentType.trim().toLowerCase(Locale.ROOT);
        int semi = type.indexOf(';');
        if (semi > 0) {
            type = type.substring(0, semi).trim();
        }
        return switch (type) {
            case "image/jpeg", "image/jpg" -> "jpg";
            case "image/png" -> "png";
            case "image/gif" -> "gif";
            // WebP 标准 JDK ImageIO 通常无 writer，跳过以免损坏
            default -> null;
        };
    }
}
