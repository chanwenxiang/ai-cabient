package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
import com.aicabinet.jiangyi.client.TradeInternalClient;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
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

    /**
     * V16 §4.2.4：设备用 token 换设备信息，WS 连接路径依 identifier 拼。
     *
     * <p><b>成因注释（2026-10-19 log3 修订，第三轮审计 P0-1）</b>：云侧真实响应是全量
     * 硬件拓扑 {@code {id, identifier, isBind, recognitionType, doorNum, locks[],
     * usbNumLu/Lb/Ru/Rb, modelNum, huodaoNum, industrialControlType, mqtt*}}，V15/V16 文档
     * 示例都只写 {@code {identifier}}（同源偏差）。</p>
     *
     * <p>🔴 <b>这组值不是展示字段，固件拿它当门锁/摄像头驱动用</b>（log2/log3 实证）：
     * 云侧回 {@code doorNum:2, locks:[1,2]} 的那次开机（09-28 15:34:05.130），25ms 内固件即
     * 走 {@code LockMannager.closeDoubleDoor → LockSys.writeSys} 去写
     * {@code /sys/class/.../gpio4}（本机无此节点，ENOENT）；其余约 100 次 {@code doorNum:1}
     * 的开机无一次锁 GPIO 异常。紧接着还打印 {@code Constans.usbNumRu=…/usbNumRb=…}
     * 做摄像头 USB 总线分配。</p>
     *
     * <p><b>0 与 null 的语义不同，不能混填</b>：云侧对「该硬件通道不存在」回 <b>0</b>
     * （{@code usbNumRu/Rb} 实测 0），只对「未维护的元数据」回 <b>null</b>
     * （{@code huodaoNum}/{@code industrialControlType}/{@code mqtt*}——同一份日志里
     * 云侧回 null 而设备照常开机 100 次，null 容忍度就此成立）。此前把拓扑字段一律回
     * null 属未定义行为：固件可能跳过，也可能按默认值动一条不该动的锁通道。</p>
     *
     * <p><b>{@code usbNumRu/Rb} 在厂商侧被改过，取最新值</b>（log3 全量普查）：09-28 与 10-08
     * 的 18 次回 {@code 5/6}，10-16 起到 10-19 的约 30 次回 {@code 0/0}（{@code doorNum/locks/
     * usbNumLu/usbNumLb/modelNum} 全程未变）⇒ 这是厂商后台可改的每机配置，不是出厂常量；
     * 下面这组按<b>最近一次实测</b>取 0/0，真机接入后须以我方自己收到的首次 getDetail 请求为准复核。</p>
     *
     * <p><b>下面这组是 CQYB10720 的实测出厂档案</b>（log3 最新一次开机 10-19 18:39:28）：
     * 单门一路锁、左上/左下两路摄像头在位、右上/右不存在、2 个模型。它同时也是
     * <b>保守缺省</b>——按「最少执行器」取值，未知设备不会因此多开一条锁。
     * 拓扑在将邑后台是<b>可改的每机配置</b>（本机 09-28 曾回过 doorNum:2/locks:[1,2]），
     * 换柜/换锁/加摄像头后必须同步这里的值；逐台建档（迁移 + 运营台）留待真机连入我方、
     * 拿到第一天日志后再定 schema。</p>
     */
    @PostMapping("/jiangyi/api/deviceInfo/getDetail")
    public Map<String, Object> getDetail(HttpServletRequest request) {
        String identifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        log.info("jiangyi deviceInfo getDetail identifier={} deviceId={}", identifier, deviceId);
        Map<String, Object> data = new LinkedHashMap<>();
        // 云侧 id 回 JSON 数字（10720），我方 deviceId 是字符串形态的雪花 id。固件若按数字读
        // （JSONObject.getInt）拿到 "EG94…" 会抛；纯数字时对齐云侧类型，非数字才回字符串。
        data.put("id", numericIfPossible(deviceId));
        data.put("identifier", identifier);
        data.put("isBind", "yes");
        data.put("recognitionType", "end");
        data.put("doorNum", 1);
        data.put("locks", List.of(1));
        data.put("usbNumLu", 1);
        data.put("usbNumLb", 3);
        data.put("usbNumRu", 0);
        data.put("usbNumRb", 0);
        data.put("modelNum", 2);
        data.put("huodaoNum", null);
        data.put("industrialControlType", null);
        // 云侧响应里还有这 5 个键，实测 100% 为 null（厂商 MQTT 通道未启用）。补齐键集
        // 而不是省略——固件解析器形态未知，「同答」比「少答」安全（顺序也照抄云侧）。
        data.put("mqttClientId", null);
        data.put("mqttTopicPrefix", null);
        data.put("lastOnlineTime", null);
        data.put("mqttStatus", null);
        data.put("lastHeartbeatTime", null);
        data.put("mqttQos", null);
        return Map.of("status", 200, "msg", "success", "data", data);
    }

    /**
     * V16 §4.2.5：模型下发成功回执（主通道；WS 分支为宽容兜底）。
     * 文档注明「下发时没有 modelName，上报时就为空」——空 modelName 只记日志不转发
     * （与 WS 分支语义一致），响应仍 200 避免设备重试风暴。
     *
     * <p>另外收三条文档未列、真机固件确实在打的路径（log4 行 24231 base / 30621 big /
     * 17847 third，请求体同形 {@code {identifier,modelName}}）——对应将邑后台「模型类型」
     * 三档。三档语义未向厂商核实，故<b>不区分处理</b>，一律走同一转发：
     * trade 侧 {@code confirmModel} 按 modelName 匹配 SENT 记录、不匹配回 409 ⇒ 错档回执
     * 只会进 warn 台账，不会伪造 CONFIRMED。
     */
    @PostMapping({"/jiangyi/api/deviceInfo/downloadModelNotify",
            "/jiangyi/api/deviceInfo/downloadBaseModelNotify",
            "/jiangyi/api/deviceInfo/downloadBigModelNotify",
            "/jiangyi/api/deviceInfo/downloadThirdModelNotify"})
    public Map<String, Object> downloadModelNotify(HttpServletRequest request,
                                                   @RequestBody DownloadModelNotifyRequest body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        String tokenIdentifier = attr(request, DeviceAuthInterceptor.ATTR_IDENTIFIER);
        String notifyPath = request.getRequestURI();
        if (body != null && body.identifier() != null && !body.identifier().isBlank()
                && !body.identifier().equals(tokenIdentifier)) {
            log.warn("jiangyi downloadModelNotify identifier mismatch token={} body={}",
                    tokenIdentifier, body.identifier());
        }
        String modelName = body == null ? null : body.modelName();
        if (modelName == null || modelName.isBlank()) {
            log.info("jiangyi {} without modelName deviceId={}（§4.3.2.9 允许为空）",
                    notifyPath, deviceId);
            return ok();
        }
        try {
            tradeInternalClient.modelConfirmed(deviceId, modelName);
            log.info("jiangyi http model confirmed path={} deviceId={} modelName={}",
                    notifyPath, deviceId, modelName);
        } catch (Exception e) {
            log.warn("jiangyi http model-confirmed report failed path={} deviceId={}: {}",
                    notifyPath, deviceId, e.getMessage());
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

    /**
     * 未文档化端点（2026-10-19 真机 CQYB10720 实测：开机序列固定调用，404 会中断
     * 设备启动）。语义据采集文档 §4.4.4 modelTextUrl（模型 classes txt 地址）推断：
     * 设备上报当前使用的模型 txt 文件地址，与 downloadModelNotify 同族回执。
     * 宽容接收任意 body 只记日志；classes_version 仍以 downloadModelNotify 为准。
     *
     * <p><b>响应照抄云侧，不报 success</b>（log3 行 291/640 实测×2 全为
     * {@code {"status":666,"msg":"当前模型text文件地址不存在","data":null}}）：本端点由
     * 云侧**回给设备**的答复决定，我方若回 200+data 会被固件当成「有 txt 地址可取」而
     * 去下载一个不存在的文件。666 是将邑协议的业务失败码，固件收到即跳过（实测无异常）。
     * 我方模型 txt 随 {@code /modelFile/notify} 的 modelUrl 一起下发，走
     * downloadModelNotify 通道，不经本回执。</p>
     */
    @PostMapping("/jiangyi/api/deviceInfo/receiveCurrentTxtUrl")
    public Map<String, Object> receiveCurrentTxtUrl(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        String deviceId = attr(request, DeviceAuthInterceptor.ATTR_DEVICE_ID);
        log.info("jiangyi receiveCurrentTxtUrl deviceId={} body={} → 666（我方不下发 txt 地址）", deviceId, body);
        return envelope(666, "当前模型text文件地址不存在", null);
    }

    private static String attr(HttpServletRequest request, String name) {
        Object v = request.getAttribute(name);
        return v == null ? "unknown" : v.toString();
    }

    /** 纯数字 deviceId 以 JSON 数字下发（对齐云侧 id 的类型）；超 long 范围或非数字回原串。 */
    private static Object numericIfPossible(String raw) {
        if (raw == null || raw.isEmpty() || !raw.chars().allMatch(Character::isDigit)) {
            return raw;
        }
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return raw;
        }
    }

    private static Map<String, Object> ok() {
        return Map.of("status", 200, "msg", "success", "data", "0");
    }

    /** 云侧协议允许 {@code data:null}（{@code Map.of} 会 NPE），失败回执走此构造。 */
    private static Map<String, Object> envelope(int status, String msg, Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("msg", msg);
        body.put("data", data);
        return body;
    }

    /** V16 §4.2.5 请求体：{identifier, modelName}（identifier=§4.2.4 返回的设备编码）。 */
    public record DownloadModelNotifyRequest(String identifier, String modelName) {}
}
