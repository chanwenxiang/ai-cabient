package com.aicabinet.trade.client;

/**
 * 将邑第七章业务失败（envelope.status != 200）。
 *
 * <p>与 {@code JiangyiGatherClient} 统一抛 {@link IllegalStateException} 不同：文档 §7.4 明写
 * 「非 200 时 msg 为可直接展示给用户的原因」，所以这里保留 {@code status} 与原始 {@code msg}，
 * 由上层原样透出，不做二次包装或改写。附录 B 的 12 条报错按 msg 分类处置。</p>
 */
public class JiangyiRestockException extends RuntimeException {

    private final int status;
    private final String vendorMsg;

    public JiangyiRestockException(String op, int status, String vendorMsg) {
        super("将邑补货接口 " + op + " 失败 status=" + status + " msg=" + vendorMsg);
        this.status = status;
        this.vendorMsg = vendorMsg;
    }

    public int status() {
        return status;
    }

    public String vendorMsg() {
        return vendorMsg;
    }
}
