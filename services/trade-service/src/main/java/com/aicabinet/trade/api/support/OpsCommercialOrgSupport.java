package com.aicabinet.trade.api.support;

import com.aicabinet.trade.service.CommercialFlowService;
import com.aicabinet.trade.service.FootfallAnalyticsService;
import com.aicabinet.trade.service.OrgService;
import com.aicabinet.trade.service.SiteContractService;
import org.springframework.stereotype.Component;

/** Org, contracts and commercial flow for {@link OpsCommercialControllerSupport}. */
@Component
class OpsCommercialOrgSupport {

    private final CommercialFlowService commercialFlowService;
    private final FootfallAnalyticsService footfallAnalyticsService;
    private final OrgService orgService;
    private final SiteContractService siteContractService;

    OpsCommercialOrgSupport(CommercialFlowService commercialFlowService,
                            FootfallAnalyticsService footfallAnalyticsService,
                            OrgService orgService,
                            SiteContractService siteContractService) {
        this.commercialFlowService = commercialFlowService;
        this.footfallAnalyticsService = footfallAnalyticsService;
        this.orgService = orgService;
        this.siteContractService = siteContractService;
    }

    CommercialFlowService commercialFlowService() {
        return commercialFlowService;
    }

    FootfallAnalyticsService footfallAnalyticsService() {
        return footfallAnalyticsService;
    }

    OrgService orgService() {
        return orgService;
    }

    SiteContractService siteContractService() {
        return siteContractService;
    }
}
