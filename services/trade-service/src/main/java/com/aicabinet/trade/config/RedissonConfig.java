package com.aicabinet.trade.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {
    
    @Value("${redis.host:localhost}")
    private String host;
    
    @Value("${redis.port:6379}")
    private String port;
    
    @Value("${redis.password:}")
    private String password;
    
    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        
        String address = "redis://" + host + ":" + port;
        
        config.useSingleServer()
            .setAddress(address)
            .setConnectionMinimumIdleSize(1)
            .setConnectionPoolSize(10)
            .setIdleConnectionTimeout(10000)
            .setConnectTimeout(10000)
            .setTimeout(3000)
            .setRetryAttempts(3)
            .setRetryInterval(1500);
        
        if (password != null && !password.isEmpty()) {
            config.useSingleServer().setPassword(password);
        }
        
        return Redisson.create(config);
    }
}