package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SocialService {
    @Autowired private ShareRewardRepository shareRewardRepository;
    @Autowired private GroupBuyRepository groupBuyRepository;
    @Autowired private RedPacketRepository redPacketRepository;
}

