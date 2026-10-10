package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 将邑设备面 deviceInfo 端点（CB-023 二期收尾，**PDF 原件逐页核对修正**）。
 *
 * <p><b>成因注释（为什么会有这个类）</b>：一期/二期曾按 docx 转换稿把
 * downloadModelNotify 当成 WS 上行处理；2026-10-10 重读 V16.0.0 PDF 原件
 * （110 页纯扫描件，与采集文档同源偏差风险）确认：</p>
 * <ul>
 *   <li>§4.2.4 {@code /deviceInfo/getDetail}：设备开机后拿 token 换「设备编码
 *       （identifier/别名）」，WS 路径靠它拼——一期缺此端点，真机将拿不到 identifier
 *       （仅靠 token 响应 data.identifier 自描述是文档没有的宽容，不能赌固件行为）。</li>
 *   <li>§4.2.5 {@code /deviceInfo/downloadModelNotify}：模型回执是<b>设备 HTTP POST
 *       商户服务器</b>（Authorization 头带 token，参数 identifier+modelName），
 *       <b>不是 WS 上行</b>——docx 稿误导致二期回执走了 WS。WS 分支保留为宽容兜底
 *       （DeviceWebSocketHandler），主通道改为本 HTTP 端点。</li>
 *   <li>§4.2.13 {@code /deviceInfo/getArtificialCheckByIdentifier}：设备查询视频上传
 *       模式；未实现会 404 噪音，按默认行为 stub data=false（只上传异常视频）。</li>
 * </ul>
 *
 * <p>鉴权：与 DeviceReportController 同走 DeviceAuthInterceptor（token 端点豁免外
 * 全部 /jiangyi/api/**），deviceId 以 token 解出为准，body.identifier 仅交叉校验
 * （铁律：固件不可信）。响应一律将邑 envelope {@code {status:200,msg:"success",data:"0"}}。</p>
 */
@RestController
public class DeviceInfoController {

    private static final Logger log = LoggerFactory.getLogger(DeviceInfoController.class);

    private final TradeInternalClient tradeInternalClient;

    public DeviceInfoController(TradeInternalClient tradeInternalClient) {
        this.tradeInternalClient = tradeInternalClient;
    }

    /** V16 §4.2.4：设备用 token 换设备编码（identifier），WS 连接路径依此拼。 */
    @PostMapping("/jiangyi/api/deviceInfo/getDetail")
    public Map<String, Object> getDetail(HttpServletRequest request) {
        String identifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        log.info("jiangyi deviceInfo getDetail identifier={}", identifier);
        return Map.of("status", 200, "msg", "success",
                "data", Map.of("identifier", identifier));
    }

    /**
     * V16 §4.2.5：模型下发成功回执（主通道；WS 分支为宽容兜底）。
     * 文档注明「下发时没有 modelName，上报时就为空」——空 modelName 只记日志不转发
     * （与 WS 分支语义一致），响应仍 200 避免设备重试风暴。
     */
    @PostMapping("/jiangyi/api/deviceInfo/downloadModelNotify")
    public Map<String, Object> downloadModelNotify(HttpServletRequest request,
                                                   @RequestBody DownloadModelNotifyRequest body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        String tokenIdentifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        if (body != null && body.identifier() != null && !body.identifier().isBlank()
                && !body.identifier().equals(tokenIdentifier)) {
            log.warn("jiangyi downloadModelNotify identifier mismatch token={} body={}",
                    tokenIdentifier, body.identifier());
        }
        String modelName = body == null ? null : body.modelName();
        if (modelName == null || modelName.isBlank()) {
            log.info("jiangyi downloadModelNotify without modelName deviceId={}（§4.3.2.9 允许为空）",
                    deviceId);
            return ok();
        }
        try {
            tradeInternalClient.modelConfirmed(deviceId, modelName);
            log.info("jiangyi http model confirmed deviceId={} modelName={}", deviceId, modelName);
        } catch (Exception e) {
            log.warn("jiangyi http model-confirmed report failed deviceId={}: {}", deviceId, e.getMessage());
        }
        return ok();
    }

    /** V16 §4.2.13：视频上传模式查询。默认 data=false（只上传异常视频）——是否全量上传属商户配置，暂不开放。 */
    @PostMapping("/jiangyi/api/deviceInfo/getArtificialCheckByIdentifier")
    public Map<String, Object> getArtificialCheck(HttpServletRequest request) {
        log.info("jiangyi getArtificialCheck identifier={} → false（默认只传异常视频）",
                attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER));
        return Map.of("status", 200, "msg", "success", "data", false);
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }

    private static Map<String, Object> ok() {
        return Map.of("status", 200, "msg", "success", "data", "0");
    }

    /** V16 §4.2.5 请求体：{identifier, modelName}（identifier=§4.2.4 返回的设备编码）。 */
    public record DownloadModelNotifyRequest(String identifier, String modelName) {}
}
