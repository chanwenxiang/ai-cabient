package com.aicabinet.trade.api;

import com.aicabinet.common.dto.ApiResponse;
import com.aicabinet.common.dto.DictDtos;
import com.aicabinet.trade.service.SysDictService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only dict labels for any logged-in client.
 * Does not gate payment or other capabilities — those stay in code + env flags.
 */
@RestController
@RequestMapping("/api/v2/dicts")
public class DictRuntimeController {

    private final SysDictService dictService;

    public DictRuntimeController(SysDictService dictService) {
        this.dictService = dictService;
    }

    @GetMapping("/runtime")
    public ApiResponse<DictDtos.DictRuntimeDto> runtime() {
        return ApiResponse.ok(dictService.runtimeMapForAuthenticatedUser());
    }
}
