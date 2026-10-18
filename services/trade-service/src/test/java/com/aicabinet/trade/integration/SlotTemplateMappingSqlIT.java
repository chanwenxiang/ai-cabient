package com.aicabinet.trade.integration;

import com.aicabinet.trade.domain.SlotTemplate;
import com.aicabinet.trade.domain.SlotTemplateLine;
import com.aicabinet.trade.mapper.SlotTemplateLineMapper;
import com.aicabinet.trade.mapper.SlotTemplateMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * AU-172（2026-10-18 批次 3）真库回归：陈列模板主键映射。
 * <p>旧实现 {@code @TableId(type=AUTO)} 无 {@code value="id"}，MyBatis-Plus 按
 * camel→snake 拼 {@code template_id}/{@code line_id} 列，而 V368 DDL 主键列名是
 * {@code id} ⇒ insert/selectById/deleteById 全链运行时 {@code column does not exist} 500
 * （单测纯 Mockito 不连库，拦不住）。修复后本 IT 走真库验证三链。</p>
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
class SlotTemplateMappingSqlIT {

    private static final long OPERATOR = 770001L;

    @Container
    @SuppressWarnings("resource")
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aicabinet_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
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
    private SlotTemplateMapper templateMapper;

    @Autowired
    private SlotTemplateLineMapper lineMapper;

    @Test
    void insert_selectById_deleteById_runOnRealColumns() {
        SlotTemplate template = new SlotTemplate();
        template.setTemplateName("AU172-映射回归");
        template.setCreatedBy(OPERATOR);

        templateMapper.insert(template);
        // 修复前此处已 500（column "template_id" does not exist）；修复后 AUTO id 正常回填
        assertNotNull(template.getTemplateId(), "insert 后主键应回填");

        SlotTemplateLine line = new SlotTemplateLine();
        line.setTemplateId(template.getTemplateId());
        line.setSlotCode("A1");
        line.setRowNo(1);
        line.setColNo(1);
        line.setSkuId("SKU-AU172");
        lineMapper.insert(line);
        assertNotNull(line.getLineId(), "行 insert 后主键应回填");

        SlotTemplate loaded = templateMapper.selectById(template.getTemplateId());
        assertNotNull(loaded, "selectById 应按 id 列命中（修复前拼 template_id 500）");
        assertEquals("AU172-映射回归", loaded.getTemplateName());

        lineMapper.deleteByTemplateId(template.getTemplateId()); // default 方法，void
        assertEquals(1, templateMapper.deleteById(template.getTemplateId()));
        assertEquals(0, lineMapper.findByTemplateId(template.getTemplateId()).size());
    }
}
