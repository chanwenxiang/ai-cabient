package com.aicabinet.trade.config;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * Spring Boot 对 {@code /admin/} 无默认 index，会 404/500；统一重定向到打包后的入口页。
 */
@Controller
public class AdminUiController {

    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "redirect:/admin/index.html";
    }
}
