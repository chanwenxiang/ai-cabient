package com.aicabinet.trade.service;

import com.aicabinet.common.dto.DictDtos;
import com.aicabinet.trade.domain.SysDictData;
import com.aicabinet.trade.domain.SysDictType;
import com.aicabinet.trade.mapper.SysDictDataMapper;
import com.aicabinet.trade.mapper.SysDictTypeMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class SysDictService {

    private final SysDictTypeMapper typeRepository;
    private final SysDictDataMapper dataRepository;
    private final PermissionService permissionService;
    private final AdminAuditService auditService;
    private final DistributedLockService distributedLockService;

    public SysDictService(SysDictTypeMapper typeRepository,
                          SysDictDataMapper dataRepository,
                          PermissionService permissionService,
                          AdminAuditService auditService,
                          DistributedLockService distributedLockService) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
        this.distributedLockService = distributedLockService;
    }

    public List<DictDtos.DictTypeDto> listTypes(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:dict:list");
        return typeRepository.findAllByOrderBySortOrderAscDictTypeAsc().stream()
                .map(t -> new DictDtos.DictTypeDto(
                        t.getDictType(),
                        t.getDictName(),
                        t.getStatus(),
                        t.getRemark(),
                        t.getSortOrder(),
                        (int) dataRepository.countByDictType(t.getDictType()),
                        t.getUpdatedAt()
                ))
                .toList();
    }

    public List<DictDtos.DictDataDto> listItems(Long operatorId, String dictType) {
        permissionService.requirePermission(operatorId, "ops:dict:list");
        requireType(dictType);
        return dataRepository.findByDictTypeOrderBySortOrderAscDictValueAsc(dictType).stream()
                .map(this::toDataDto)
                .toList();
    }

    public DictDtos.DictRuntimeDto runtimeMap(Long operatorId) {
        permissionService.requirePermission(operatorId, "ops:dict:list");
        return buildActiveRuntimeMap();
    }

    /** Labels for any authenticated client (consumer / merchant / admin). Display only — not capability flags. */
    public DictDtos.DictRuntimeDto runtimeMapForAuthenticatedUser() {
        return buildActiveRuntimeMap();
    }

  public static final String DEVICE_FAULT_ISSUE = "device_fault_issue";

    /** 展示用：含已停用项，便于历史数据仍显示运营配置过的中文。 */
    @Transactional(readOnly = true)
    public String labelOf(String dictType, String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback != null ? fallback : "暂无";
        }
        String type = dictType.trim().toLowerCase();
        String key = value.trim().toUpperCase();
        return dataRepository.findByDictTypeAndDictValue(type, key)
                .map(SysDictData::getDictLabel)
                .filter(label -> label != null && !label.isBlank())
                .orElse(fallback != null ? fallback : key);
    }

    @Transactional(readOnly = true)
    public boolean isActiveDictValue(String dictType, String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String type = dictType.trim().toLowerCase();
        String key = value.trim().toUpperCase();
        return dataRepository.findByDictTypeAndDictValue(type, key)
                .filter(row -> "ACTIVE".equalsIgnoreCase(row.getStatus()))
                .isPresent();
    }

    private DictDtos.DictRuntimeDto buildActiveRuntimeMap() {
        Map<String, List<DictDtos.DictDataDto>> map = new LinkedHashMap<>();
        for (SysDictData row : dataRepository.findByStatusOrderByDictTypeAscSortOrderAsc("ACTIVE")) {
            map.computeIfAbsent(row.getDictType(), k -> new ArrayList<>()).add(toDataDto(row));
        }
        return new DictDtos.DictRuntimeDto(map);
    }

    @Transactional
    public DictDtos.DictTypeDto upsertType(Long operatorId, DictDtos.DictTypeUpsertRequest req) {
        permissionService.requirePermission(operatorId, "ops:dict:edit");
        String type = requireText(req.dictType(), "字典类型").trim().toLowerCase();
        return runWithDictTypeLock(type, () -> doUpsertType(operatorId, req, type));
    }

    private DictDtos.DictTypeDto doUpsertType(Long operatorId, DictDtos.DictTypeUpsertRequest req, String type) {
        String name = requireText(req.dictName(), "字典名称").trim();
        SysDictType entity = typeRepository.findByIdForUpdate(type).orElseGet(SysDictType::new);
        boolean created = entity.getDictType() == null;
        entity.setDictType(type);
        entity.setDictName(name);
        entity.setStatus(normalizeStatus(req.status()));
        entity.setRemark(blankToNull(req.remark()));
        if (req.sortOrder() != null) {
            entity.setSortOrder(req.sortOrder());
        }
        typeRepository.save(entity);
        auditService.record(operatorId, created ? "DICT_TYPE_CREATE" : "DICT_TYPE_UPDATE",
                "DICT_TYPE", type, name);
        return new DictDtos.DictTypeDto(
                entity.getDictType(), entity.getDictName(), entity.getStatus(), entity.getRemark(),
                entity.getSortOrder(), (int) dataRepository.countByDictType(type), entity.getUpdatedAt()
        );
    }

    @Transactional
    public DictDtos.DictDataDto upsertItem(Long operatorId, String dictType, Long dictDataId,
                                           DictDtos.DictDataUpsertRequest req) {
        permissionService.requirePermission(operatorId, "ops:dict:edit");
        String type = dictType.trim().toLowerCase();
        return runWithDictTypeLock(type, () -> doUpsertItem(operatorId, type, dictDataId, req));
    }

    private DictDtos.DictDataDto doUpsertItem(Long operatorId, String dictType, Long dictDataId,
                                              DictDtos.DictDataUpsertRequest req) {
        requireType(dictType);
        String value = requireText(req.dictValue(), "字典值").trim().toUpperCase();
        String label = requireText(req.dictLabel(), "字典标签").trim();
        SysDictData entity;
        if (dictDataId != null) {
            entity = dataRepository.findByIdForUpdate(dictDataId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在"));
            if (!dictType.equals(entity.getDictType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字典类型不匹配");
            }
        } else {
            entity = dataRepository.findByDictTypeAndDictValueForUpdate(dictType, value).orElseGet(SysDictData::new);
            entity.setDictType(dictType);
        }
        boolean created = entity.getDictDataId() == null;
        dataRepository.findByDictTypeAndDictValue(dictType, value).ifPresent(existing -> {
            if (entity.getDictDataId() == null || !existing.getDictDataId().equals(entity.getDictDataId())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "字典值已存在");
            }
        });
        entity.setDictValue(value);
        entity.setDictLabel(label);
        entity.setStatus(normalizeStatus(req.status()));
        entity.setRemark(blankToNull(req.remark()));
        if (req.sortOrder() != null) {
            entity.setSortOrder(req.sortOrder());
        }
        dataRepository.save(entity);
        auditService.record(operatorId, created ? "DICT_DATA_CREATE" : "DICT_DATA_UPDATE",
                "DICT_DATA", dictType + ":" + value, label);
        return toDataDto(entity);
    }

    @Transactional
    public void deleteItem(Long operatorId, Long dictDataId) {
        permissionService.requirePermission(operatorId, "ops:dict:edit");
        runWithDictDataIdLock(dictDataId, () -> {
            SysDictData entity = dataRepository.findByIdForUpdate(dictDataId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在"));
            dataRepository.delete(entity);
            auditService.record(operatorId, "DICT_DATA_DELETE", "DICT_DATA",
                    entity.getDictType() + ":" + entity.getDictValue(), entity.getDictLabel());
            return null;
        });
    }

    /** 删除字典类型及其全部字典项。 */
    @Transactional
    public void deleteType(Long operatorId, String dictType) {
        permissionService.requirePermission(operatorId, "ops:dict:edit");
        String type = requireText(dictType, "字典类型").trim();
        runWithDictTypeLock(type, () -> {
            SysDictType entity = typeRepository.findByIdForUpdate(type)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典类型不存在"));
            long itemCount = dataRepository.countByDictType(type);
            dataRepository.deleteByDictType(type);
            typeRepository.deleteById(type);
            auditService.record(operatorId, "DICT_TYPE_DELETE", "DICT_TYPE", type,
                    entity.getDictName() + "（含 " + itemCount + " 项）");
            return null;
        });
    }

    static String dictTypeLockKey(String dictType) {
        return "dict:type:" + dictType.trim().toLowerCase();
    }

    static String dictDataIdLockKey(Long dictDataId) {
        return "dict:data:" + dictDataId;
    }

    private <T> T runWithDictTypeLock(String dictType, java.util.function.Supplier<T> action) {
        String key = dictTypeLockKey(dictType);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "字典处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private <T> T runWithDictDataIdLock(Long dictDataId, java.util.function.Supplier<T> action) {
        String key = dictDataIdLockKey(dictDataId);
        if (!distributedLockService.tryLock(key, 60, 5)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "字典项处理中，请稍后重试");
        }
        try {
            return action.get();
        } finally {
            distributedLockService.unlock(key);
        }
    }

    private SysDictType requireType(String dictType) {
        return typeRepository.findById(dictType)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典类型不存在"));
    }

    private DictDtos.DictDataDto toDataDto(SysDictData row) {
        return new DictDtos.DictDataDto(
                row.getDictDataId(),
                row.getDictType(),
                row.getDictValue(),
                row.getDictLabel(),
                row.getSortOrder(),
                row.getStatus(),
                row.getRemark()
        );
    }

    private static String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "ACTIVE";
        }
        return "INACTIVE".equalsIgnoreCase(status) ? "INACTIVE" : "ACTIVE";
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + "不能为空");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
