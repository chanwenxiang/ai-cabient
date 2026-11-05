package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiRestockClient;
import com.aicabinet.trade.domain.JiangyiSkuJiangyiLink;
import com.aicabinet.trade.dto.JiangyiRestockDtos.PhoneCapturePolicy;
import com.aicabinet.trade.dto.JiangyiRestockDtos.ProductState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 手机采集编排（V16 §7.7 拍摄要求 → §7.8 上传 → §7.9 提交）。
 *
 * <p>照片来源是<b>现场手机拍摄的实物图</b>，不是商品主图：§7.3 明文「拍摄的必须是实物，
 * 不能网图或商品宣传图」，因此本服务不读我方存储、也不接受外部 URL——只接受调用方
 * 上传的字节，逐张转发 §7.8 取得厂商地址后按顺序提交 §7.9（§7.9 只接受 7.8 返回的地址）。</p>
 *
 * <p>张数以 §7.7 返回的 {@code shotCount} 为准（§3.2 写「8 张（或 16 张）」），不做硬编码。</p>
 */
@Service
public class JiangyiPhoneCaptureService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiPhoneCaptureService.class);

    private final JiangyiRestockClient client;
    private final JiangyiProductStateService productStateService;

    public JiangyiPhoneCaptureService(JiangyiRestockClient client,
                                      JiangyiProductStateService productStateService) {
        this.client = client;
        this.productStateService = productStateService;
    }

    /** 一张待提交的现场照片。 */
    public record Photo(byte[] content, String filename, String contentType) {}

    /** §7.7 拍摄要求（张数 + 每张角度提示）。 */
    public PhoneCapturePolicy policy() {
        return client.phoneCapturePolicy();
    }

    /**
     * 提交一个条码的手机采集。
     *
     * <p>先用 §7.5 取 {@code productId}（文档：商品已建档时返回）并校验状态——
     * 只有 {@code NEED_PHONE} / {@code REJECTED} 才该重拍；{@code READY}（免采集）或
     * {@code NEW}（未建档）直接拒绝，避免把工单打到错误阶段。</p>
     */
    public Optional<JiangyiSkuJiangyiLink> submit(String barCode, List<Photo> photos) {
        if (barCode == null || barCode.isBlank()) {
            throw new IllegalArgumentException("barCode 不能为空");
        }
        if (photos == null || photos.isEmpty()) {
            throw new IllegalArgumentException("照片不能为空（§7.9）");
        }
        PhoneCapturePolicy policy = client.phoneCapturePolicy();
        if (policy.shotCount() > 0 && photos.size() != policy.shotCount()) {
            throw new IllegalArgumentException(
                    "照片张数必须等于 " + policy.shotCount() + "（§7.7/§7.9）");
        }
        ProductState current = client.lookup(barCode.trim());
        requireCapturable(current);
        Long productId = current.productId();
        if (productId == null) {
            throw new IllegalStateException("条码未建档，无法提交手机采集: " + barCode);
        }
        List<String> vendorUrls = new ArrayList<>();
        for (Photo photo : photos) {
            vendorUrls.add(client.uploadImage(photo.content(), photo.filename(), photo.contentType()));
        }
        log.info("jiangyi phone capture submit barCode={} productId={} photos={}",
                barCode, productId, vendorUrls.size());
        return productStateService.submitPhoneCapture(productId, vendorUrls);
    }

    private static void requireCapturable(ProductState current) {
        switch (current.state()) {
            case NEED_PHONE, REJECTED -> {
                // 正常路径
            }
            case READY -> throw new IllegalStateException("该商品免采集（READY），可直接补货（§7.11）");
            case NEW -> throw new IllegalStateException("该商品尚未建档（NEW），请先调用新建商品（§7.6）");
            default -> throw new IllegalStateException(
                    "当前状态 " + current.state().name() + " 不允许提交手机采集（§7.2）");
        }
    }
}
