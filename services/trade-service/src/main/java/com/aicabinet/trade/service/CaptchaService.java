package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CaptchaResponse;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class CaptchaService {

    private static final String KEY_PREFIX = "aicabinet:captcha:";
    private static final char[] CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final int CODE_LEN = 4;
    private static final int WIDTH = 120;
    private static final int HEIGHT = 40;
    private static final long TTL_SECONDS = 300;

    private final RedissonClient redisson;
    private final boolean captchaEnabled;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(
            RedissonClient redisson,
            @Value("${aicabinet.security.captcha-enabled:true}") boolean captchaEnabled) {
        this.redisson = redisson;
        this.captchaEnabled = captchaEnabled;
    }

    public boolean isEnabled() {
        return captchaEnabled;
    }

    public CaptchaResponse create() {
        String code = randomCode();
        String id = UUID.randomUUID().toString().replace("-", "");
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + id, StringCodec.INSTANCE);
        bucket.set(code.toLowerCase(), TTL_SECONDS, TimeUnit.SECONDS);
        String image = "data:image/png;base64," + renderPngBase64(code);
        return new CaptchaResponse(id, image);
    }

    /** 运营后台密码登录校验；关闭开关时跳过。 */
    public void verifyOrThrow(String captchaId, String captchaCode) {
        if (!captchaEnabled) {
            return;
        }
        if (captchaId == null || captchaId.isBlank() || captchaCode == null || captchaCode.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请输入图形验证码");
        }
        RBucket<String> bucket = redisson.getBucket(KEY_PREFIX + captchaId.trim(), StringCodec.INSTANCE);
        String expected = bucket.getAndDelete();
        if (expected == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码已失效，请刷新后重试");
        }
        if (!expected.equalsIgnoreCase(captchaCode.trim())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "验证码错误");
        }
    }

    private String randomCode() {
        char[] buf = new char[CODE_LEN];
        for (int i = 0; i < CODE_LEN; i++) {
            buf[i] = CHARS[random.nextInt(CHARS.length)];
        }
        return new String(buf);
    }

    private String renderPngBase64(String code) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, WIDTH, HEIGHT);
            for (int i = 0; i < 6; i++) {
                g.setColor(new Color(160 + random.nextInt(60), 160 + random.nextInt(60), 180 + random.nextInt(50)));
                g.drawLine(random.nextInt(WIDTH), random.nextInt(HEIGHT),
                        random.nextInt(WIDTH), random.nextInt(HEIGHT));
            }
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            int x = 14;
            for (int i = 0; i < code.length(); i++) {
                g.setColor(new Color(30 + random.nextInt(80), 40 + random.nextInt(80), 90 + random.nextInt(80)));
                double angle = (random.nextDouble() - 0.5) * 0.4;
                g.rotate(angle, x, 28);
                g.drawString(String.valueOf(code.charAt(i)), x, 28);
                g.rotate(-angle, x, 28);
                x += 24;
            }
            for (int i = 0; i < 40; i++) {
                g.setColor(new Color(120 + random.nextInt(100), 120 + random.nextInt(100), 140 + random.nextInt(80)));
                g.fillRect(random.nextInt(WIDTH), random.nextInt(HEIGHT), 1, 1);
            }
        } finally {
            g.dispose();
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("生成验证码失败", e);
        }
    }
}
