package com.aicabinet.common.dto;

/**
 * 订单读模型 Jackson 视图：按端裁剪字段。
 * Consumer/Merchant/Admin 均继承 Public，公共字段只需标 Public。
 */
public final class OrderViews {

    private OrderViews() {
    }

    public interface Public {
    }

    public interface Consumer extends Public {
    }

    public interface Merchant extends Public {
    }

    public interface Admin extends Public {
    }
}
