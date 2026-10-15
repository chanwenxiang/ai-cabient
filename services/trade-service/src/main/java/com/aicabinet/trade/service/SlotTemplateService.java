package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.DeviceSkuPrice;
import com.aicabinet.trade.domain.DeviceSkuPriceId;
import com.aicabinet.trade.domain.DeviceSlot;
import com.aicabinet.trade.domain.DeviceSlotId;
import com.aicabinet.trade.domain.SkuCatalog;
import com.aicabinet.trade.domain.SlotTemplate;
import com.aicabinet.trade.domain.SlotTemplateLine;
import com.aicabinet.trade.mapper.DeviceSkuPriceMapper;
import com.aicabinet.trade.mapper.DeviceSlotMapper;
import com.aicabinet.trade.mapper.SkuCatalogMapper;
import com.aicabinet.trade.mapper.SlotTemplateLineMapper;
import com.aicabinet.trade.mapper.SlotTemplateMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * C3 商品模板（2026-10-15 backlog 拍板项，老系统 ego_machine_aisle_template 对位）：
 * 把已配好的设备货道陈列（货道×SKU×售价×目层）存为命名模板，供同型设备一键套用。
 *
 * <p>与老系统差异（有意不同，V368 头注释）：老模板是「新机开通铺货道」，我方设备开通骨架由
 * 代码内置机型模板（{@link PlanogramTemplateService}）承担——本服务只做 SKU/价格/目层的
 * <b>设备间复制</b>：套用时目标货道缺失自动补骨架，货道已有绑定则覆盖（套用前显式确认）。
 * 价格复制走 {@link DeviceSkuPriceMapper} upsert（价格行缺失创建、存在更新）。
 */
@Service
public class SlotTemplateService {

    private static final Logger log = LoggerFactory.getLogger(SlotTemplateService.class);

    private final SlotTemplateMapper templateRepository;
    private final SlotTemplateLineMapper lineRepository;
    private final DeviceSlotMapper slotRepository;
    private final DeviceSkuPriceMapper priceRepository;
    private final SkuCatalogMapper skuCatalogRepository;

    public SlotTemplateService(SlotTemplateMapper templateRepository,
                               SlotTemplateLineMapper lineRepository,
                               DeviceSlotMapper slotRepository,
                               DeviceSkuPriceMapper priceRepository,
                               SkuCatalogMapper skuCatalogRepository) {
        this.templateRepository = templateRepository;
        this.lineRepository = lineRepository;
        this.slotRepository = slotRepository;
        this.priceRepository = priceRepository;
        this.skuCatalogRepository = skuCatalogRepository;
    }

    /** 模板 + 行全量（运营台列表用）。 */
    @Transactional(readOnly = true)
    public List<SlotTemplateView> list() {
        List<SlotTemplateView> out = new ArrayList<>();
        for (SlotTemplate t : templateRepository.selectList(null)) {
            out.add(new SlotTemplateView(t.getTemplateId(), t.getTemplateName(), t.getCreatedAt(),
                    lineRepository.findByTemplateId(t.getTemplateId()).size()));
        }
        out.sort((a, b) -> Long.compare(b.templateId(), a.templateId()));
        return out;
    }

