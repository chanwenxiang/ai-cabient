package com.aicabinet.trade.service;

import com.aicabinet.common.dto.CreateInvoiceRequest;
import com.aicabinet.common.dto.InvoiceRequestDto;
import com.aicabinet.common.dto.MerchantTaxProfileDto;
import com.aicabinet.trade.domain.CabinetOrder;
import com.aicabinet.trade.domain.DeviceInfo;
import com.aicabinet.trade.domain.InvoiceRequest;
import com.aicabinet.trade.domain.MerchantTaxProfile;
import com.aicabinet.trade.mapper.CabinetOrderMapper;
import com.aicabinet.trade.mapper.DeviceInfoMapper;
import com.aicabinet.trade.mapper.InvoiceRequestMapper;
import com.aicabinet.trade.mapper.MerchantTaxProfileMapper;
import com.aicabinet.trade.support.ApiMessages;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

@Service
public class InvoiceService {

    private static final Set<String> INVOICEABLE = Set.of("PAID", "COMPLETED", "PARTIAL_REFUNDED");

    private final InvoiceRequestMapper invoiceRepository;
    private final MerchantTaxProfileMapper taxProfileRepository;
    private final CabinetOrderMapper orderRepository;
    private final DeviceInfoMapper deviceRepository;
    private final PermissionService permissionService;
    private final MerchantScopeService merchantScopeService;

    public InvoiceService(InvoiceRequestMapper invoiceRepository,
                          MerchantTaxProfileMapper taxProfileRepository,
                          CabinetOrderMapper orderRepository,
                          DeviceInfoMapper deviceRepository,
                          PermissionService permissionService,
                          MerchantScopeService merchantScopeService) {
        this.invoiceRepository = invoiceRepository;
        this.taxProfileRepository = taxProfileRepository;
        this.orderRepository = orderRepository;
        this.deviceRepository = deviceRepository;
        this.permissionService = permissionService;
        this.merchantScopeService = merchantScopeService;
    }

