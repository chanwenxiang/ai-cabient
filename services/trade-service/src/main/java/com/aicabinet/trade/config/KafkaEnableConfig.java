package com.aicabinet.trade.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;

@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class KafkaEnableConfig {}
