package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.RecognitionResult;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface RecognitionResultMapper extends BaseTradeMapper<RecognitionResult> {

    /**
     * 按会话取识别结果（会话维度唯一，见实体类注释）。
     * 用 {@code selectList + LIMIT 1} 而非 {@code selectOne}：即便历史数据出现同会话多行也不会抛
     * {@code TooManyResultsException} 把结算链路带崩。
     */
    default Optional<RecognitionResult> findBySessionId(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return Optional.empty();
        }
        List<RecognitionResult> rows = selectList(Wrappers.<RecognitionResult>lambdaQuery()
                .eq(RecognitionResult::getSessionId, sessionId)
                .last("LIMIT 1"));
        return rows.stream().findFirst();
    }

    /**
     * 写入识别结果；{@code task_id} 冲突时静默跳过而不抛错。
     *
     * <p>为什么要单独写这条 SQL：本方法跑在<b>结算事务内</b>（见 {@code RecognitionResultWriter} 类注释），
     * 而 PostgreSQL「事务内任一语句报错即整个事务作废」——若用普通 insert 撞主键，会把同事务里的
     * 结算/扣款一起毒死。端侧任务号跨会话复用属于外部可控输入，不该成为拖垮结算的路径。
     *
     * <p>{@code items} 是 JSONB，必须显式带 typeHandler：自定义语句不会读实体的
     * {@code @TableField(typeHandler=…)}。
     *
     * @return 实际写入行数（0 = 冲突未写）
     */
    @Insert("""
            INSERT INTO recognition_result
                (task_id, session_id, items, overall_confidence, fusion_mode, model_version, need_review)
            VALUES
                (#{taskId}, #{sessionId},
                 #{items, typeHandler=com.aicabinet.trade.config.JsonStringTypeHandler},
                 #{overallConfidence}, #{fusionMode}, #{modelVersion}, #{needReview})
            ON CONFLICT (task_id) DO NOTHING
            """)
    int insertIgnoreConflict(RecognitionResult row);
}
