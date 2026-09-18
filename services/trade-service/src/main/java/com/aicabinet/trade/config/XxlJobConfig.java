package com.aicabinet.trade.config;

import com.xxl.job.core.executor.impl.XxlJobSpringExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * XXL-JOB 执行器配置。
 * <p>通过 aicabinet.xxljob.enabled=true（环境变量 XXL_JOB_ENABLED）开启，
 * 关闭时业务完全不受影响，仅不注册执行器。</p>
 */
@Configuration
@ConditionalOnProperty(prefix = "aicabinet.xxljob", name = "enabled", havingValue = "true")
public class XxlJobConfig {

    private static final Logger log = LoggerFactory.getLogger(XxlJobConfig.class);

    @Value("${xxl.job.admin.addresses}")
    private String adminAddresses;

    // H48: 默认值与 infra/docker-compose.full.yml 的开发默认保持一致（非空），
    // 避免执行器与调度中心一侧空 token 一侧非空导致注册/回调鉴权失败
    @Value("${xxl.job.access-token:dev-xxl-token}")
    private String accessToken;

    @Value("${xxl.job.executor.appname:trade-service}")
    private String appname;

    @Value("${xxl.job.executor.ip:}")
    private String ip;

    @Value("${xxl.job.executor.port:9999}")
    private int port;

    @Value("${xxl.job.executor.log-path:./logs/xxl-job/jobhandler}")
    private String logPath;

    @Value("${xxl.job.executor.log-retention-days:30}")
    private int logRetentionDays;

    @Bean
    public XxlJobSpringExecutor xxlJobExecutor() {
        log.info(">>>>>>>>>>> xxl-job executor init, adminAddresses={}, appname={}, port={}",
                adminAddresses, appname, port);
        XxlJobSpringExecutor executor = new XxlJobSpringExecutor();
        executor.setAdminAddresses(adminAddresses);
        executor.setAppname(appname);
        executor.setIp(ip);
        executor.setPort(port);
        executor.setAccessToken(accessToken);
        executor.setLogPath(logPath);
        executor.setLogRetentionDays(logRetentionDays);
        return executor;
    }
}
