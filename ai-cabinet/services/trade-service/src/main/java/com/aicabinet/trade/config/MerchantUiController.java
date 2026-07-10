package com.aicabinet.trade.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class MerchantUiController {

    @GetMapping({"/merchant", "/merchant/"})
    public String merchantRoot() {
        return "redirect:/merchant/index.html";
    }
}
