package com.aicabinet.jiangyi.api;

import com.aicabinet.jiangyi.auth.DeviceAuthInterceptor;
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
 * 将邑真机独有端点的兼容应答面（V15/V16 文档未载，2026-10-19 由 CQYB10720 logcat 实测补齐）。
 *
 * <p><b>成因注释</b>：真机固件开机/运行期实际打 43 条 HTTP 路径，文档第四章只写 17 条。
 * 域名切到我方后，这 43 条全部打到本网关——未注册路径会 404，其中若干条是设备
 * <b>读取</b>配置（不是上报），返回空值等于给设备一份空配置。故凡云侧真实响应有实测样本的，
 * 一律<b>照抄实测值</b>，不发明语义；无实测样本的走 {@link DeviceUnknownPathController} 显式失败。</p>
 *
 * <p><b>证据口径</b>：每条注释里的「实测×N」= .tmp/log3.txt 中该路径响应行的样本数
 * （logcat 单行截断，样本数为可完整解析出 url 尾的行数）。「同族推定」= 该路径无完整实测样本，
 * 按同一固件族（上报类一律 {@code data:null}）取值，只影响设备侧日志、不影响行为。</p>
 *
 * <p>鉴权同设备面其余端点（DeviceAuthInterceptor）；deviceId 以 token 为准，body 只审计。</p>
 */
@RestController
public class DeviceCompatController {

    private static final Logger log = LoggerFactory.getLogger(DeviceCompatController.class);

    // ---------- 设备读取类：照抄云侧实测值 ----------

    /**
     * 是否需要二次甄别复核（log3 实测×2：POST /tenantOrder/needCheck，req {@code {}}，
     * resp {@code data:false}）。云侧对这台设备恒回 false；我方模式一的复核走
     * 自有争议结算链（SettlementConfirmDisputeService），不经将邑云，故照抄 false。
     * 与已实现的 §4.2.13 getArtificialCheckByIdentifier（实测同样 false）同族。
     */
    @PostMapping("/jiangyi/api/tenantOrder/needCheck")
    public Map<String, Object> needCheck(HttpServletRequest request) {
        log.info("jiangyi tenantOrder/needCheck deviceId={} → false（照抄云侧实测）", device(request));
        return data(false);
    }

    /**
     * 商品识别映射表拉取（log3 调用 15 次：req {@code {}}）。
     *
     * <p><b>响应形态证据不足，取保守值</b>：能确定属到本路径的实测样本只有
     * {@code data:{}}×2；另有同一日志 tag「获取商户特殊商品ID」的 {@code data:{"0":"",…,"14":""}}
     * ×11（2026-10-19 复核），但该 tag 与 URL 不在同一行、logcat 里并发线程交错，
     * <b>无法证明它属本路径还是 {@code floor/getDeviceSellProductClassIds}</b>。两种形态语义都是
     * 「没有特殊商品」⇒ 回 {@code {}}（不回 null：读取类里 null 与 {} 在固件解析上不等价）。
     * 真机连入第一天若发现固件按 15 个槽位取值，改回 15 键空串即可，无其他耦合。</p>
     */
    @PostMapping("/jiangyi/api/product/tenantPidAndClassId")
    public Map<String, Object> tenantPidAndClassId(HttpServletRequest request) {
        log.info("jiangyi product/tenantPidAndClassId deviceId={} → 空对象（照抄云侧实测）", device(request));
        return data(Map.of());
    }

    /** OTG 状态查询（log3 实测×13：resp {@code data:"open"}）——照抄，设备据此走 U 盘/OTG 分支。 */
    @PostMapping("/jiangyi/api/deviceInfo/getOtgStatus")
    public Map<String, Object> getOtgStatus(HttpServletRequest request) {
        log.info("jiangyi deviceInfo/getOtgStatus deviceId={} → \"open\"（照抄云侧实测）", device(request));
        return data("open");
    }

    /** 传图开关（log3 实测×2：resp {@code data:"on"}；固件标签 GetIsCanUploadJpgTool）——照抄。 */
    @PostMapping("/jiangyi/api/dictionary/getPicSwitch")
    public Map<String, Object> getPicSwitch(HttpServletRequest request) {
        log.info("jiangyi dictionary/getPicSwitch deviceId={} → \"on\"（照抄云侧实测）", device(request));
        return data("on");
    }

