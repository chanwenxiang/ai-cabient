package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceQrLinkDto;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.wechat.MiniAppTarget;
import com.aicabinet.trade.wechat.WeChatMiniAppClient;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.util.EnumMap;
import java.util.Map;

@Service
public class DeviceQrService {

    private static final int PNG_SIZE = 512;

    private final DeviceInfoMapper deviceInfoMapper;
    private final QrProperties qrProperties;
    private final WeChatMiniAppClient weChatMiniAppClient;

    public DeviceQrService(DeviceInfoMapper deviceInfoMapper, QrProperties qrProperties,
                           WeChatMiniAppClient weChatMiniAppClient) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.qrProperties = qrProperties;
        this.weChatMiniAppClient = weChatMiniAppClient;
    }

    public DeviceQrLinkDto linkFor(String rawDeviceId) {
        String deviceId = requireDevice(rawDeviceId).getDeviceId();
        return new DeviceQrLinkDto(deviceId, buildOpenUrl(deviceId));
    }

    /**
     * 柜门贴码：生成<b>小程序码</b>（微信扫一扫直接拉起消费者小程序）。
     *
     * <p>不用 {@link #pngFor} 的普通链接二维码——那需要公众平台
     * 「扫普通链接二维码打开小程序」前缀规则 + 校验文件，文件一丢码就废
     * （2026-11-19 实测校验文件 404）。这里空 Optional 表示微信侧失败，
     * 调用方必须明确报错，不得静默降级回普通二维码。
     */
    public java.util.Optional<byte[]> wxacodeFor(String rawDeviceId) {
        String deviceId = requireDevice(rawDeviceId).getDeviceId();
        return weChatMiniAppClient.getWxaCodeUnlimited(
                deviceId,
                qrProperties.wechatPath(),
                qrProperties.wechatEnvVersion(),
                MiniAppTarget.CONSUMER);
    }

    /** 落地页地址（普通链接，H5 兜底 / 现场分享用；<b>不要</b>作为柜门贴码）。 */
    public byte[] pngFor(String rawDeviceId) {
        DeviceQrLinkDto link = linkFor(rawDeviceId);
        try {
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 2);
            BitMatrix matrix = new QRCodeWriter().encode(link.url(), BarcodeFormat.QR_CODE, PNG_SIZE, PNG_SIZE, hints);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "生成二维码失败", e);
        }
    }

    public String buildOpenUrl(String deviceId) {
        return qrProperties.normalizedPublicHost() + "/o/" + deviceId;
    }

    public DeviceInfo requireDevice(String rawDeviceId) {
        String deviceId = normalizeDeviceId(rawDeviceId);
        DeviceInfo device = deviceInfoMapper.selectById(deviceId);
        if (device == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "柜机不存在");
        }
        return device;
    }

    /**
     * deviceId 大小写敏感：创建入口不转写、库查询按原样匹配（种子含小写如 jiangyi-test-001）。
     * 成因注释：历史版本此处强转 toUpperCase，源于「deviceId 必大写」的旧约定，
     * 但该约定从未在创建链路强制——小写 ID 柜机在二维码链路被转写成不存在的 ID，
     * 设备详情页一打开（调 qr-link）即 404「柜机不存在」。现只 trim，不改大小写。
     */
    public static String normalizeDeviceId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId 不能为空");
        }
        String id = raw.trim();
        if (!id.matches("^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId 格式无效");
        }
        return id;
    }
}
