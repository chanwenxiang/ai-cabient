package com.aicabinet.trade.api;

import com.aicabinet.trade.service.PaymentService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v2/payment/alipay")
public class AlipayNotifyController {

    private final PaymentService paymentService;

    public AlipayNotifyController(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    /** 支付宝异步通知（application/x-www-form-urlencoded） */
    @PostMapping(value = "/notify", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ResponseEntity<String> notify(@RequestParam MultiValueMap<String, String> form) {
        Map<String, String> params = new HashMap<>();
        form.forEach((k, values) -> {
            if (values != null && !values.isEmpty()) {
                params.put(k, values.get(0));
            }
        });
        paymentService.handleAlipayNotify(params);
        return ResponseEntity.ok("success");
    }
}
