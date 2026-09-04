package com.aicabinet.trade.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.resource.PathResourceResolver;

import java.io.IOException;
import java.util.Arrays;

/**
 * 运营后台 SPA：history 深链刷新回落到 index.html。
 * <p>
 * 默认 classpath；开发可用 {@code aicabinet.admin-ui.resource-locations}
 * 优先挂载宿主机构建产物（见 {@code infra/docker-compose.admin-static.yml}）。
 */
@Controller
public class AdminUiController implements WebMvcConfigurer {

    private final String[] adminLocations;
    private final ResourceLoader resourceLoader = new DefaultResourceLoader();

    public AdminUiController(
            @Value("${aicabinet.admin-ui.resource-locations:classpath:/static/admin/}") String locations) {
        this.adminLocations = Arrays.stream(locations.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(s -> s.endsWith("/") ? s : s + "/")
                .toArray(String[]::new);
    }

    @GetMapping({"/admin", "/admin/"})
    public String adminRoot() {
        return "forward:/admin/index.html";
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/admin/**")
                .addResourceLocations(adminLocations)
                .resourceChain(true)
                .addResolver(new PathResourceResolver() {
                    @Override
                    protected Resource getResource(String resourcePath, Resource location) throws IOException {
                        Resource requested = location.createRelative(resourcePath);
                        if (requested.exists() && requested.isReadable()) {
                            return requested;
                        }
                        if (resourcePath.contains(".")) {
                            return null;
                        }
                        return resolveIndexHtml();
                    }
                });
    }

    private Resource resolveIndexHtml() {
        for (String loc : adminLocations) {
            Resource index = resourceLoader.getResource(loc + "index.html");
            if (index.exists() && index.isReadable()) {
                return index;
            }
        }
        return new ClassPathResource("static/admin/index.html");
    }
}
