package com.aicabinet.trade.service;

import com.aicabinet.common.dto.VisionRecognitionResultDto;
import com.aicabinet.trade.client.VisionServiceClient;
import com.aicabinet.trade.domain.RecognitionResult;
import com.aicabinet.trade.mapper.RecognitionResultMapper;
import com.aicabinet.trade.metrics.CabinetMetrics;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 把平台采纳的识别结果写入 {@code recognition_result}（会话维度一份）。
 *
 * <p><b>为什么要有这个类</b>：该表自 {@code V1__init_schema.sql} 起就存在，但全仓 java/xml 零引用、
 * 线上 0 行——平台把识别结果直接喂给结算，谁都没落库。对账（端侧上报 vs 平台记账）与识别准确率
 * 看板因此没有数据源。写入点选在结算侧的识别收敛处（{@code SettlementRecognitionService}），
 * 一处即可覆盖同步关门、异步回调、开发上传三条路径。
 *
 * <p><b>为什么必须与结算共用同一个事务</b>：本类由结算事务内部调用，而结算事务持有
 * {@code shopping_session} 该行的 {@code FOR UPDATE}；{@code recognition_result.session_id} 又是指向
 * {@code shopping_session} 的外键，PostgreSQL 插入子行时会对父行取 {@code FOR KEY SHARE}——
 * 若改用 {@code REQUIRES_NEW} 独立事务，就会与外层 {@code FOR UPDATE} 互锁。2026-09-19 实测对照：
 *
 * <pre>
 *   无锁插入                 → INSERT 0 1（6 ms）
 *   外层持 FOR UPDATE 后插入 → ERROR: canceling statement due to statement timeout
 *                             CONTEXT: while locking tuple (0,14) in relation "shopping_session"
 *                             SQL: SELECT 1 FROM ONLY shopping_session … FOR KEY SHARE OF x
 * </pre>
 *
 * 而同一事务内插入不冲突（事务不会阻塞自己已持有的锁）⇒ 只能同事务。
 *
 * <p><b>失败语义：刻意 fail-hard，不静默</b>。本类不吞异常——库错误必须让结算一起回滚，否则会出现
 * 「会话已结算、订单已扣款，却没有识别记录」的对账黑洞，而那正是本批要消灭的东西。为把 fail-hard
 * 的触发面压到接近零：所有列约束在触碰数据库之前先校验（坏输入直接返回 {@link Outcome#INVALID}，
 * 不产生 SQL 错误）、{@code task_id} 冲突交由 {@code ON CONFLICT DO NOTHING} 化解
 * （端侧任务号跨会话复用不该拖垮结算）。剩下的失败面只有真实的基础设施故障——那种情况下结算本来
 * 也跑不下去。
 *
 * <p><b>幂等与去重</b>（两把闸）：
 * <ol>
 *   <li>{@code task_id} 是主键 ⇒ 同任务重投不得报错也不得产生第二行，命中即 {@link Outcome#DUPLICATE_TASK}；</li>
 *   <li>会话维度唯一（与 V222 demo seed 同语义）⇒ 同会话换 taskId 再报，命中即
 *       {@link Outcome#SESSION_ALREADY_RECORDED}，避免准确率看板把同一单算两次。</li>
 * </ol>
 */
@Service
public class RecognitionResultWriter {

    private static final Logger log = LoggerFactory.getLogger(RecognitionResultWriter.class);

    /** 与 V222 demo seed / 表注释一致的融合模式取值。 */
    static final String FUSION_MODE_VISION = "VISION";
    /** {@code recognition_result.task_id} 列宽。 */
    static final int TASK_ID_MAX_LENGTH = 64;
    private static final String EMPTY_ITEMS_JSON = "[]";

    private final RecognitionResultMapper mapper;
    private final ObjectMapper objectMapper;
    private final CabinetMetrics metrics;

    public RecognitionResultWriter(RecognitionResultMapper mapper, ObjectMapper objectMapper,
                                   CabinetMetrics metrics) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /** 落库结论。{@link #INVALID} 由调用方记日志——不做静默。 */
    public enum Outcome {
        /** 已写入。 */
        WRITTEN,
        /** 同一 task_id 已有行：重投，无需再写。 */
        DUPLICATE_TASK,
        /** 同一会话已有识别结果：会话维度唯一，丢弃。 */
        SESSION_ALREADY_RECORDED,
        /** 入参不满足列约束（缺 taskId / taskId 或 modelVersion 超列宽 / items 无法序列化）。 */
        INVALID
    }

    /**
     * 写入一份识别结果；必须在结算事务内调用（见类注释）。
     *
     * @param sessionId   归属会话（以会话实体上的 id 为准，不用请求体字段）
     * @param recognition 平台采纳的识别结果
     * @throws RuntimeException 仅当真实的基础设施错误发生——刻意不吞，让结算一同回滚
     */
    public Outcome persist(String sessionId, VisionServiceClient.RecognitionResult recognition) {
        if (sessionId == null || sessionId.isBlank() || recognition == null) {
            log.warn("识别结果落库跳过：会话号或结果为空 session={}", sessionId);
            return Outcome.INVALID;
        }
        String taskId = recognition.taskId();
        if (taskId == null || taskId.isBlank()) {
            log.warn("识别结果落库跳过：缺 taskId session={}", sessionId);
            return Outcome.INVALID;
        }
        if (taskId.length() > TASK_ID_MAX_LENGTH) {
            log.warn("识别结果落库跳过：taskId 超列宽 session={} len={}", sessionId, taskId.length());
            return Outcome.INVALID;
        }
        String modelVersion = recognition.modelVersion();
        if (modelVersion != null && modelVersion.length() > VisionRecognitionResultDto.MODEL_VERSION_MAX_LENGTH) {
            log.warn("识别结果落库跳过：modelVersion 超列宽 session={} len={}",
                    sessionId, modelVersion.length());
            return Outcome.INVALID;
        }
        String itemsJson;
        try {
            itemsJson = writeItems(recognition.items());
        } catch (JsonProcessingException e) {
            log.warn("识别结果落库跳过：items 序列化失败 session={}", sessionId, e);
            return Outcome.INVALID;
        }

        if (mapper.findById(taskId).isPresent()) {
            log.info("识别结果已存在，跳过 session={} task={}", sessionId, taskId);
            return Outcome.DUPLICATE_TASK;
        }
        if (mapper.findBySessionId(sessionId).isPresent()) {
            log.info("会话已有识别结果，跳过 session={} task={}", sessionId, taskId);
            return Outcome.SESSION_ALREADY_RECORDED;
        }

        RecognitionResult row = new RecognitionResult();
        row.setTaskId(taskId);
        row.setSessionId(sessionId);
        row.setItems(itemsJson);
        row.setOverallConfidence(recognition.overallConfidence());
        row.setFusionMode(FUSION_MODE_VISION);
        row.setModelVersion(modelVersion);
        row.setNeedReview(recognition.needReview());
        // created_at 交给列默认 NOW()
        int inserted = mapper.insertIgnoreConflict(row);
        if (inserted == 0) {
            log.info("识别结果落库冲突（task_id 已被占用），跳过 session={} task={}", sessionId, taskId);
            return Outcome.DUPLICATE_TASK;
        }
        log.info("识别结果已落库 session={} task={} items={} modelVersion={}",
                sessionId, taskId, itemsJson, modelVersion);
        // 质量指标只在**真正写入**时记：重投/同会话重复都会被前面两道幂等闸挡住，
        // 若在入口记就会把同一份识别结果重复计入分母，把 need_review 率算低。
        metrics.recordRecognition(recognition.overallConfidence(), Boolean.TRUE.equals(recognition.needReview()));
        return Outcome.WRITTEN;
    }

    private String writeItems(List<VisionServiceClient.RecognizedItem> items) throws JsonProcessingException {
        if (items == null || items.isEmpty()) {
            return EMPTY_ITEMS_JSON;
        }
        return objectMapper.writeValueAsString(items);
    }
}