    /**
     * 租户基础运行配置（log3 实测×27 全量对象；下组值 = 10-19 18:33~18:39 连续 3 次开机众数，
     * dictValue NORMAL）。字段语义属将邑固件内部约定，我方无权威口径 ⇒ <b>整组照抄云侧当前值</b>，
     * 不改任何开关：改动任何一项都等于替厂商做设备行为决策。
     * 待办（汇报文件问题 10）：这 15 个字段各自语义 + 哪些需要我方接管、哪些可永久沿用缺省。
     */
    @PostMapping("/jiangyi/api/tenant/getDeviceBasicConfig")
    public Map<String, Object> getDeviceBasicConfig(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("dictValue", "NORMAL");
        cfg.put("lightAlwaysOn", 0);
        cfg.put("videoMappingMode", 0);
        cfg.put("imageUploadMode", 0);
        cfg.put("isDeleteSourceImage", 1);
        cfg.put("isGetSdkVedio", 0);
        cfg.put("isUseTts", 1);
        cfg.put("dataAugmentationMode", 0);
        cfg.put("moudleRunMode", 0);
        cfg.put("replenishmentVideoMode", 0);
        cfg.put("isShowAllLog", 0);
        cfg.put("isPlayVoice", 0);
        cfg.put("debugStatus", 0);
        cfg.put("ttsModelUrl", "https://jiangyi-logs.oss-cn-shanghai.aliyuncs.com/tts/tts-xiao_ya-v2.zip");
        cfg.put("ttsModelVersion", "xiao_ya-v2");
        log.info("jiangyi tenant/getDeviceBasicConfig deviceId={} asked={} → 云侧实测缺省组",
                device(request), body);
        return data(cfg);
    }

