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

    public SysDictService(SysDictTypeMapper typeRepository,
                          SysDictDataMapper dataRepository,
                          PermissionService permissionService,
                          AdminAuditService auditService) {
        this.typeRepository = typeRepository;
        this.dataRepository = dataRepository;
        this.permissionService = permissionService;
        this.auditService = auditService;
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
        String name = requireText(req.dictName(), "字典名称").trim();
        SysDictType entity = typeRepository.findById(type).orElseGet(SysDictType::new);
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
        requireType(dictType);
        String value = requireText(req.dictValue(), "字典值").trim().toUpperCase();
        String label = requireText(req.dictLabel(), "字典标签").trim();
        SysDictData entity;
        if (dictDataId != null) {
            entity = dataRepository.findById(dictDataId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在"));
            if (!dictType.equals(entity.getDictType())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "字典类型不匹配");
            }
        } else {
            entity = dataRepository.findByDictTypeAndDictValue(dictType, value).orElseGet(SysDictData::new);
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
        SysDictData entity = dataRepository.findById(dictDataId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "字典项不存在"));
        dataRepository.delete(entity);
        auditService.record(operatorId, "DICT_DATA_DELETE", "DICT_DATA",
                entity.getDictType() + ":" + entity.getDictValue(), entity.getDictLabel());
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
