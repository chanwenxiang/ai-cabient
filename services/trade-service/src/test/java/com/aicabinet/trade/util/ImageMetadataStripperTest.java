package com.aicabinet.trade.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImageMetadataStripperTest {

    @Test
    void strip_jpeg_removesApp1ExifMarker() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.ORANGE);
        g.fillRect(0, 0, 8, 8);
        g.dispose();

        ByteArrayOutputStream rawOut = new ByteArrayOutputStream();
        ImageIO.write(image, "jpg", rawOut);
        byte[] plainJpeg = rawOut.toByteArray();

        // 手工注入 APP1 (Exif) 段：SOI + APP1 + 其余
        byte[] withExif = injectFakeExifApp1(plainJpeg);
        assertTrue(containsApp1(withExif), "fixture must contain APP1");

        byte[] cleaned = ImageMetadataStripper.strip(withExif, "image/jpeg");
        assertNotNull(cleaned);
        assertTrue(cleaned.length > 2);
        assertFalse(containsApp1(cleaned), "stripped JPEG must not contain APP1/Exif");
    }

    @Test
    void strip_unknownType_returnsOriginal() {
        byte[] original = new byte[] {1, 2, 3, 4};
        byte[] out = ImageMetadataStripper.strip(original, "image/webp");
        assertTrue(out == original);
    }

    /** 在 SOI 后插入最小 APP1 段（非合法 Exif，仅用于标记检测）。 */
    private static byte[] injectFakeExifApp1(byte[] jpeg) {
        // JPEG SOI = FF D8；APP1 = FF E1 + length(2) + payload
        byte[] app1 = new byte[] {
                (byte) 0xFF, (byte) 0xE1,
                0x00, 0x10,
                'E', 'x', 'i', 'f', 0, 0,
                0, 0, 0, 0, 0, 0, 0, 0
        };
        byte[] out = new byte[2 + app1.length + (jpeg.length - 2)];
        out[0] = (byte) 0xFF;
        out[1] = (byte) 0xD8;
        System.arraycopy(app1, 0, out, 2, app1.length);
        System.arraycopy(jpeg, 2, out, 2 + app1.length, jpeg.length - 2);
        return out;
    }

    private static boolean containsApp1(byte[] jpeg) {
        for (int i = 0; i < jpeg.length - 1; i++) {
            if ((jpeg[i] & 0xFF) == 0xFF && (jpeg[i + 1] & 0xFF) == 0xE1) {
                return true;
            }
        }
        return false;
    }
}
