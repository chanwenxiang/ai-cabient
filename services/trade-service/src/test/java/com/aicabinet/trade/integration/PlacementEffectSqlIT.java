package com.aicabinet.trade.integration;

import com.aicabinet.common.dto.PlacementEffectRowDto;
import com.aicabinet.trade.domain.AdPlayEvent;
import com.aicabinet.trade.mapper.AdPlayEventMapper;
import com.aicabinet.trade.service.MarketingRoiService;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 活动位打点的真库钉测（批次 T）。
 *
 * <p>为什么必须是 IT 而不是单测：两条聚合 SQL 与 {@code position}/{@code user_id}/{@code activity_id}
 * 三个新列的 MyBatis-Plus 生成语句在 Mockito 下一行都跑不到——同型教训已记过两次
 * （{@code WithdrawClaimForPayoutSqlIT}：条件 UPDATE 生成的 SQL 单测跑不到）。
 * 而 {@code position} 在 PG 里同时是函数名，列名能不能被正确引用也只能靠真库回答。
 *
 * <p>钉住的三件事：
 * <ol>
 *   <li>通过 mapper 写进去的新列能读回来（INSERT 语句真的带上了这三列）；</li>
 *   <li>人均分母只算已归因行：未归因行单列，不混进 {@code COUNT(DISTINCT user_id)}；</li>
 *   <li>单用户峰值取的是「人」这一层的最大计数，而不是总次数。</li>
 * </ol>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class PlacementEffectSqlIT {

    /** 用一段不会与真实业务相撞的 id 段，测试前后各自清空。 */
    private static final long ACTIVITY_ID = 900001L;
    private static final long CAMPAIGN_ID = 900002L;
    private static final long USER_A = 900777L;
    private static final long USER_B = 900888L;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            org.testcontainers.utility.DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void datasourceProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));
    }

    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private AdPlayEventMapper playEventMapper;
    @Autowired
    private MarketingRoiService marketingRoiService;

    @BeforeEach
    @AfterEach
    void cleanTestRows() {
        jdbc.update("DELETE FROM ad_play_event WHERE activity_id = ? OR campaign_id = ?",
                ACTIVITY_ID, CAMPAIGN_ID);
        // V440 后「无主体行」本来就被 CHECK 挡住，这里只清上一条用例里那条刻意违反约束的探针残留
        jdbc.update("DELETE FROM ad_play_event WHERE campaign_id IS NULL AND activity_id IS NULL");
    }

    /** 只认本用例自播的主体，避免别的行把断言带偏。 */
    private static boolean isMine(Map<String, Object> r) {
        return "ACTIVITY".equals(String.valueOf(r.get("subject_kind")))
                && ((Number) r.get("subject_id")).longValue() == ACTIVITY_ID;
    }

    /**
     * 自播两个主体：campaign_id 有 FK 指向 ad_campaign（V217），activity_id 没有 FK——
     * 这正是要钉的地方：弹窗行只靠服务层的「活动必须存在且 ACTIVE」拦，DB 层不兜底。
     * 播种同时让 subjectName 解析走真库（ACT/CAM 两套 key 不能串）。
     */
    private void seedSubjects() {
        jdbc.update("INSERT INTO ad_campaign (campaign_id, name, status) VALUES (?, ?, 'RUNNING') "
                + "ON CONFLICT (campaign_id) DO NOTHING", CAMPAIGN_ID, "批次T测试广告计划");
        jdbc.update("INSERT INTO promotion_activity (activity_id, activity_name, activity_type, "
                + "status, start_time, end_time) VALUES (?, ?, 'DISCOUNT', 'ACTIVE', NOW(), NOW()) "
                + "ON CONFLICT (activity_id) DO NOTHING", ACTIVITY_ID, "批次T测试活动");
    }

    private void insert(String position, Long activityId, Long campaignId, Long userId, String type) {
        AdPlayEvent ev = new AdPlayEvent();
        ev.setPosition(position);
        ev.setActivityId(activityId);
        ev.setCampaignId(campaignId);
        ev.setUserId(userId);
        // asset_id 有 FK 指向 media_asset（V217），本用例不测素材维度，留空即可
        ev.setEventType(type);
        ev.setCreatedAt(Instant.now());
        playEventMapper.insert(ev);
    }

    @Test
    @DisplayName("新列经 mapper 写入后可读回；人均分母只算已归因行；峰值取人级最大")
    void placementAggregation_readsNewColumns_andKeepsUnattributedOutOfDenominator() {
        seedSubjects();
        // 弹窗位：A 两次 + B 一次 = 3 次曝光 / 2 人；外加一条未归因（设备侧或迁移前行）
        insert("POPUP_BEFORE_OPEN", ACTIVITY_ID, null, USER_A, "IMPRESSION");
        insert("POPUP_BEFORE_OPEN", ACTIVITY_ID, null, USER_A, "IMPRESSION");
        insert("POPUP_BEFORE_OPEN", ACTIVITY_ID, null, USER_B, "IMPRESSION");
        insert("POPUP_BEFORE_OPEN", ACTIVITY_ID, null, null, "IMPRESSION");
        insert("POPUP_BEFORE_OPEN", ACTIVITY_ID, null, USER_B, "CLICK");
        // banner 位（广告计划主体，无 activity_id）
        insert("BANNER", null, CAMPAIGN_ID, USER_A, "IMPRESSION");

        List<Map<String, Object>> rows =
                playEventMapper.aggregatePlacementsSince(Instant.now().minusSeconds(300));
        Map<String, Object> popup = rows.stream()
                .filter(PlacementEffectSqlIT::isMine)
                .findFirst()
                .orElseThrow(() -> new AssertionError("弹窗位聚合行缺失：" + rows));
        assertEquals("POPUP_BEFORE_OPEN", String.valueOf(popup.get("position")),
                "position 列必须能原样读回（PG 里 position 同时是函数名，列引用写错这里就红）");
        assertEquals(4L, ((Number) popup.get("exposure_count")).longValue());
        assertEquals(2L, ((Number) popup.get("exposure_users")).longValue(),
                "未归因行不得进 COUNT(DISTINCT user_id)");
        assertEquals(1L, ((Number) popup.get("unattributed_count")).longValue());
        assertEquals(1L, ((Number) popup.get("click_count")).longValue());

        List<Map<String, Object>> peaks =
                playEventMapper.peakExposurePerUserSince(Instant.now().minusSeconds(300));
        Map<String, Object> peak = peaks.stream()
                .filter(PlacementEffectSqlIT::isMine)
                .findFirst()
                .orElseThrow(() -> new AssertionError("人级峰值行缺失：" + peaks));
        assertEquals(2L, ((Number) peak.get("max_exposure_per_user")).longValue(),
                "峰值应是「某一个人」的最大次数（A 两次），不是总次数 4");

        List<PlacementEffectRowDto> svc = marketingRoiService.placements(7);
        PlacementEffectRowDto popupRow = svc.stream()
                .filter(r -> "ACTIVITY".equals(r.subjectKind()) && r.subjectId() == ACTIVITY_ID)
                .findFirst()
                .orElseThrow(() -> new AssertionError("服务层未返回弹窗位行：" + svc));
        assertEquals(2.0, popupRow.exposurePerUser(), 1e-9);
        assertEquals(2L, popupRow.maxExposurePerUser());
        assertEquals("批次T测试活动", popupRow.subjectName(),
                "弹窗位主体名必须取自 promotion_activity（ACT| 键），不能落到广告计划的 id 空间");
        PlacementEffectRowDto bannerRow = svc.stream()
                .filter(r -> "AD_CAMPAIGN".equals(r.subjectKind()) && r.subjectId() == CAMPAIGN_ID)
                .findFirst()
                .orElseThrow(() -> new AssertionError("banner 位行缺失：" + svc));
        assertEquals("批次T测试广告计划", bannerRow.subjectName(),
                "banner 位主体名必须取自 ad_campaign（CAM| 键）");
        assertEquals("BANNER", bannerRow.position());
        assertTrue(svc.stream().anyMatch(r -> "AD_CAMPAIGN".equals(r.subjectKind()) && r.subjectId() == CAMPAIGN_ID),
                "banner 位应以广告计划主体单独成行，不与活动混列");
    }

    @Test
    @DisplayName("两个主体都缺的行必须被 CHECK 拒掉（放开 NOT NULL 后唯一的底线）")
    void rowWithoutAnySubject_isRejectedByCheck() {
        AdPlayEvent ev = new AdPlayEvent();
        ev.setPosition("BANNER");
        ev.setUserId(USER_A);
        ev.setEventType("IMPRESSION");
        ev.setCreatedAt(Instant.now());
        try {
            playEventMapper.insert(ev);
            throw new AssertionError("campaign_id 与 activity_id 全空的行必须被 ck_ad_play_subject 拒绝");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertTrue(String.valueOf(expected.getMessage()).contains("ck_ad_play_subject"),
                    "报错应来自我们那条 CHECK，而不是别的约束：" + expected.getMessage());
        }
    }
}
