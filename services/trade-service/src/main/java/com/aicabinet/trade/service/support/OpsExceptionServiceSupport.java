package com.aicabinet.trade.service.support;

import com.aicabinet.trade.mapper.AdminAuditLogMapper;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.service.AdminAuditService;
import com.aicabinet.trade.service.DisputeService;
import com.aicabinet.trade.service.JiangyiOrderVideoService;
import com.aicabinet.trade.service.RepairTicketService;
import com.aicabinet.trade.service.SessionService;
import com.aicabinet.trade.service.SettlementService;
import com.aicabinet.trade.storage.MinioVideoService;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

/** Groups secondary dependencies for {@link com.aicabinet.trade.service.OpsExceptionService}. */
@Component
public class OpsExceptionServiceSupport {

    private final AdminAuditService auditService;
    private final AdminAuditLogMapper auditRepository;
    private final ShoppingSessionMapper sessionRepository;
    private final SettlementService settlementService;
    private final DisputeService disputeService;
    private final RepairTicketService repairTicketService;
    private final SessionService sessionService;
    private final MinioVideoService minioVideoService;
    private final JiangyiOrderVideoService jiangyiOrderVideoService;

    public OpsExceptionServiceSupport(AdminAuditService auditService,
                                      AdminAuditLogMapper auditRepository,
                                      ShoppingSessionMapper sessionRepository,
                                      @Lazy SettlementService settlementService,
                                      @Lazy DisputeService disputeService,
                                      RepairTicketService repairTicketService,
                                      @Lazy SessionService sessionService,
                                      @Lazy MinioVideoService minioVideoService,
                                      @Lazy JiangyiOrderVideoService jiangyiOrderVideoService) {
        this.auditService = auditService;
        this.auditRepository = auditRepository;
        this.sessionRepository = sessionRepository;
        this.settlementService = settlementService;
        this.disputeService = disputeService;
        this.repairTicketService = repairTicketService;
        this.sessionService = sessionService;
        this.minioVideoService = minioVideoService;
        this.jiangyiOrderVideoService = jiangyiOrderVideoService;
    }

    public AdminAuditService auditService() {
        return auditService;
    }

    public AdminAuditLogMapper auditRepository() {
        return auditRepository;
    }

    public ShoppingSessionMapper sessionRepository() {
        return sessionRepository;
    }

    public SettlementService settlementService() {
        return settlementService;
    }

    public DisputeService disputeService() {
        return disputeService;
    }

    public RepairTicketService repairTicketService() {
        return repairTicketService;
    }

    public SessionService sessionService() {
        return sessionService;
    }

    public MinioVideoService minioVideoService() {
        return minioVideoService;
    }

    public JiangyiOrderVideoService jiangyiOrderVideoService() {
        return jiangyiOrderVideoService;
    }
}
