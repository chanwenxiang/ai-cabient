package com.aicabinet.trade.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(
    info = @Info(
        title = "AI Cabinet API",
        version = "0.1.0",
        description = "AI开门柜 - 全栈API文档\n\n提供消费者小程序、商户小程序、运营控制台所需的后端服务接口。",
        contact = @Contact(name = "AI Cabinet Team")
    ),
    servers = {
        @Server(url = "http://localhost:8080", description = "本地开发"),
        @Server(url = "https://api.aicabinet.com", description = "生产环境")
    }
)
public class OpenApiConfig {}
