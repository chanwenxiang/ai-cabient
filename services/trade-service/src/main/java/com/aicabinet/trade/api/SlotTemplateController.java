package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.trade.auth.AuthInterceptor;
import com.aicabinet.trade.auth.RequiresPermissions;
import com.aicabinet.trade.service.SlotTemplateService;
import com.aicabinet.trade.service.SlotTemplateService.SlotTemplateView;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * C3 商品模板（铺货模板）：把设备货道陈列存为模板 / 套用到其他设备（V368）。
 * 权限与货道编辑同口径（ops:device:edit）。
 */
@RestController
@RequestMapping("/api/v2/ops/admin/slot-templates")
public class SlotTemplateController {

    private final SlotTemplateService service;

    public SlotTemplateController(SlotTemplateService service) {
        this.service = service;
    }

    @RequiresPermissions("ops:device:list")
    @GetMapping
    public ApiResponse<List<SlotTemplateView>> list() {
        return ApiResponse.ok(service.list());
    }

    /** 把 sourceDeviceId 当前已绑定 SKU 的货道存为模板。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping
    public ApiResponse<SlotTemplateView> saveFromDevice(HttpServletRequest request,
                                                        @RequestBody Map<String, Object> body) {
        return ApiResponse.ok(service.saveFromDevice(
                operatorId(request),
                str(body.get("templateName")),
                str(body.get("sourceDeviceId"))));
    }

    /** 套用模板到目标设备（货道缺失补骨架、已有绑定覆盖、价格 upsert）。 */
    @RequiresPermissions("ops:device:edit")
    @PostMapping("/{templateId}/apply/{deviceId}")
    public ApiResponse<Map<String, Object>> apply(HttpServletRequest request,
                                                  @PathVariable("templateId") Long templateId,
                                                  @PathVariable("deviceId") String deviceId) {
        int applied = service.apply(operatorId(request), templateId, deviceId);
        return ApiResponse.ok(Map.of("applied", applied));
    }

    @RequiresPermissions("ops:device:edit")
    @DeleteMapping("/{templateId}")
    public ApiResponse<Void> delete(@PathVariable("templateId") Long templateId) {
        service.delete(templateId);
        return ApiResponse.ok(null);
    }

    private static Long operatorId(HttpServletRequest request) {
        return (Long) request.getAttribute(AuthInterceptor.ATTR_USER_ID);
    }

    private static String str(Object v) {
        return v == null ? null : String.valueOf(v);
    }
}
