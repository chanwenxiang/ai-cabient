package com.aicabinet.trade.service;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.common.dto.VisionResultIngestResponseDto;
import com.aicabinet.common.enums.SessionState;
import com.aicabinet.common.enums.VisionIngestOutcome;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.ShoppingSession;
import com.aicabinet.trade.mapper.ShoppingSessionMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.aicabinet.trade.support.ApiMessages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.List;

/**
 * 端侧识别结果直报入站（第三方端侧盒子识别完成后上报，见 {@code QuectelRecognizer} 契约自述）。
 *
 * <p><b>与 Kafka 通道的关系</b>：本类不重新实现结算，采纳时一律调用
 * {@link SessionService#completeAsyncRecognition}，与
 * {@link com.aicabinet.trade.messaging.VisionRecognitionListener} 走同一条结算链路，
 * 保证「同一份识别结果从哪个入口进来都得到同一个结果」。
 *
 * <p>本类只额外做两件事：
 * <ol>
 *   <li><b>入口 fail-closed 校验</b>：{@code modelVersion} 必填。结算管线以
 *       「modelVersion 是否含 mock/fallback」判定「非生产精度不得静默扣款」
 *       （{@code SettlementService#blocksSilentSettle}）；Kafka 通道的该字段来自
 *       vision-service 代码常量、外部无法构造，而 HTTP 入站可被外部构造
 *       ⇒ 若允许留空即可绕过该闸，故在入口直接拒绝。同处一并拦截
 *       {@code modelVersion} 超列宽（{@link VisionRecognitionResultDto#MODEL_VERSION_MAX_LENGTH}），
 *       避免「入口放行 → 写库报错 → 被 best-effort 吞掉」造成识别结果静默缺失。</li>
 *   <li><b>明确回执</b>：结算侧 {@code doCompleteAsyncRecognition} 在非 RECOGNIZING 态是
 *       静默 return，HTTP 调用方无从区分「已结算」与「被丢弃」⇒ 本类把受理结论显式回给端侧，
 *       端侧据此决定是否重发。</li>
 *   <li><b>通道健康度信号</b>（P0-1 阶段 B「边缘盒监控」）：每次上报记一笔受理结论计数
 *       （{@code cabinet.edge.ingest{outcome}}）。{@code TOO_EARLY} 偏高说明端侧报得太早、
 *       {@code ALREADY_HANDLED} 偏高说明端侧在重发、{@code PROCESSED} 长时间为 0 说明端侧通道断了 ——
 *       没有这个计数，端侧通道的质量问题在监控上是隐形的。</li>
 * </ol>
 */
@Service
public class VisionResultIngestService {

    private static final Logger log = LoggerFactory.getLogger(VisionResultIngestService.class);

    private final ShoppingSessionMapper repository;
    private final SessionService sessionService;
    private final CabinetMetrics metrics;

    public VisionResultIngestService(ShoppingSessionMapper repository, SessionService sessionService,
                                     CabinetMetrics metrics) {
        this.repository = repository;
        this.sessionService = sessionService;
        this.metrics = metrics;
    }

    /**
     * 受理一批端侧识别结果（一个会话一份结果）。
     *
     * @throws ResponseStatusException 400=入参不合法；404=会话不存在
     */
    public VisionResultIngestResponseDto ingest(VisionRecognitionResultDto request) {
        validate(request);
        String sessionId = request.sessionId().trim();
        ShoppingSession before = repository.findById(sessionId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, ApiMessages.SESSION_NOT_FOUND));
        SessionState stateBefore = before.getState();

        if (stateBefore != SessionState.RECOGNIZING) {
            VisionIngestOutcome outcome = outcomeFor(stateBefore);
            // 端侧通道健康度：非 RECOGNIZING 的每一次上报都记一笔（TOO_EARLY 高说明端侧报得太早、
            // ALREADY_HANDLED 高说明端侧在重发）——没有这个计数，通道质量问题在监控上是隐形的。
            metrics.recordEdgeIngest(outcome.name());
            log.info("端侧识别结果未采纳 session={} state={} outcome={}",
                    sessionId, stateBefore, outcome);
            return new VisionResultIngestResponseDto(false, outcome,
                    reasonFor(stateBefore), sessionId, request.taskId(), stateBefore.name());
        }

