package com.aicabinet.trade.dto;

import java.util.List;

/**
 * 将邑 V16（2026-10-24）第七章「新商品录入、采集与补货对接」的传输对象。
 *
 * <p>字段名与文档 §7.5~§7.19 逐字对齐；金额一律在 DTO 内保持<b>分</b>，
 * 由 {@code JiangyiRestockClient} 在出向边界经 {@code YuanFen} 转元（§7.6/§7.11 单位元）。</p>
 */
public final class JiangyiRestockDtos {

    private JiangyiRestockDtos() {}

    /** §7.2 商品状态（文档之外的取值归 UNKNOWN，不抛异常——厂商可能新增状态）。 */
    public enum State {
        NEW, READY, NEED_PHONE, REJECTED, REVIEWING, CABINET_PENDING, CABINET_REVIEWING, UNKNOWN;

        public static State parse(String raw) {
            if (raw == null || raw.isBlank()) {
                return UNKNOWN;
            }
            try {
                return valueOf(raw.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                return UNKNOWN;
            }
        }
    }

    /**
     * §7.5/§7.6/§7.9 共用的商品状态回包。
     * <p>{@code rawState} 是厂商原始 state 字符串：不认识的取值会解析成 {@link State#UNKNOWN}，
     * 此时靠原始值落库/展示（2026-11-10 实测：柜内采集上传期间 7.5 会回七态表之外的值）。</p>
     */
    public record ProductState(String barCode, State state, String nextAction,
                               Long productId, String name, String rejectCause, String rawState) {
        public ProductState(String barCode, State state, String nextAction,
                            Long productId, String name, String rejectCause) {
            this(barCode, state, nextAction, productId, name, rejectCause, null);
        }
    }

    /**
     * §7.6 新建商品入参。
     *
     * <p>{@code name/brandName/specs/category} 是「标准商品库无此条码时必填」的条件字段（文档 §7.6 C），
     * 为空时由调用方决定是否传；{@code specs} 文档要求只能英文和数字。</p>
     */
    public record ProductCreateCommand(String barCode, long salePriceCents, Long purchasePriceCents,
                                       String name, String brandName, String specs, String category,
                                       Integer categoryId, Integer colorId, String mainImg) {}

    /** §7.7 手机采集拍摄要求（张数必须读接口，禁止硬编码；§3.2 写「8 张（或 16 张）」）。 */
    public record PhoneCapturePolicy(int shotCount, List<String> shotHints) {}

    /** §7.11 单条补货商品：productId 与 barCode 二选一（文档 C）；stockNum 为补货后库存。 */
    public record RestockLine(Long productId, String barCode, int stockNum, long salePriceCents) {}

    /**
     * §7.11 提交补货。
     *
     * <p>语义：<b>一次提交 = 该层补货后的最终库存</b>，该层原有但未列出的商品会被下架。
     * {@code requestId} 网络重试必须保持不变且内容一致；内容变化时必须换新号（附录 B）。</p>
     */
    public record RestockSubmitCommand(String requestId, String deviceIdentifier, int floorNum,
                                       String floorOrientation, String picUrl, List<RestockLine> products) {}

    /** 本柜待柜内采集商品（§7.14，§7.11 回包的 cabinetCaptureProducts 同结构）。 */
    public record CabinetCaptureProduct(long productId, String name, String barCode,
                                        String mainImg, boolean onShelf) {}

    /** §7.11 回包。 */
    public record RestockSubmitResult(String historyNo, List<CabinetCaptureProduct> cabinetCaptureProducts) {}

    /** §7.12 识别参数下发进度（stage ∈ BUILDING/READY/SENT/APPLIED/NOT_REQUIRED）。 */
    public record RestockProgress(boolean ready, String stage, int total, int applied,
                                  List<String> pendingNames, List<String> pendingCodes) {}

    /** §7.15~§7.18 柜内采集会话（phase 见 §7.16）。 */
    public record CabinetCaptureSession(int sessionId, String deviceIdentifier, long productId,
                                        String phase, String error) {}

    /** §7.19 商户商品类目。 */
    public record ProductCategory(int categoryId, String name, int parentId) {}
}