    @Transactional
    public InvoiceRequestDto applyByConsumer(Long userId, String orderId, CreateInvoiceRequest body) {
        if (body == null || body.title() == null || body.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写发票抬头");
        }
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        if (!userId.equals(order.getUserId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "无权开票该订单");
        }
        String st = order.getStatus() == null ? "" : order.getStatus().toUpperCase();
        if (!INVOICEABLE.contains(st)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "当前订单状态不可开票");
        }
        int amount = Math.max(0, order.getTotalAmountCents());
        if (amount <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "订单金额为 0，无法开票");
        }
        if (invoiceRepository.findActiveByOrderId(orderId).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该订单已有开票申请");
        }
        InvoiceRequest row = new InvoiceRequest();
        row.setOrderId(orderId);
        row.setUserId(userId);
        row.setTitle(body.title().trim());
        row.setTaxNo(blankToNull(body.taxNo()));
        row.setEmail(blankToNull(body.email()));
        row.setAmountCents(amount);
        row.setStatus("PENDING");
        row.setCreatedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        invoiceRepository.insert(row);
        return toDto(row);
    }

    @Transactional(readOnly = true)
    public List<InvoiceRequestDto> listMine(Long userId) {
        return invoiceRepository.findByUserIdOrderByCreatedAtDesc(userId).stream().map(this::toDto).toList();
    }

    @Transactional(readOnly = true)
    public List<InvoiceRequestDto> listForOps(Long operatorId, String status) {
        permissionService.requireAnyPermission(operatorId, "ops:invoice:list", "ops:finance:view");
        return invoiceRepository.findByStatusOrderByCreatedAtDesc(status, 100).stream().map(this::toDto).toList();
    }

    @Transactional
    public InvoiceRequestDto issue(Long operatorId, Long invoiceId) {
        permissionService.requirePermission(operatorId, "ops:invoice:edit");
        InvoiceRequest row = invoiceRepository.selectById(invoiceId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开票申请不存在");
        }
        if (!"PENDING".equalsIgnoreCase(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待处理申请可开具");
        }
        row.setStatus("ISSUED");
        row.setIssuedAt(Instant.now());
        row.setUpdatedAt(Instant.now());
        invoiceRepository.updateById(row);
        return toDto(row);
    }

    @Transactional
    public InvoiceRequestDto reject(Long operatorId, Long invoiceId, String reason) {
        permissionService.requirePermission(operatorId, "ops:invoice:edit");
        InvoiceRequest row = invoiceRepository.selectById(invoiceId);
        if (row == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "开票申请不存在");
        }
        if (!"PENDING".equalsIgnoreCase(row.getStatus())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅待处理申请可驳回");
        }
        row.setStatus("REJECTED");
        row.setRejectReason(reason == null || reason.isBlank() ? "不符合开票条件" : reason.trim());
        row.setUpdatedAt(Instant.now());
        invoiceRepository.updateById(row);
        return toDto(row);
    }

    @Transactional(readOnly = true)
    public MerchantTaxProfileDto getTaxProfile(Long userId, String merchantId) {
        requireMerchantScope(userId, merchantId);
        MerchantTaxProfile p = taxProfileRepository.selectById(merchantId);
        if (p == null) {
            return new MerchantTaxProfileDto(merchantId, "", "", null, null, null, null);
        }
        return toTaxDto(p);
    }

    @Transactional
    public MerchantTaxProfileDto saveTaxProfile(Long userId, MerchantTaxProfileDto body) {
        if (body == null || body.merchantId() == null || body.merchantId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少商户编号");
        }
        requireMerchantScope(userId, body.merchantId());
        MerchantTaxProfile p = taxProfileRepository.selectById(body.merchantId());
        boolean insert = p == null;
        if (insert) {
            p = new MerchantTaxProfile();
            p.setMerchantId(body.merchantId().trim());
        }
        p.setCompanyName(body.companyName().trim());
        p.setTaxNo(body.taxNo().trim());
        p.setAddress(blankToNull(body.address()));
        p.setBankName(blankToNull(body.bankName()));
        p.setBankAccount(blankToNull(body.bankAccount()));
        p.setPhone(blankToNull(body.phone()));
        p.setUpdatedAt(Instant.now());
        if (insert) {
            taxProfileRepository.insert(p);
        } else {
            taxProfileRepository.updateById(p);
        }
        return toTaxDto(p);
    }

    private void requireMerchantScope(Long userId, String merchantId) {
        permissionService.requirePermission(userId, "merchant:portal:access");
        Set<String> allowed = merchantScopeService.allowedMerchantIds(userId);
        if (allowed == null || allowed.isEmpty() || !allowed.contains(merchantId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ApiMessages.PERMISSION_DENIED);
        }
    }

    /** 按订单设备反查商户税号资料（运营开票参考）。 */
    @Transactional(readOnly = true)
    public MerchantTaxProfileDto taxProfileForOrder(Long operatorId, String orderId) {
        permissionService.requireAnyPermission(operatorId, "ops:invoice:list", "ops:finance:view");
        CabinetOrder order = orderRepository.findById(orderId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "订单不存在"));
        DeviceInfo device = deviceRepository.findById(order.getDeviceId()).orElse(null);
        if (device == null || device.getMerchantId() == null) {
            return new MerchantTaxProfileDto(null, "", "", null, null, null, null);
        }
        MerchantTaxProfile p = taxProfileRepository.selectById(device.getMerchantId());
        return p == null
                ? new MerchantTaxProfileDto(device.getMerchantId(), "", "", null, null, null, null)
                : toTaxDto(p);
    }

    private InvoiceRequestDto toDto(InvoiceRequest r) {
        return new InvoiceRequestDto(
                r.getInvoiceId(), r.getOrderId(), r.getUserId(), r.getTitle(), r.getTaxNo(),
                r.getEmail(), r.getAmountCents(), r.getStatus(), r.getRejectReason(),
                r.getCreatedAt(), r.getIssuedAt());
    }

    private static MerchantTaxProfileDto toTaxDto(MerchantTaxProfile p) {
        return new MerchantTaxProfileDto(
                p.getMerchantId(), p.getCompanyName(), p.getTaxNo(),
                p.getAddress(), p.getBankName(), p.getBankAccount(), p.getPhone());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
