package com.aicabinet.trade.service;

import com.aicabinet.common.dto.ConsumerHelpDto;
import com.aicabinet.common.dto.ConsumerPolicyDto;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * 消费者帮助/条款公开文案。源文件 {@code public-legal.json}，须与
 * {@code clients/consumer-mp/src/pages/help/help.vue}、
 * {@code pages/policy/detail.vue} 静态文案保持同口径。
 */
@Service
public class PublicLegalService {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final LegalRoot root;

    public PublicLegalService() {
        this.root = load();
    }

    public ConsumerHelpDto help() {
        return root.help();
    }

    public List<ConsumerPolicyDto> policies() {
        return root.policies();
    }

    static LegalRoot load() {
        try (InputStream in = PublicLegalService.class.getResourceAsStream("/public-legal.json")) {
            if (in == null) {
                throw new IllegalStateException("missing classpath resource /public-legal.json");
            }
            return MAPPER.readValue(in, LegalRoot.class);
        } catch (IOException e) {
            throw new UncheckedIOException("failed to parse public-legal.json", e);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record LegalRoot(ConsumerHelpDto help, List<ConsumerPolicyDto> policies) {
        LegalRoot {
            Objects.requireNonNull(help, "help");
            Objects.requireNonNull(policies, "policies");
        }
    }
}
