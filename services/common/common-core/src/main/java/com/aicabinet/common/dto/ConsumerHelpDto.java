package com.aicabinet.common.dto;

import java.util.List;

/** 消费者帮助中心公开内容（与 mp 静态页口径对齐）。 */
public record ConsumerHelpDto(String supportPhone, String supportEmail, List<ConsumerHelpFaqDto> faqs) {}