    /**
     * 重审商品黑名单（真机每次开门调一次：POST {@code /tenant/getOrderRecheckProduct}，
     * body {@code {"identifier":"CQYB10720"}}（{@code OkhttpUtils.postRequest} 把 HashMap
     * Gson 序列化后以 {@code application/json} 发出，smali 亲验）；V15/V16 未载，
     * 2026-10-21 我方日志实测 6 次全 404）。
     *
     * <p><b>契约取自固件而非文档</b>：{@code ErrorGoodsEntity}（smali 亲验）=
     * {@code {status:int, msg:String, data:[Ljava/lang/String;}}，回调把 {@code data} 转成
     * {@code Constans.ERROR_GOODS}，唯一消费点是各 Tracker 的
     * {@code ERROR_GOODS.contains(labelTitle)} → {@code isErrorGoods}。</p>
     *
     * <p><b>回 {@code 200 + []} 与现状 404 对设备行为等价</b>：两个调用点（BaseActivity$11 /
     * CameraPreviewNew$17）的 {@code onFailed} 都是空实现，{@code onSuccess} 对 entity 与 data
     * 各有 {@code if-eqz} 保护 ⇒ 404 时 ERROR_GOODS 维持 null、含空表时为空表，两者都不产生
     * 「重审商品」标记；差别只在设备日志里少一条失败记录。<b>不可回 {@code data:""}</b>：
     * 固件按 String 数组反序列化，字符串会抛 Expected BEGIN_ARRAY（被 try/catch printStackTrace
     * 吞掉，正是本轮真机日志噪音来源）。</p>
     *
     * <p>与 {@code docs/JIANGYI_GATEWAY_DESIGN.md} §「未实测一律 404」不冲突：那条针对的是
     * <b>配置读取类</b>（200 空值会伪装成「配置就是空」）；本端点是无条件的商品黑名单查询，
     * 空表是我方真实答案（我方没有任何要求重审的商品）。</p>
     */
    @PostMapping("/jiangyi/api/tenant/getOrderRecheckProduct")
    public Map<String, Object> getOrderRecheckProduct(HttpServletRequest request,
                                                      @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi tenant/getOrderRecheckProduct deviceId={} asked={} → 空黑名单",
                device(request), body);
        return data(List.of());
    }

    /**
     * 调光（摄像头曝光）配置表（log3：{@code E/getLight} 标签，data = 25 行 cameraType/pvalue 区间
     * /pt1-5/pb1-5 查找表，另有 8 次实测 {@code data:null}）。
     * 取 null：云侧自己对同一台设备回过 null 且设备照常完成开机与购物流程 ⇒ null 有实证容忍度；
     * 且该表属厂商租户级标定数据，不应复制进我方代码库。
     * 待办（汇报文件问题 11）：切我方域名后调光表由谁维护、是否仍需云侧下发。
     */
    @PostMapping("/jiangyi/api/deviceInfo/getTenantPvalueConfig")
    public Map<String, Object> getTenantPvalueConfig(HttpServletRequest request) {
        log.info("jiangyi deviceInfo/getTenantPvalueConfig deviceId={} → null（云侧实测含 null 样本）",
                device(request));
        return data(null);
    }

    /** 关门时段调光表（log3：{@code E/getLightClose}，同族，实测含 6 次 null）——同上取 null。 */
    @PostMapping("/jiangyi/api/deviceInfo/getTenantPvalueConfigClose")
    public Map<String, Object> getTenantPvalueConfigClose(HttpServletRequest request) {
        log.info("jiangyi deviceInfo/getTenantPvalueConfigClose deviceId={} → null（云侧实测含 null 样本）",
                device(request));
        return data(null);
    }

    /**
     * 百度语音合成离线 SDK 激活码（log3 实测×15：req {@code {"deviceSn":"YPKJ1JYKJ00000000000250"}}，
     * resp {@code status:666, msg:"当前设备未绑定百度语音合成离线SDK激活序列号<sn>", data:null}）。
     * 666 是将邑协议的业务失败码，固件收到即跳过 TTS 激活并继续开机（实测无异常）。
     * 我方没有该 SDK 的授权序列号 ⇒ 照抄云侧对这台设备的真实答复。
     */
    @PostMapping("/jiangyi/api/deviceInfo/getBaiduAudioCode")
    public Map<String, Object> getBaiduAudioCode(HttpServletRequest request,
                                                 @RequestBody(required = false) Map<String, Object> body) {
        String deviceSn = body == null || body.get("deviceSn") == null ? "" : body.get("deviceSn").toString();
        log.info("jiangyi deviceInfo/getBaiduAudioCode deviceId={} sn={} → 666（无百度离线 TTS 授权）",
                device(request), deviceSn);
        return Map.of("status", 666,
                "msg", "当前设备未绑定百度语音合成离线SDK激活序列号" + deviceSn,
                "data", "");
    }

    // ---------- 设备上报类：接收 + 审计，响应照抄上报族实测 ----------

    /**
     * 摄像头当前光值上报（log3 实测：req {@code {"pvalue":"i：2,c:3,","pvalueB":"i：33,c:5,"}}，
     * resp {@code data:null}）。调光闭环的观测值，格式是厂商私有串（全角冒号），
     * 我方不解析、只留原始日志——识别质量分析需要落库时再定 schema（同拓扑字段处置口径）。
     */
    @PostMapping("/jiangyi/api/deviceInfo/getPValue")
    public Map<String, Object> reportPValue(HttpServletRequest request,
                                            @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/getPValue deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** 订单维度光值（log3 实测×4：req 同上 + {@code "transactionId":""}，resp {@code data:null}）。 */
    @PostMapping("/jiangyi/api/order/pValueByTransactionId")
    public Map<String, Object> reportPValueByTransaction(HttpServletRequest request,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi order/pValueByTransactionId deviceId={} body={}", device(request), body);
        return data(null);
    }

    /**
     * 补货关门状态（log3 实测×2：req {@code {"deviceNum":"CQYB10720","addGoodsId":"",
     * "lockStatus":"success"}}；响应样本被 logcat 截断，未取到）。
     * 语义＝补货会话的门锁回执，{@code addGoodsId} 为空串（与 §4.2.7 uploadOrderLockStatus
     * 的空 orderNo 同判：无会话可推进，只审计）。响应按上报族实测取 {@code data:null}。
     * 待办（汇报文件问题 12）：我方补货流程是否要消费 addGoodsId。
     */
    @PostMapping("/jiangyi/api/order/uploadAddGoodsCloseStatus")
    public Map<String, Object> uploadAddGoodsCloseStatus(HttpServletRequest request,
                                                          @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi order/uploadAddGoodsCloseStatus deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** 自检结果上报（log3：req {@code {"faultContent":"","pass":true}}；上报族推定 data:null）。 */
    @PostMapping("/jiangyi/api/deviceInfo/selfCheckReport")
    public Map<String, Object> selfCheckReport(HttpServletRequest request,
                                                @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/selfCheckReport deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** 设备状态上报（log3：req {@code {"deviceStatusMsg":"","deviceStatus":0}}；上报族推定）。 */
    @PostMapping("/jiangyi/api/deviceInfo/reportDeviceStatus")
    public Map<String, Object> reportDeviceStatus(HttpServletRequest request,
                                                   @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/reportDeviceStatus deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** 摄像头实装信息上报（log3：req {@code {"cameras":[{fw,serial,usb,port,name}...]}}；上报族推定）。 */
    @PostMapping("/jiangyi/api/deviceInfo/reportCameraRealInfo")
    public Map<String, Object> reportCameraRealInfo(HttpServletRequest request,
                                                     @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/reportCameraRealInfo deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** 故障状态上报（log3：req {@code {"troubleSource":"selfcheck","troubleStatus":"no",
     * "troubleReason":"自检通过"}}；上报族推定）。 */
    @PostMapping("/jiangyi/api/deviceInfo/updateTrouble")
    public Map<String, Object> updateTrouble(HttpServletRequest request,
                                              @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/updateTrouble deviceId={} body={}", device(request), body);
        return data(null);
    }

    /** SIM 卡号上报（log3 实测×7 且 resp {@code data:null}：req {@code {"cardNo":"898604..."}}）。 */
    @PostMapping("/jiangyi/api/deviceInfo/uploadDeviceDataCardNo")
    public Map<String, Object> uploadDeviceDataCardNo(HttpServletRequest request,
                                                       @RequestBody(required = false) Map<String, Object> body) {
        log.info("jiangyi deviceInfo/uploadDeviceDataCardNo deviceId={} body={}", device(request), body);
        return data(null);
    }

    // ---------- 内部 ----------

    private static Map<String, Object> data(Object value) {
        // LinkedHashMap：data 允许为 null（Map.of 不接受 null 值），字段序保持与云侧一致
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("status", 200);
        resp.put("msg", "success");
        resp.put("data", value);
        return resp;
    }

    private static String device(HttpServletRequest request) {
        Object attr = request.getAttribute(DeviceAuthInterceptor.ATTR_DEVICE_ID);
        return attr == null ? "unknown" : attr.toString();
    }
}
