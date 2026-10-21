package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

/**
 * 设备面未注册路径的统一失败出口（2026-10-19 真机接入补齐）。
 *
 * <p><b>成因注释</b>：域名切到我方后，固件那 43 条实测 HTTP 路径全部落在本网关。
 * 已注册的走各自控制器；本 handler 只吃<b>剩余</b>路径（Spring PathPattern 比较器恒把
 * 字面量路径排在 {@code /jiangyi/api/**} 之前，不会遮蔽已实现端点，见 DeviceCompatRoutingIT）。</p>
 *
 * <p><b>为什么失败（404）而不回 200 空值</b>：未实现路径里既有上报类，也有<b>读取类</b>
 * （{@code feature/getFeatureByDevice} 每 5 分钟拉特征包、{@code deviceInfo/getFaceModelConfig}、
 * {@code deviceApp/updateAppVersions} OTA）。对读取类回 {@code status:200,data:null} 等于告诉
 * 设备「配置就是空」，设备按空配置继续跑，缺口被掩盖成一次成功；404 让设备走自己的失败分支，
 * 我方同时留下一条可 grep 的 {@code JIANGYI_PATH_UNIMPLEMENTED} 台账——真机第一天就靠这份
 * 清单决定补哪条。</p>
 *
 * <p>刻意保持失败的几类（理由见 docs/JIANGYI_GATEWAY_DESIGN.md §5）：OTA（{@code deviceApp/*}）、
 * 特征包（{@code feature/*}）、将邑自有桶（{@code token/getAliyunAkToken}）、补货历史
 * （{@code replenishmentHistory*}），以及一切资金前缀（{@code order/**}、{@code orderProduct/**}、
 * {@code device/pokerOrder/**}）：资金类上报宁可让设备侧看到失败并重试/告警，也不能被我方吞成成功。
 * （2026-10-20 起 {@code domainConfig/*} 已从本清单移出，改由 {@link DomainConfigController} 自钉。）</p>
 */
@RestController
public class DeviceUnknownPathController {

    private static final Logger log = LoggerFactory.getLogger(DeviceUnknownPathController.class);

    @RequestMapping("/jiangyi/api/**")
    public void unhandled(HttpServletRequest request,
                          @RequestBody(required = false) Map<String, Object> body) {
        String uri = request.getRequestURI();
        Object deviceId = request.getAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID);
        log.warn("JIANGYI_PATH_UNIMPLEMENTED uri={} deviceId={} body={}", uri, deviceId, body);
        // 与鉴权失败同一条出口：JiangyiApiExceptionHandler 渲染成 HTTP 404 +
        // envelope {status:404,msg:"not found",data:""}（体内码给设备判成败，HTTP 码给监控判）
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "jiangyi path not implemented: " + uri);
    }
}
