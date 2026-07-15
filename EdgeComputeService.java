package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class EdgeComputeService {
    @Autowired private EdgeDeviceRepository edgeDeviceRepository;
    @Autowired private EdgeInferenceLogRepository inferenceLogRepository;
    @Autowired private EdgeModelVersionRepository modelVersionRepository;
}

