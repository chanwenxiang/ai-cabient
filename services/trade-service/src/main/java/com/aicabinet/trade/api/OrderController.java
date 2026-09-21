package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateInvoiceRequest;
import com.aicabinet.common.dto.InvoiceRequestDto;
import com.aicabinet.common.dto.OrderReadModel;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.OrderViews;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.common.dto.PayOrderRequest;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.InvoiceService;
import com.aicabinet.trade.service.OrderService;
import com.aicabinet.trade.service.UnpaidOrderService;
import com.fasterxml.jackson.annotation.JsonView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v2/orders")
public class OrderController {

    private final OrderService orderService;
    private final UnpaidOrderService unpaidOrderService;
    private final InvoiceService invoiceService;

    public OrderController(OrderService orderService,
                           UnpaidOrderService unpaidOrderService,
                           InvoiceService invoiceService) {
        this.orderService = orderService;
        this.unpaidOrderService = unpaidOrderService;
        this.invoiceService = invoiceService;
    }

    @JsonView(OrderViews.Consumer.class)
    @GetMapping
    public ApiResponse<PageResult<OrderReadModel>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(orderService.listMyOrders(userId, page, size));
    }

    /** C-P2-9：待补缴角标，须声明在 /{orderId} 之前。 */
    @GetMapping("/pending-count")
    public ApiResponse<java.util.Map<String, Long>> pendingCount(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(java.util.Map.of("count", orderService.countMyPendingOrders(userId)));
    }

    @JsonView(OrderViews.Consumer.class)
    @GetMapping("/{orderId}")
    public ApiResponse<OrderReadModel> get(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(orderService.getMyOrder(userId, orderId));
    }

    @GetMapping(value = "/{orderId}/video", produces = {MediaType.APPLICATION_OCTET_STREAM_VALUE, "video/mp4", "video/webm"})
    public void streamVideo(
            HttpServletRequest request,
            HttpServletResponse response,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        orderService.streamMyOrderVideo(userId, orderId, request, response);
    }

    /**
     * 补缴待支付订单。
     *
     * <p>F6：可选 body {@code {"channel":"BALANCE|WECHAT|ALIPAY"}} 表示消费者在结算页**显式选择**的支付方式。
     * 不传 body（老客户端）或 {@code channel} 为空 ⇒ 服务端按既有规则自动决策，行为与接入前一致。
     * 显式选择时**不降级**：所选渠道未就绪返回 412（由前端提示改选或先去开通）。
     */
    @JsonView(OrderViews.Consumer.class)
    @PostMapping("/{orderId}/pay")
    public ApiResponse<OrderReadModel> payPending(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody(required = false) PayOrderRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(unpaidOrderService.collectByUser(userId, orderId,
                body == null ? null : body.channel()));
    }

    @PostMapping("/{orderId}/refund")
    public ApiResponse<OrderRefundResultDto> refund(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody OrderRefundRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(orderService.refundMyOrder(userId, orderId, body));
    }

    @PostMapping("/{orderId}/invoice")
    public ApiResponse<InvoiceRequestDto> applyInvoice(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId,
            @Valid @RequestBody CreateInvoiceRequest body) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(invoiceService.applyByConsumer(userId, orderId, body));
    }
}
