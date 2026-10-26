package com.aicabinet.trade.dto;

import java.time.Instant;
import java.util.List;

/**
 * 将邑采集 API（CB-023）轻量 DTO 集合：仅承载客户端收敛后的字段，
 * 宽松字段（doorStatus/studyingProduct 的原始 JsonNode）不在此定义。
 */
public final class JiangyiGatherDtos {

    private JiangyiGatherDtos() {}

    /**
     * 将邑商品库标准商品（采集文档 §4.1.2.4；name 语义以 PDF 原文「标准商品名称」为准）。
     * barCodeSource 原值 app（扫码）/manual（手填，不可靠）/null（未登记）。
     */
    public record JiangyiStdSku(long id, String name, String barCode, String specs,
                                String brandName, String category, String mainImg, String stdSkuCode,
                                String barCodeSource) {}

    /**
     * 将邑待采集商品（§4.3.2.4 waitGatherProduct）：已进将邑、但还没采集学习过的条目。
     *
     * <p>2026-10-23 实测回包<b>没有 barCode 字段</b>（只有 productId/name/brandName/specs/
     * category/mainImg/barCodeSource/productEn），条码要等采集学习进标准库后
     * 才在 §4.1.2 出现——所以这个列表不能按条码筛（同批次实测 barCode 入参也是空转）。</p>
     */
    public record GatherPendingProduct(long productId, String name, String brandName, String specs,
                                       String category, String mainImg, String barCodeSource,
                                       String productEn) {}

    /** 商品分类（§4.1.4.4：id/name，例 235 其他 / 238 饮料）。 */
    public record JiangyiCategory(long id, String name) {}

    /**
     * 采集建条目请求（§4.3.3 productInfo）。字段取自待采集列表（waitGatherProduct 行）+
     * 柜机 identifier；barCodeSource 空/非法时客户端兜底 manual（2026-10-26 实测）。
     */
    public record GatherEntryRequest(String identifier, long productId, String name, String brand,
                                     String specs, String category, String barCodeSource) {}

    /** 已学习准备成功商品（§4.4.2.4）：textName 是模型 classes.txt 的直接键。 */
    public record TrainedProduct(long id, String name, String textName, String barCode) {}

    /**
     * classes.txt 行 → 将邑标准商品（§5.5.3.4 返回表；字段名以 2026-10-22 真机响应原文为准，
     * 文档正文示例用的是 <code>stdSkuld</code>/<code>name</code>，与实际回包不一致，不可据示例编码）。
     *
     * <p>barCode 可为 null（香烟等无条码类目）；classId 是 classes.txt 的 0-based 行下标（台账 H-15）。</p>
     */
    public record JiangyiTextNameSku(Long stdSkuId, String stdSkuName, String textName,
                                     String barCode, Integer classId) {}

    /**
     * 模型文件（§4.4.4.4）。industrialControlModel 存将邑原值（"76"/"88"）——
     * PDF 示例两种主板 rknn 该字段同为 "88"，映射关系不解释，下发校验用字符串相等。
     */
    public record JiangyiModelFile(long id, String modelName, String modelUrl,
                                   String industrialControlModel, String modelTextUrl, int quantity) {}

    /** 采集审核项（§4.4.6.4：status pass|reject|wait，rejectCause 驳回原因）。 */
    public record GatherCheckItem(long id, long productId, String status,
                                  String rejectCause, String name, String picUrl) {}

    /**
     * 商户侧采集模式状态（小程序条件渲染）：非将邑绑定柜机 bound=false（前端据此隐藏入口，
     * 不弹错）；gatherLocked=true 表示营业开门已被 409 闸断（DeviceValidationService）。
     */
    public record MerchantGatherStatusDto(boolean bound, boolean gatherLocked) {}

    /**
     * 商户端只读学习进度（本地票据聚合，不读厂商读面）：bound=false 时前端隐藏入口。
     * 一次触发（含批量）共享一个回执 id，所以 items 是整组 SKU 的逐项状态；
     * status 是整组汇总（PENDING 学习中 / FINISHED 全部学成 / FAILED 有失败）。
     */
    public record MerchantTrainingStatusDto(boolean bound, boolean gatherLocked, String modelName,
                                            String status, Instant createdAt, Instant finishedAt,
                                            List<MerchantTrainingItemDto> items) {}

    public record MerchantTrainingItemDto(String skuId, String skuName, String status) {}
}
