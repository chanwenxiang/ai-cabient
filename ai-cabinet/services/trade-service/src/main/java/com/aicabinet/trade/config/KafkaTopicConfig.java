package com.aicabinet.trade.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

import com.aicabinet.common.constants.KafkaTopics;

@Configuration
@ConditionalOnProperty(prefix = "aicabinet.vision-async", name = "enabled", havingValue = "true")
public class KafkaTopicConfig {

    @Bean
    NewTopic visionRecognizeRequestTopic() {
        return TopicBuilder.name(KafkaTopics.VISION_RECOGNIZE_REQUEST).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic visionRecognizeResultTopic() {
        return TopicBuilder.name(KafkaTopics.VISION_RECOGNIZE_RESULT).partitions(3).replicas(1).build();
    }
}
