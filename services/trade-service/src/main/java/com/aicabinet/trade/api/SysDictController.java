package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DictDtos;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.SysDictService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v2/ops/admin/dicts")
public class SysDictController {

    private final SysDictService dictService;

    public SysDictController(SysDictService dictService) {
        this.dictService = dictService;
    }

    @RequiresPermissions("ops:dict:list")
    @GetMapping
    public ApiResponse<List<DictDtos.DictTypeDto>> listTypes(HttpServletRequest request) {
        return ApiResponse.ok(dictService.listTypes(operatorId(request)));
    }

    @RequiresPermissions(value = {"ops:dict:list", "ops:dashboard:view"}, logical = RequiresPermissions.Logical.OR)
    @GetMapping("/runtime")
    public ApiResponse<DictDtos.DictRuntimeDto> runtime(HttpServletRequest request) {
        return ApiResponse.ok(dictService.runtimeMap(operatorId(request)));
    }

    @RequiresPermissions("ops:dict:list")
    @GetMapping("/{dictType}/items")
    public ApiResponse<List<DictDtos.DictDataDto>> listItems(
            HttpServletRequest request,
            @PathVariable String dictType) {
        return ApiResponse.ok(dictService.listItems(operatorId(request), dictType));
    }

    @RequiresPermissions(value = {"ops:dict:edit", "ops:dict:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/types")
    public ApiResponse<DictDtos.DictTypeDto> upsertType(
            HttpServletRequest request,
            @Valid @RequestBody DictDtos.DictTypeUpsertRequest body) {
        return ApiResponse.ok(dictService.upsertType(operatorId(request), body));
    }

    @RequiresPermissions(value = {"ops:dict:edit", "ops:dict:import"}, logical = RequiresPermissions.Logical.OR)
    @PostMapping("/{dictType}/items")
    public ApiResponse<DictDtos.DictDataDto> createItem(
            HttpServletRequest request,
            @PathVariable String dictType,
            @Valid @RequestBody DictDtos.DictDataUpsertRequest body) {
        return ApiResponse.ok(dictService.upsertItem(operatorId(request), dictType, null, body));
    }

    @RequiresPermissions("ops:dict:edit")
    @PutMapping("/{dictType}/items/{dictDataId}")
    public ApiResponse<DictDtos.DictDataDto> updateItem(
            HttpServletRequest request,
            @PathVariable String dictType,
            @PathVariable Long dictDataId,
            @Valid @RequestBody DictDtos.DictDataUpsertRequest body) {
        return ApiResponse.ok(dictService.upsertItem(operatorId(request), dictType, dictDataId, body));
    }

    @RequiresPermissions("ops:dict:edit")
    @DeleteMapping("/items/{dictDataId}")
    public ApiResponse<Void> deleteItem(
            HttpServletRequest request,
            @PathVariable Long dictDataId) {
        dictService.deleteItem(operatorId(request), dictDataId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }
}
