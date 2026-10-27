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

    /**
     * 厂商「学习准备成功」名单里的一行（§4.4.2.4）：textName 是模型 classes.txt 的直接键。
     *
     * <p>🔴 <b>在列 = 采集成档且厂商审核通过，不等于已学成</b>。文档 §4.4.1 流程图把它放在
     * 「开始学习」<b>之前</b>（查询可学习商品 → 开始学习 → 获取模型文件），且 2026-10-27 实测
     * 本租户商品 41175 在该名单内时 §4.4.4 模型数与 §4.4.5 学习中数<b>同时为 0</b>。
     * 判「已学成/可识别」请看模型 classes（§5.5.3）与我方映射，<b>不要顺手把这里当学成证据</b>。</p>
     */
    public record LearnableProduct(long id, String name, String textName, String barCode) {}

    /**
     * classes.txt 行 → 将邑标准商品（§5.5.3.4 返回表；字段名以 2026-10-22 真机响应原文为准，
     * 文档正文示例用的是 <code>stdSkuld</code>/<code>name</code>，与实际回包不一致，不可据示例编码）。
     *
     * <p>barCode 可为 null（香烟等无条码类目）；classId 是 classes.txt 的 0-based 行下标（台账 H-15）。</p>
     */
    public record JiangyiTextNameSku(Long stdSkuId, String stdSkuName, String textName,
                                     String barCode, Integer classId) {}

    /**
     * 模型文件（§4.4.4.4）。industrialControlModel 存将邑原值——文档写「固定值("76","88")」，
     * 现网实测至少 76/88/1106/1126/3566，且一次重训按档位产 5 行而非 2 行（台账 H-33）；
     * 映射关系不解释，下发校验用字符串相等，<b>别按「两个模型」写死计数</b>。
     *
     * <p>customerModelName（模型初命名）是文档列为 M 的字段：我们提交学习时用的名字若被厂商
     * 改写成内部命名（未文档化面实测形态是 {@code 商户码+时间戳_工控型号_序号}），初命名只会
     * 出现在这一列。模型名软锁必须同时认这两列，否则第一颗模型出来后我们会把「重训同一个模型」
     * 判成非法名字而 409 自己（2026-10-27 台账 H-33；该 409 是推导，现网本租户模型 0 条，未实跑）。</p>
     */
    public record JiangyiModelFile(long id, String modelName, String customerModelName, String modelUrl,
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
