package com.aicabinet.common.dto;

/** BIND|UNBIND|DEPLOY|UNDEPLOY|RETURN|RETIRE|INBOUND */
public record DeviceLifecycleRequest(
        String action,
        String merchantId,
        String remark
) {}
