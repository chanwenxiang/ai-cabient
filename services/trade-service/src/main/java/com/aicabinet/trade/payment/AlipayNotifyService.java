package com.aicabinet.trade.payment;

import com.aicabinet.trade.config.AlipayProperties;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class AlipayNotifyService {

    private final AlipayProperties properties;
    private final AlipaySignUtil signUtil;

    public AlipayNotifyService(AlipayProperties properties, AlipaySignUtil signUtil) {
        this.properties = properties;
        this.signUtil = signUtil;
    }

    public Map<String, String> parseAndVerify(Map<String, String> params) {
        if (!properties.isConfigured()) {
            throw new IllegalStateException(ApiMessages.ALIPAY_PAY_NOT_CONFIGURED);
        }
        String sign = params.get("sign");
        if (!signUtil.verifyRsa2(params, sign, properties.alipayPublicKey())) {
            throw new IllegalArgumentException(ApiMessages.INVALID_ALIPAY_NOTIFY);
        }
        return new HashMap<>(params);
    }
}
