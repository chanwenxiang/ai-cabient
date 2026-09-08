package com.aicabinet.common.dto;

import java.util.List;

public record MerchantMeDto(
        Long userId,
        String phoneNumber,
        String displayName,
        List<MerchantDto> merchants,
        List<String> permissions,
        boolean canEditPricing,
        /** 绑定商户功能包并集：field / biz / team */
        List<String> enabledPacks,
        /**
         * 完成补货任务是否必须上传现场凭证。
         * 来自系统参数 replenishment.complete.require_evidence；缺省 true。
         */
        boolean requireReplenishmentEvidence,
        /**
         * 完成补货是否必须先补货开门。
         * 来自系统参数 replenishment.complete.require_door；缺省 true。
         */
        boolean requireReplenishmentDoor,
        /**
         * 柜机有坐标时签到是否必须带定位。
         * 来自系统参数 replenishment.check_in.require_location；缺省 true。
         */
        boolean requireReplenishmentCheckInLocation,
        /**
         * 签到距柜机最大允许距离（米）；≤0 表示关闭距离校验。
         * 来自系统参数 replenishment.check_in.max_distance_m；缺省 500。
         */
        int replenishmentCheckInMaxDistanceM
) {}
