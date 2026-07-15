package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AdService {
    @Autowired private AdSlotRepository slotRepository;
    @Autowired private AdCampaignRepository campaignRepository;
    @Autowired private AdImpressionRepository impressionRepository;
}

