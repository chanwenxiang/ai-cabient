package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.CreateInvoiceRequest;
import com.aicabinet.common.dto.InvoiceRequestDto;
import com.aicabinet.common.dto.OrderDto;
import com.aicabinet.common.dto.OrderRefundRequest;
import com.aicabinet.common.dto.OrderRefundResultDto;
import com.aicabinet.common.dto.OrderSummaryDto;
import com.aicabinet.common.dto.PageResult;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.service.InvoiceService;
import com.aicabinet.trade.service.OrderService;
import com.aicabinet.trade.service.UnpaidOrderService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    @GetMapping
    public ApiResponse<PageResult<OrderSummaryDto>> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(orderService.listMyOrders(userId, page, size));
    }

    @GetMapping("/{orderId}")
    public ApiResponse<OrderDto> get(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(orderService.getMyOrder(userId, orderId));
    }

    @PostMapping("/{orderId}/pay")
    public ApiResponse<OrderDto> payPending(
            HttpServletRequest request,
            @PathVariable("orderId") String orderId) {
        Long userId = (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
        return ApiResponse.ok(unpaidOrderService.collectByUser(userId, orderId));
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
