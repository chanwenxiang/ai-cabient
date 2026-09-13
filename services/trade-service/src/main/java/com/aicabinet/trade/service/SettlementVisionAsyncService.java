package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.messaging.VisionRecognitionProducer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 结算链路中的异步视觉提交（从 {@link SettlementService} 拆出，降低上帝类体积）。
 */
@Service
public class SettlementVisionAsyncService {

    private final ShoppingSessionMapper sessionRepository;
    private final ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer;

    public SettlementVisionAsyncService(ShoppingSessionMapper sessionRepository,
                                        ObjectProvider<VisionRecognitionProducer> visionRecognitionProducer) {
        this.sessionRepository = sessionRepository;
        this.visionRecognitionProducer = visionRecognitionProducer;
    }

    public void submitAsyncRecognition(ShoppingSession session) {
        VisionRecognitionProducer producer = visionRecognitionProducer.getIfAvailable();
        if (producer == null) {
            throw new IllegalStateException("vision async not enabled");
        }
        String taskId = "T-" + session.getSessionId();
        persistRecognitionTaskId(session.getSessionId(), taskId);
        session.setRecognitionTaskId(taskId);
        producer.publish(session.getSessionId(), session.getVideoUri(), taskId,
                session.getVideoClips(), session.getCameraFusionMode());
    }

    @Transactional
    public void persistRecognitionTaskId(String sessionId, String taskId) {
        ShoppingSession session = sessionRepository.findByIdForUpdate(sessionId).orElse(null);
        if (session == null) {
            return;
        }
        session.setRecognitionTaskId(taskId);
        sessionRepository.save(session);
    }
}
