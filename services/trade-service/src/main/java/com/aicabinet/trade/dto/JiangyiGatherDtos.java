package com.aicabinet.trade.dto;

/**
 * 将邑采集 API（CB-023）轻量 DTO 集合：仅承载客户端收敛后的字段，
 * 宽松字段（doorStatus/waitGatherProduct/studyingProduct 的原始 JsonNode）不在此定义。
 */
public final class JiangyiGatherDtos {

    private JiangyiGatherDtos() {}

    /** 将邑商品库标准商品（采集文档 §4.1.2.4；name 语义以 PDF 原文「标准商品名称」为准）。 */
    public record JiangyiStdSku(long id, String name, String barCode, String specs,
                                String brandName, String category, String mainImg, String stdSkuCode) {}

    /** 商品分类（§4.1.4.4：id/name，例 235 其他 / 238 饮料）。 */
    public record JiangyiCategory(long id, String name) {}

    /** 已学习准备成功商品（§4.4.2.4）：textName 是模型 classes.txt 的直接键。 */
    public record TrainedProduct(long id, String name, String textName, String barCode) {}

    /**
     * 模型文件（§4.4.4.4）。industrialControlModel 存将邑原值（"76"/"88"）——
     * PDF 示例两种主板 rknn 该字段同为 "88"，映射关系不解释，下发校验用字符串相等。
     */
    public record JiangyiModelFile(long id, String modelName, String modelUrl,
                                   String industrialControlModel, String modelTextUrl, int quantity) {}

    /** 采集审核项（§4.4.6.4：status pass|reject|wait，rejectCause 驳回原因）。 */
    public record GatherCheckItem(long id, long productId, String status,
                                  String rejectCause, String name, String picUrl) {}
}
