package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.*;
import com.aicabinet.trade.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GameService {
    @Autowired private UserCheckinRepository checkinRepository;
    @Autowired private AchievementRepository achievementRepository;
    @Autowired private GameTaskRepository gameTaskRepository;
}

