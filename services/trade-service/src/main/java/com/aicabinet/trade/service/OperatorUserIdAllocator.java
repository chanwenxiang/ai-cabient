package com.aicabinet.trade.service;

import com.aicabinet.trade.repository.UserInfoRepository;
import org.springframework.stereotype.Component;

@Component
public class OperatorUserIdAllocator {

    private final UserInfoRepository userInfoRepository;

    public OperatorUserIdAllocator(UserInfoRepository userInfoRepository) {
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
