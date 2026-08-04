package com.aicabinet.trade.service;

import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.stereotype.Component;

@Component
public class OperatorUserIdAllocator {

    private final UserInfoMapper userInfoRepository;

    public OperatorUserIdAllocator(UserInfoMapper userInfoRepository) {
        this.userInfoRepository = userInfoRepository;
    }

    public long nextId() {
        Long nextId = userInfoRepository.nextOperatorUserId();
        if (nextId == null) {
            throw new IllegalStateException("Operator user ID sequence returned null");
        }
        return nextId;
    }
}
