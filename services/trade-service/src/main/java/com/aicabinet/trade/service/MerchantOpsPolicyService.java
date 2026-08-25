package com.aicabinet.trade.service;

import com.aicabinet.common.enums.SessionState;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.MerchantOpsConfig;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.MerchantOpsConfigMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class MerchantOpsPolicyService {

    private static final List<SessionState> INFLIGHT = List.of(
            SessionState.CREATED,
            SessionState.OPENING,
            SessionState.SHOPPING,
            SessionState.RECOGNIZING,
            SessionState.WAITING_UPLOAD
    );

    private final MerchantOpsConfigMapper opsConfigMapper;
    private final DeviceInfoMapper deviceInfoMapper;
    private final ShoppingSessionMapper sessionMapper;

    public MerchantOpsPolicyService(MerchantOpsConfigMapper opsConfigMapper,
                                    DeviceInfoMapper deviceInfoMapper,
                                    ShoppingSessionMapper sessionMapper) {
        this.opsConfigMapper = opsConfigMapper;
        this.deviceInfoMapper = deviceInfoMapper;
        this.sessionMapper = sessionMapper;
    }

    public MerchantOpsConfig loadConfig(String deviceId) {
        DeviceInfo device = deviceInfoMapper.findById(deviceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
        if (device.getMerchantId() == null || device.getMerchantId().isBlank()) {
            return null;
        }
        return opsConfigMapper.findById(device.getMerchantId()).orElse(null);
    }

    public void requirePhotoEvidence(String deviceId, boolean stocktake, String evidenceUrl) {
        MerchantOpsConfig cfg = loadConfig(deviceId);
        if (cfg == null) {
            return;
        }
        boolean required = stocktake
                ? Boolean.TRUE.equals(cfg.getPhotoStocktake())
                : Boolean.TRUE.equals(cfg.getPhotoReplenish());
        if (required && (evidenceUrl == null || evidenceUrl.isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    stocktake ? "该商户要求盘点必须上传照片凭证" : "该商户要求补货必须上传照片凭证");
        }
    }

    public void requireInflightCapacity(String deviceId) {
        MerchantOpsConfig cfg = loadConfig(deviceId);
        if (cfg == null || cfg.getMaxInflightOrders() <= 0) {
            return;
        }
        long open = sessionMapper.selectCount(Wrappers.<ShoppingSession>lambdaQuery()
                .eq(ShoppingSession::getDeviceId, deviceId)
                .in(ShoppingSession::getState, INFLIGHT));
        if (open >= cfg.getMaxInflightOrders()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "并发购物会话已达上限（" + cfg.getMaxInflightOrders() + "），请稍后再试");
        }
    }
}