    /** 把某台设备当前已绑定 SKU 的货道存为模板（空绑定的货道不进模板）。 */
    @Transactional
    public SlotTemplateView saveFromDevice(Long operatorId, String templateName, String sourceDeviceId) {
        if (templateName == null || templateName.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称必填");
        }
        String name = templateName.trim();
        if (name.length() > 64) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "模板名称超长（≤64 字符）");
        }
        if (templateRepository.selectCount(null) >= 50) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模板数量已达上限（50），请先清理不用的模板");
        }
        if (templateRepository.selectCount(null) > 0 && findByName(name) != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模板名称已存在：" + name);
        }
        List<DeviceSlot> slots = slotRepository.selectList(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<DeviceSlot>lambdaQuery()
                        .eq(DeviceSlot::getDeviceId, sourceDeviceId));
        List<SlotTemplateLine> lines = new ArrayList<>();
        for (DeviceSlot slot : slots) {
            if (slot.getAssignedSkuId() == null || slot.getAssignedSkuId().isBlank()) {
                continue;
            }
            SlotTemplateLine line = new SlotTemplateLine();
            line.setSlotCode(slot.getSlotCode());
            line.setRowNo(slot.getRowNo());
            line.setColNo(slot.getColNo());
            line.setSkuId(slot.getAssignedSkuId());
            line.setPriceCents(currentPrice(sourceDeviceId, slot.getAssignedSkuId()));
            line.setParLevel(slot.getParLevel());
            lines.add(line);
        }
        if (lines.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该设备没有已绑定商品的货道，无可存模板内容");
        }
        SlotTemplate template = new SlotTemplate();
        template.setTemplateName(name);
        template.setCreatedBy(operatorId);
        template.setCreatedAt(Instant.now());
        templateRepository.insert(template);
        for (SlotTemplateLine line : lines) {
            line.setTemplateId(template.getTemplateId());
            lineRepository.insert(line);
        }
        log.info("slot template saved from device={} template={} lines={}",
                sourceDeviceId, name, lines.size());
        return new SlotTemplateView(template.getTemplateId(), name, template.getCreatedAt(), lines.size());
    }

    /** 套用模板到目标设备：货道缺失补骨架、已有绑定覆盖、价格 upsert。返回生效货道数。 */
    @Transactional
    public int apply(Long operatorId, Long templateId, String targetDeviceId) {
        SlotTemplate template = templateRepository.selectById(templateId);
        if (template == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模板不存在：" + templateId);
        }
        List<SlotTemplateLine> lines = lineRepository.findByTemplateId(templateId);
        int applied = 0;
        for (SlotTemplateLine line : lines) {
            DeviceSlotId id = new DeviceSlotId(targetDeviceId, line.getSlotCode());
            DeviceSlot slot = slotRepository.findById(id).orElse(null);
            if (slot == null) {
                slot = new DeviceSlot();
                slot.setId(id);
            }
            if (!skuExists(line.getSkuId())) {
                log.warn("slot template apply skipped missing sku device={} slot={} sku={}",
                        targetDeviceId, line.getSlotCode(), line.getSkuId());
                continue;
            }
            slot.setRowNo(line.getRowNo());
            slot.setColNo(line.getColNo());
            slot.setAssignedSkuId(line.getSkuId());
            slot.setAssignedAt(Instant.now());
            if (slot.getParLevel() <= 0) {
                slot.setParLevel(line.getParLevel());
            }
            if (slot.getMinLevel() <= 0) {
                slot.setMinLevel(Math.max(1, line.getParLevel() / 4));
            }
            if (slot.getMaxLevel() <= 0) {
                slot.setMaxLevel(line.getParLevel());
            }
            slot.setEnabled(true);
            slotRepository.save(slot);
            upsertPrice(targetDeviceId, line.getSkuId(), line.getPriceCents());
            applied++;
        }
        log.info("slot template applied device={} template={} applied={}/{}",
                targetDeviceId, template.getTemplateName(), applied, lines.size());
        return applied;
    }

    @Transactional
    public void delete(Long templateId) {
        lineRepository.deleteByTemplateId(templateId);
        templateRepository.deleteById(templateId);
    }

    private Long currentPrice(String deviceId, String skuId) {
        return priceRepository.findByDeviceIdAndSkuId(deviceId, skuId)
                .map(DeviceSkuPrice::getPriceCents)
                .map(Long::new)
                .orElse(0L);
    }

    private void upsertPrice(String deviceId, String skuId, long priceCents) {
        if (priceCents <= 0) {
            return;
        }
        DeviceSkuPrice price = priceRepository.findByDeviceIdAndSkuId(deviceId, skuId).orElse(null);
        if (price == null) {
            price = new DeviceSkuPrice();
            price.setId(new DeviceSkuPriceId(deviceId, skuId));
        }
        price.setPriceCents((int) Math.min(priceCents, Integer.MAX_VALUE));
        price.setUpdatedAt(Instant.now());
        priceRepository.save(price);
    }

    private boolean skuExists(String skuId) {
        return skuCatalogRepository.findById(skuId)
                .map(SkuCatalog::getSkuId)
                .map(s -> s.equals(skuId))
                .orElse(false);
    }

    private SlotTemplate findByName(String name) {
        return templateRepository.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SlotTemplate>lambdaQuery()
                        .eq(SlotTemplate::getTemplateName, name)
                        .last("LIMIT 1"));
    }

    public record SlotTemplateView(Long templateId, String templateName, Instant createdAt, int lineCount) {}
}
