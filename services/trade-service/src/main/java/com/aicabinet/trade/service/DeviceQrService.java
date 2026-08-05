package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DeviceQrLinkDto;
import com.aicabinet.trade.config.QrProperties;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
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

    public DeviceQrService(DeviceInfoMapper deviceInfoMapper, QrProperties qrProperties) {
        this.deviceInfoMapper = deviceInfoMapper;
        this.qrProperties = qrProperties;
    }

    public DeviceQrLinkDto linkFor(String rawDeviceId) {
        String deviceId = requireDevice(rawDeviceId).getDeviceId();
        return new DeviceQrLinkDto(deviceId, buildOpenUrl(deviceId));
    }

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

    public static String normalizeDeviceId(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId 不能为空");
        }
        String id = raw.trim().toUpperCase();
        if (!id.matches("^[A-Z0-9][A-Z0-9_-]{1,63}$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "deviceId 格式无效");
        }
        return id;
    }
}
