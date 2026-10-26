package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.dto.JiangyiGatherDtos.GatherEntryRequest;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 将邑采集台（§4.3.3–4.3.14 拍摄编排的运营后台一条龙，CB-023）。
 *
 * <p>链路每步都在 2026-10-26 真机自采验证过：开门 §4.2.3 → 等 doorStatus=open §4.2.4 →
 * 建条目 §4.3.3 → 轮询就绪 §4.3.4 → confirmStart §4.3.5 → 拍摄窗口（轮询 gatherError §4.3.6）
 * → endOne §4.3.7 → gatherProducts §4.3.8 / goOnGather §4.3.9 / endAll §4.3.10 →
 * 轮询 uploadInfoNew §4.3.11。替代此前只能临时脚本驱动的状态（台账 G-5）。</p>
 *
 * <p>🔴 <b>内存态会话</b>：运行状态只在本进程（一次现场采集本就该当场了结），重启即清；
 * 留痕靠日志，不建表。</p>
 *
 * <p>🔴 <b>绝不自动调 deleteBatchNoNew</b>：2026-10-26 16:51 实测在 uploadInfoNew 还有 wait 时
 * 清批次会把后续上传打成孤儿（靠 §4.3.13 goOnUploadNew 才续回来）。清批次只做手动动作，
 * 且服务端先查确认无 wait 才放行（§4.3.14 文档原文约束）。</p>
 *
 * <p>采集锁：{@link #start} 即 markGatherLocked（与 {@link JiangyiGatherService#startGather}
 * 同一把营业闸，柜机停止售货）；会话终态（完成/失败/中止）finally 解锁。救援动作在终态后
 * 手动触发，不持锁。</p>
 */
@Service
public class JiangyiGatherConsoleService {

    private static final Logger log = LoggerFactory.getLogger(JiangyiGatherConsoleService.class);

    static final int DOOR_TIMEOUT_S = 60;
    static final int READY_TIMEOUT_S = 45;
    static final int UPLOAD_TIMEOUT_S = 240;
    static final long POLL_MS = 1500;
    static final long SHOOT_POLL_MS = 5000;

    public static final String PHASE_WAIT_DOOR = "WAIT_DOOR";
    public static final String PHASE_PREPARING = "PREPARING";
    public static final String PHASE_SHOOTING = "SHOOTING";
    public static final String PHASE_WRAPPING = "WRAPPING";
    public static final String PHASE_UPLOADING = "UPLOADING";
    public static final String PHASE_DONE = "DONE";
    public static final String PHASE_FAILED = "FAILED";
    public static final String PHASE_ABORTED = "ABORTED";

    private final JiangyiGatherClient client;
    private final JiangyiDeviceMapper deviceMapper;
    private final String merchantMobile;
    private final Map<String, ConsoleRun> runs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "jiangyi-gather-console");
        t.setDaemon(true);
        return t;
    });

    public JiangyiGatherConsoleService(JiangyiGatherClient client,
                                       JiangyiDeviceMapper deviceMapper,
                                       @Value("${JIANGYI_MERCHANT_MOBILE:}") String merchantMobile) {
        this.client = client;
        this.deviceMapper = deviceMapper;
        this.merchantMobile = merchantMobile;
    }

    /** 发起一条龙采集会话（同柜已有活动会话 / 柜已在采集模式 → 409）。identifier 一律以服务端查库为准。 */
    public ConsoleSnapshot start(String deviceId, GatherEntryRequest entry, Integer shootSeconds) {
        JiangyiDevice device = deviceMapper.selectById(deviceId);
        if (device == null || !"BOUND".equals(device.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "将邑设备未绑定：" + deviceId);
        }
        if (device.getIdentifier() == null || device.getIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备缺少将邑设备编码（identifier）");
        }
        if (entry == null || entry.productId() <= 0 || entry.name() == null || entry.name().isBlank()
                || entry.category() == null || entry.category().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "采集条目不完整：productId/name/category 必填（从待采集列表选择会自动带出）");
        }
        GatherEntryRequest verified = new GatherEntryRequest(device.getIdentifier(), entry.productId(),
                entry.name().trim(), entry.brand(), entry.specs(), entry.category().trim(), entry.barCodeSource());
        ConsoleRun existing = runs.get(deviceId);
        if (existing != null && !existing.terminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该柜已有采集会话在跑（阶段 "
                    + existing.phase + "），先中止再发起");
        }
        if (device.getGatherLockedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备已在采集模式中（商户 App 采集或学习回调未回）");
        }
        int seconds = clampShootSeconds(shootSeconds);
        ConsoleRun run = new ConsoleRun(deviceId, device.getIdentifier(), verified, seconds,
                doorTimeoutS(), readyTimeoutS(), uploadTimeoutS(), pollMs(), shootPollMs());
        runs.put(deviceId, run);
        deviceMapper.markGatherLocked(deviceId, Instant.now());
        run.log("INFO", "会话启动：拍摄窗口 " + seconds + "s，商品 " + verified.productId() + " " + verified.name());
        executor.execute(run);
        return run.snapshot();
    }

    /** 当前会话快照（无会话回 null，前端按空态渲染）。 */
    public ConsoleSnapshot state(String deviceId) {
        ConsoleRun run = runs.get(deviceId);
        return run == null ? null : run.snapshot();
    }

    /** §4.3.13 继续上传：批次标记被误清而条目仍 wait 时的补救；终态会话重新进入上传轮询。 */
    public ConsoleSnapshot resumeUpload(String deviceId) {
        ConsoleRun run = requireRun(deviceId);
        if (!run.terminal() && !PHASE_UPLOADING.equals(run.phase)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话还在跑（阶段 " + run.phase + "），不能继续上传");
        }
        run.log("INFO", "手动：goOnUploadNew 继续上传（§4.3.13）");
        client.goOnUploadNew(run.identifier);
        run.beginResume();
        executor.execute(run::runUploadPoll);
        return run.snapshot();
    }

    /** §4.3.12 放弃上传：整批丢弃，不可逆。 */
    public ConsoleSnapshot forgoUpload(String deviceId) {
        ConsoleRun run = requireRun(deviceId);
        if (!run.terminal() && !PHASE_UPLOADING.equals(run.phase)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话还在跑（阶段 " + run.phase + "），先中止再放弃");
        }
        run.log("WARN", "手动：forgoUploadNew 放弃上传（§4.3.12，不可逆）");
        client.forgoUploadNew(run.identifier);
        run.refreshUpload();
        return run.snapshot();
    }

    /** §4.3.14 清批次标记：服务端先查无 wait 才放行（文档原文约束）。 */
    public ConsoleSnapshot clearBatch(String deviceId) {
        ConsoleRun run = requireRun(deviceId);
        JsonNode rows = client.uploadInfoNew(run.identifier);
        String waiting = firstWaiting(rows);
        if (waiting != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "仍有 wait 条目（" + waiting + "）：先「继续上传」或「放弃上传」，再清批次（§4.3.14）");
        }
        run.log("INFO", "手动：deleteBatchNoNew 清批次标记（§4.3.14，已确认无 wait）");
        client.deleteBatchNoNew(run.identifier);
        run.refreshUpload();
        return run.snapshot();
    }

    /** 中止会话：链路在步骤间隙退出并尽力收尾（endOne/endAll），厂商侧不留悬挂批次。 */
    public ConsoleSnapshot abort(String deviceId) {
        ConsoleRun run = requireRun(deviceId);
        if (run.terminal()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "会话已结束（" + run.phase + "）");
        }
        run.requestAbort();
        run.log("WARN", "手动：请求中止，等链路在下一步退出");
        return run.snapshot();
    }

    private ConsoleRun requireRun(String deviceId) {
        ConsoleRun run = runs.get(deviceId);
        if (run == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "该柜没有采集会话记录（重启后内存清空）");
        }
        return run;
    }

    /** 链路节奏钩子（package-private）：单测覆写成毫秒级，生产用常量。 */
    int clampShootSeconds(Integer shootSeconds) {
        return shootSeconds == null ? 45 : Math.max(20, Math.min(120, shootSeconds));
    }

    int doorTimeoutS() { return DOOR_TIMEOUT_S; }

    int readyTimeoutS() { return READY_TIMEOUT_S; }

    int uploadTimeoutS() { return UPLOAD_TIMEOUT_S; }

    long pollMs() { return POLL_MS; }

    long shootPollMs() { return SHOOT_POLL_MS; }

    private static String firstWaiting(JsonNode rows) {
        if (rows == null || !rows.isArray()) {
            return null;
        }
        for (JsonNode n : rows) {
            if ("wait".equalsIgnoreCase(n.path("status").asText())) {
                return "#" + n.path("id").asText() + " " + n.path("productInfo").asText("");
            }
        }
        return null;
    }

    /** 单次采集会话：一次 Runnable 跑完整条链；timings 全部构造注入（单测缩到秒级）。 */
    final class ConsoleRun implements Runnable {

        private final String runId = UUID.randomUUID().toString();
        private final String deviceId;
        private final String identifier;
        private final GatherEntryRequest entry;
        private final int shootSeconds;
        private final int doorTimeoutS;
        private final int readyTimeoutS;
        private final int uploadTimeoutS;
        private final long pollMs;
        private final long shootPollMs;

        private final List<LogLine> logs = Collections.synchronizedList(new ArrayList<>());
        private volatile String phase = PHASE_WAIT_DOOR;
        private volatile boolean abortRequested;
        private volatile long entryId;
        private volatile boolean wrapped;
        private volatile Instant startedAt = Instant.now();
        private volatile Instant finishedAt;
        private volatile String failure;
        private volatile List<UploadRow> upload = List.of();

        ConsoleRun(String deviceId, String identifier, GatherEntryRequest entry, int shootSeconds,
                   int doorTimeoutS, int readyTimeoutS, int uploadTimeoutS, long pollMs, long shootPollMs) {
            this.deviceId = deviceId;
            this.identifier = identifier;
            this.entry = entry;
            this.shootSeconds = shootSeconds;
            this.doorTimeoutS = doorTimeoutS;
            this.readyTimeoutS = readyTimeoutS;
            this.uploadTimeoutS = uploadTimeoutS;
            this.pollMs = pollMs;
            this.shootPollMs = shootPollMs;
        }

        @Override
        public void run() {
            try {
                runChain();
            } catch (AbortedException e) {
                phase = PHASE_ABORTED;
                log("WARN", "会话已中止");
            } catch (Exception e) {
                phase = PHASE_FAILED;
                failure = rootMessage(e);
                log("ERROR", "失败：" + failure);
            } finally {
                try {
                    deviceMapper.markGatherUnlocked(deviceId, Instant.now());
                    log("INFO", "已退出采集模式，恢复营业");
                } catch (Exception unlockError) {
                    log("ERROR", "解除采集锁失败（可在设备卡手动退出采集模式）：" + rootMessage(unlockError));
                }
                finishedAt = Instant.now();
                log.info("jiangyi gather console finished deviceId={} phase={} entry={}",
                        deviceId, phase, entry.productId());
            }
        }

        private void runChain() throws AbortedException {
            log("INFO", "① 下发采集开门（§4.2.3），请到柜机前把门拉开");
            client.gatherOpenDoor(identifier, "");
            waitDoor();

            phase = PHASE_PREPARING;
            checkAbort();
            log("INFO", "② 门已开，建条目（§4.3.3 productInfo）——把商品拿在手上准备");
            entryId = client.gatherProductInfo(entry);
            log("INFO", "   条目 id=" + entryId);
            waitReady();

            checkAbort();
            phase = PHASE_SHOOTING;
            log("INFO", "③ confirmStart 开始采集（§4.3.5）——把商品摆到摄像头前，慢扫 " + shootSeconds + "s");
            client.gatherConfirmStart(identifier, entryId);
            shootWindow();

            checkAbort();
            phase = PHASE_WRAPPING;
            log("INFO", "④ 拍摄结束，可以关门了；收尾 endOne/gatherProducts/goOnGather/endAll");
            client.gatherEndOne(identifier, entryId);
            JsonNode products = client.gatherProducts(identifier);
            log("INFO", "   批内条目：" + (products == null || !products.isArray() ? "?" : products.size()) + " 条");
            JsonNode goOn = client.gatherGoOn(identifier);
            log("INFO", "   goOnGather=" + (goOn == null ? "?" : goOn.toString()));
            client.gatherEndAll(identifier, merchantMobile);
            wrapped = true;
            log("INFO", "   endAll 已发（回执短信发商户手机号）");

            phase = PHASE_UPLOADING;
            log("INFO", "⑤ 轮询上传（§4.3.11 uploadInfoNew），最多 " + uploadTimeoutS + "s");
            runUploadPoll();
            if (!PHASE_DONE.equals(phase)) {
                log("WARN", "上传未到终态：不要清批次，用「继续上传」（§4.3.13）或稍后重查");
            }
        }

        /** 门等待：60s（可配）内 doorStatus=data=open 才继续。 */
        private void waitDoor() throws AbortedException {
            long deadline = System.currentTimeMillis() + doorTimeoutS * 1000L;
            while (System.currentTimeMillis() < deadline) {
                checkAbort();
                JsonNode st = client.doorStatus(identifier);
                if (st != null && "open".equalsIgnoreCase(st.asText())) {
                    return;
                }
                sleep(pollMs);
            }
            throw new IllegalStateException(doorTimeoutS + "s 内门未打开（doorStatus 未 open），会话终止");
        }

        /** 就绪等待：cameraStatus/configParamStatus/start 全 yes；厂商判 FAILED/超时即终止。 */
        private void waitReady() throws AbortedException {
            long deadline = System.currentTimeMillis() + readyTimeoutS * 1000L;
            while (System.currentTimeMillis() < deadline) {
                checkAbort();
                JsonNode d = client.gatherStartCheck(identifier, entryId);
                String camera = d.path("cameraStatus").asText("");
                String config = d.path("configParamStatus").asText("");
                String start = d.path("start").asText("");
                if ("yes".equalsIgnoreCase(camera) && "yes".equalsIgnoreCase(config) && "yes".equalsIgnoreCase(start)) {
                    log("INFO", "   摄像头/配置就绪（§4.3.4）");
                    return;
                }
                String err = d.path("errorMessage").asText("");
                if ("FAILED".equalsIgnoreCase(d.path("prepareStatus").asText())) {
                    throw new IllegalStateException("厂商侧采集准备失败：" + err);
                }
                if (err.contains("超时") || err.contains("结束")) {
                    throw new IllegalStateException("厂商侧采集流程已结束：" + err);
                }
                sleep(pollMs);
            }
            throw new IllegalStateException(readyTimeoutS + "s 未就绪（文档：20s 未就绪即结束采集流程）");
        }

        /** 拍摄窗口：每 shootPollMs 查 gatherError，yes=设备侧判错，提前收尾（still ends normally）。 */
        private void shootWindow() throws AbortedException {
            long deadline = System.currentTimeMillis() + shootSeconds * 1000L;
            while (System.currentTimeMillis() < deadline) {
                checkAbort();
                sleep(shootPollMs);
                String err = client.gatherError(entryId);
                if ("yes".equalsIgnoreCase(err)) {
                    log("WARN", "   gatherError=yes：设备侧已判错，提前收尾");
                    return;
                }
            }
            log("INFO", "   拍摄窗口结束");
        }

        /** 上传轮询：到无 wait 为止；超时停在 UPLOADING，等手动救援。绝不自动清批次。 */
        void runUploadPoll() {
            phase = PHASE_UPLOADING;
            long deadline = System.currentTimeMillis() + uploadTimeoutS * 1000L;
            try {
                while (System.currentTimeMillis() < deadline) {
                    checkAbort();
                    refreshUpload();
                    List<UploadRow> rows = upload;
                    long waiting = rows.stream().filter(r -> "wait".equalsIgnoreCase(r.status())).count();
                    log("INFO", "   上传中：共 " + rows.size() + " 条，wait " + waiting + " 条");
                    if (!rows.isEmpty() && waiting == 0) {
                        phase = PHASE_DONE;
                        log("INFO", "✅ 上传终态齐了，会话完成");
                        return;
                    }
                    sleep(pollMs);
                }
                log("ERROR", "上传轮询超时（" + uploadTimeoutS + "s），停在 UPLOADING 等手动处理");
            } catch (AbortedException e) {
                phase = PHASE_ABORTED;
                log("WARN", "上传轮询被中止");
            } catch (Exception e) {
                phase = PHASE_FAILED;
                failure = rootMessage(e);
                log("ERROR", "上传轮询失败：" + failure);
            }
        }

        /** 会话被中止时尽力收尾，让厂商侧状态机闭合（endAll 已发过就不重发）。 */
        private void abortCleanup() {
            try {
                if (entryId > 0 && !wrapped) {
                    client.gatherEndOne(identifier, entryId);
                    client.gatherEndAll(identifier, merchantMobile);
                    wrapped = true;
                    log("INFO", "中止收尾：endOne/endAll 已补发");
                }
            } catch (Exception e) {
                log("WARN", "中止收尾失败（可检查 uploadInfoNew）： " + rootMessage(e));
            }
        }

        void checkAbort() throws AbortedException {
            if (abortRequested) {
                abortCleanup();
                throw new AbortedException();
            }
        }

        void requestAbort() {
            abortRequested = true;
        }

        void beginResume() {
            if (terminal()) {
                finishedAt = null;
                failure = null;
            }
        }

        void refreshUpload() {
            JsonNode rows = client.uploadInfoNew(identifier);
            List<UploadRow> out = new ArrayList<>();
            if (rows != null && rows.isArray()) {
                for (JsonNode n : rows) {
                    out.add(new UploadRow(n.path("id").asLong(), n.path("productInfo").asText(""),
                            n.path("status").asText(""), n.path("errorMessage").asText(""),
                            n.path("rejectReason").asText(""), n.path("mainImg").asText("")));
                }
            }
            upload = List.copyOf(out);
        }

        void log(String level, String message) {
            logs.add(new LogLine(Instant.now(), level, message));
        }

        boolean terminal() {
            return PHASE_DONE.equals(phase) || PHASE_FAILED.equals(phase) || PHASE_ABORTED.equals(phase);
        }

        ConsoleSnapshot snapshot() {
            List<LogLine> tail;
            synchronized (logs) {
                int from = Math.max(0, logs.size() - 80);
                tail = List.copyOf(logs.subList(from, logs.size()));
            }
            Map<String, Object> entryView = new LinkedHashMap<>();
            entryView.put("productId", entry.productId());
            entryView.put("name", entry.name());
            entryView.put("brand", entry.brand());
            entryView.put("specs", entry.specs());
            entryView.put("category", entry.category());
            return new ConsoleSnapshot(runId, deviceId, identifier, phase, shootSeconds,
                    abortRequested, startedAt, finishedAt, failure, entryView, tail, upload);
        }

        private static void sleep(long ms) {
            try {
                Thread.sleep(ms);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("会话线程被中断");
            }
        }

        private static String rootMessage(Throwable e) {
            Throwable cur = e;
            while (cur.getCause() != null && cur.getCause() != cur) {
                cur = cur.getCause();
            }
            return cur.getMessage() == null ? cur.getClass().getSimpleName() : cur.getMessage();
        }
    }

    private static final class AbortedException extends Exception {
    }

    /** 会话快照（openapi inline schema；entry 用 Map 避免再开一层 record）。 */
    public record ConsoleSnapshot(String runId, String deviceId, String identifier, String phase,
                                  int shootSeconds, boolean abortRequested, Instant startedAt,
                                  Instant finishedAt, String failure, Map<String, Object> entry,
                                  List<LogLine> logs, List<UploadRow> upload) {

        /** 终态（完成/失败/中止）；UPLOADING 超时等救援不算终态。 */
        public boolean terminal() {
            return PHASE_DONE.equals(phase) || PHASE_FAILED.equals(phase) || PHASE_ABORTED.equals(phase);
        }
    }

    public record LogLine(Instant ts, String level, String message) {}

    /** §4.3.11 上传行（只留 UI 要展示的字段）。 */
    public record UploadRow(long id, String productInfo, String status,
                            String errorMessage, String rejectReason, String mainImg) {}
}