        VisionServiceClient.RecognitionResult result = toResult(request);
        sessionService.completeAsyncRecognition(sessionId, result);

        String stateAfter = repository.findById(sessionId)
                .map(session -> session.getState().name())
                .orElse("UNKNOWN");
        metrics.recordEdgeIngest(VisionIngestOutcome.PROCESSED.name());
        log.info("端侧识别结果已采纳 session={} task={} provider={} 终态={}",
                sessionId, request.taskId(), request.provider(), stateAfter);
        return new VisionResultIngestResponseDto(true, VisionIngestOutcome.PROCESSED,
                "已采纳并结算，会话终态 " + stateAfter, sessionId, request.taskId(), stateAfter);
    }

    /** 非识别态下的受理结论：已处理过的不必重发，未到识别态的可以稍后重试。 */
    private static VisionIngestOutcome outcomeFor(SessionState state) {
        return switch (state) {
            case SETTLING, COMPLETED, DISPUTED, FAILED -> VisionIngestOutcome.ALREADY_HANDLED;
            case CANCELLED -> VisionIngestOutcome.CANCELLED;
            case CREATED, OPENING, SHOPPING, WAITING_UPLOAD, RECOGNIZING -> VisionIngestOutcome.TOO_EARLY;
        };
    }

    private static String reasonFor(SessionState state) {
        return switch (state) {
            case SETTLING -> "会话结算进行中，识别结果已受理过，请勿重发";
            case COMPLETED -> "会话已完成结算，请勿重发";
            case DISPUTED -> "识别结果已进入人工审核，请勿重发";
            case FAILED -> "会话结算已失败，请勿重发（重试需由平台侧发起）";
            case CANCELLED -> "会话已取消，请勿重发";
            case CREATED, OPENING, SHOPPING -> "会话尚未关门识别，请在会话进入识别态后上报";
            case WAITING_UPLOAD -> "会话等待视频上传，尚未进入识别态，请稍后重试";
            case RECOGNIZING -> "会话处于识别态";
        };
    }

    private static void validate(VisionRecognitionResultDto request) {
        if (request == null) {
            throw badRequest("请求体不能为空");
        }
        if (isBlank(request.sessionId())) {
            throw badRequest("sessionId 必填：需指明识别结果归属的开门会话");
        }
        if (isBlank(request.modelVersion())) {
            throw badRequest("modelVersion 必填：平台据此判定识别是否生产精度，留空会导致"
                    + "「非生产精度不得静默扣款」校验被绕过");
        }
        if (request.modelVersion().trim().length() > VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH) {
            throw badRequest("modelVersion 超长：识别结果表该列宽为 "
                    + VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH
                    + " 字符，超长会被平台拒收，请上报真实且不超长的版本号");
        }
        if (request.items() != null) {
            for (VisionRecognitionResultDto.Item item : request.items()) {
                if (item == null || isBlank(item.skuId())) {
                    throw badRequest("items[].skuId 必填");
                }
                if (item.quantity() <= 0) {
                    throw badRequest("items[].quantity 必须大于 0");
                }
            }
        }
    }

    private static VisionServiceClient.RecognitionResult toResult(VisionRecognitionResultDto request) {
        List<VisionServiceClient.RecognizedItem> items = new ArrayList<>();
        if (request.items() != null) {
            for (VisionRecognitionResultDto.Item item : request.items()) {
                items.add(new VisionServiceClient.RecognizedItem(
                        item.skuId().trim(), item.quantity(), (float) item.confidence()));
            }
        }
        List<String> classes = request.detectedClasses() == null
                ? List.of()
                : List.copyOf(request.detectedClasses());
        String taskId = isBlank(request.taskId())
                ? "T-" + request.sessionId().trim()
                : request.taskId().trim();
        return new VisionServiceClient.RecognitionResult(
                taskId,
                items,
                (float) request.overallConfidence(),
                request.needReview(),
                request.modelVersion().trim(),
                classes);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }
}
