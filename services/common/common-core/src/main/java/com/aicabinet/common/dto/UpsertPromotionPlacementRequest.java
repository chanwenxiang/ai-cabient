package com.aicabinet.common.dto;

/**
 * 保存展示位入参。图片复用广告素材库（post /ops/admin/ad/assets 上传），
 * 服务端读素材真实像素尺寸做位置校验，不接受客户端自报尺寸。
 */
public record UpsertPromotionPlacementRequest(
        String position,
        Long assetId,
        /** 轮播顺序：数值小在前；null=0。仅 BANNER 有意义，弹窗位忽略。 */
        Integer sortOrder
) {}
