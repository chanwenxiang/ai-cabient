package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.client.JiangyiGatewayClient;
import com.aicabinet.trade.domain.JiangyiClassMapping;
import com.aicabinet.trade.domain.JiangyiDevice;
import com.aicabinet.trade.domain.JiangyiModelDeployment;
import com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiModelFile;
import com.aicabinet.trade.mapper.JiangyiClassMappingMapper;
import com.aicabinet.trade.mapper.JiangyiDeviceMapper;
import com.aicabinet.trade.mapper.JiangyiModelDeploymentMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 模型同步编排（CB-023 范围 A）单测：机型字符串相等校验（存原值不解释）/ 未决 SENT
 * 幂等 409 / 回执防串包 409 / gateway 下发失败回滚 FAILED。
 * classes.txt 拉取走公网 OSS（RestClient.create），正向 pushModel 由 IT/联调覆盖，
 * 单测钉住所有进入拉取之前的负向与回执侧。
 */
@ExtendWith(MockitoExtension.class)
@org.springframework.test.context.TestPropertySource(
        properties = "aicabinet.jiangyi.model-sync.allowed-hosts=hunanjysmart.com,localhost,127.0.0.1")
class JiangyiModelSyncServiceTest {

    private static final String DEVICE_ID = "100000000001";
    private static final String MODEL_NAME = "JY-DOOR-01-model";

    /** classes.txt 拉取是公网 OSS（service 内 RestClient.create 不可注入）——测试内起本地 HTTP 兜住正向路径。 */
    private static com.sun.net.httpserver.HttpServer classesServer;
    private static String classesUrl;

    @BeforeAll
    static void startClassesServer() throws java.io.IOException {
        classesServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress(0), 0);
        classesServer.createContext("/classes.txt", exchange -> {
            byte[] body = "农夫山泉 550ml\n可口可乐 330ml".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        classesServer.start();
        classesUrl = "http://127.0.0.1:" + classesServer.getAddress().getPort() + "/classes.txt";
    }

    @AfterAll
    static void stopClassesServer() {
        classesServer.stop(0);
    }

    @Mock private JiangyiGatherClient jiangyiGatherClient;
    @Mock private JiangyiDeviceMapper jiangyiDeviceMapper;
    @Mock private JiangyiClassMappingMapper jiangyiClassMappingMapper;
    @Mock private JiangyiModelDeploymentMapper deploymentMapper;
    @Mock private JiangyiGatewayClient jiangyiGatewayClient;
    @Mock private com.aicabinet.trade.mapper.SkuCatalogMapper skuCatalogMapper;
    @Mock private AdminAuditService auditService;

    private JiangyiModelSyncService service() {
        JiangyiModelSyncService svc = new JiangyiModelSyncService(jiangyiGatherClient, jiangyiDeviceMapper,
                jiangyiClassMappingMapper, deploymentMapper, jiangyiGatewayClient, skuCatalogMapper, auditService);
        // AU-183：白名单注入本地 HttpServer host（守卫默认仅将邑域）
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "allowedModelHosts",
                java.util.List.of("hunanjysmart.com", "http://localhost", "http://127.0.0.1"));
        return svc;
    }

    private JiangyiDevice boundDevice(String industrialControlModel) {
        JiangyiDevice d = new JiangyiDevice();
        d.setDeviceId(DEVICE_ID);
        d.setStatus("BOUND");
        d.setIdentifier("CQYB11253");
        d.setIndustrialControlModel(industrialControlModel);
        return d;
    }

    private JiangyiModelFile modelFile(String industrialControlModel) {
        return new JiangyiModelFile(7L, MODEL_NAME, null, "http://oss/model.rknn",
                industrialControlModel, classesUrl, 2);
    }

    @Test
    void pushModel_deviceWithoutIndustrialControlModelIsRejected() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice(null));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(1L, DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        verify(deploymentMapper, org.mockito.Mockito.never())
                .insert(any(JiangyiModelDeployment.class));
    }

    @Test
    void pushModel_unknownModelIs404() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(1L, DEVICE_ID, "不存在的模型"));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void pushModel_industrialControlModelMismatchIsRejected() {
        // 机型校验 = 字符串相等（原值 "76"/"88" 不解释——PDF 示例两主板同为 "88"，映射关系文档自证不了）
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(1L, DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("机型不匹配"));
    }

    @Test
    void pushModel_pendingSentDeploymentBlocksAsIdempotent() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("88"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));
        when(deploymentMapper.hasPendingSent(DEVICE_ID)).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(1L, DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("未完成的模型下发"));
    }

    @Test
    void pushModel_gatewayFailureMarksDeploymentFailed() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("88"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));
        when(deploymentMapper.hasPendingSent(DEVICE_ID)).thenReturn(false);
        doAnswer(inv -> {
            JiangyiModelDeployment dep = inv.getArgument(0);
            dep.setId(55L);
            return 1;
        }).when(deploymentMapper).insert(any(JiangyiModelDeployment.class));
        doThrow(new IllegalStateException("gateway 503")).when(jiangyiGatewayClient)
                .modelPush(eq(DEVICE_ID), anyLong(), anyString(), anyString(), anyString(), anyInt());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(1L, DEVICE_ID, MODEL_NAME));
        assertEquals(502, e.getStatusCode().value());
        verify(deploymentMapper).fail(eq(55L), anyString(), any());
    }

    @Test
    void pushModel_successInsertsSentDeploymentWithParsedClassesVersion() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("88"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));
        when(deploymentMapper.hasPendingSent(DEVICE_ID)).thenReturn(false);
        doAnswer(inv -> {
            JiangyiModelDeployment dep = inv.getArgument(0);
            dep.setId(56L);
            return 1;
        }).when(deploymentMapper).insert(any(JiangyiModelDeployment.class));

        long deploymentId = service().pushModel(1L, DEVICE_ID, MODEL_NAME);

        assertEquals(56L, deploymentId);
        verify(auditService).appendLog(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("JIANGYI_MODEL_PUSH"),
                org.mockito.ArgumentMatchers.eq("JIANGYI_DEVICE"),
                org.mockito.ArgumentMatchers.eq(DEVICE_ID),
                org.mockito.ArgumentMatchers.contains("deploymentId=56"));
        ArgumentCaptor<JiangyiModelDeployment> captor =
                ArgumentCaptor.forClass(JiangyiModelDeployment.class);
        verify(deploymentMapper).insert(captor.capture());
        JiangyiModelDeployment dep = captor.getValue();
        assertEquals("SENT", dep.getStatus());
        assertEquals("88", dep.getIndustrialControlModel());
        assertTrue(dep.getClassesVersion() != null && dep.getClassesVersion().length() == 12,
                "classesVersion 应为 12 位指纹：" + dep.getClassesVersion());
        verify(jiangyiGatewayClient).modelPush(eq(DEVICE_ID), eq(56L), eq(MODEL_NAME),
                eq("http://oss/model.rknn"), eq(classesUrl), eq(2));
    }

    /**
     * G-14 主证：显式 URL 下发**完全不查 §4.4.4**（租户 426 现回 []，这是不打扰厂商就能推模型的唯一入口）。
     */
    @Test
    void pushModelByUrl_neverConsultsVendorModelList() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(deploymentMapper.hasPendingSent(DEVICE_ID)).thenReturn(false);
        doAnswer(inv -> {
            ((JiangyiModelDeployment) inv.getArgument(0)).setId(57L);
            return 1;
        }).when(deploymentMapper).insert(any(JiangyiModelDeployment.class));

        long id = service().pushModelByUrl(1L, DEVICE_ID, new JiangyiModelSyncService.ExplicitModel(
                MODEL_NAME, "http://oss/explicit.rknn", classesUrl, 2));

        assertEquals(57L, id);
        verify(jiangyiGatherClient, org.mockito.Mockito.never()).modelFiles();
        ArgumentCaptor<JiangyiModelDeployment> captor =
                ArgumentCaptor.forClass(JiangyiModelDeployment.class);
        verify(deploymentMapper).insert(captor.capture());
        JiangyiModelDeployment dep = captor.getValue();
        assertEquals("SENT", dep.getStatus());
        assertNull(dep.getModelId(), "显式下发没有 §4.4.4 的 modelId，model_id 必须留空");
        assertEquals("76", dep.getIndustrialControlModel());
        verify(jiangyiGatewayClient).modelPush(eq(DEVICE_ID), eq(57L), eq(MODEL_NAME),
                eq("http://oss/explicit.rknn"), eq(classesUrl), eq(2));
    }

    @Test
    void pushModelByUrl_missingFieldIs400AndTouchesNothing() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModelByUrl(1L, DEVICE_ID, new JiangyiModelSyncService.ExplicitModel(
                        MODEL_NAME, "http://oss/m.rknn", " ", 2)));
        assertEquals(400, e.getStatusCode().value());
        verify(deploymentMapper, org.mockito.Mockito.never())
                .insert(any(JiangyiModelDeployment.class));
    }

    /** 行数≠quantity 一律拒（错位映射一旦激活就是错钱），显式路径不松这条。 */
    @Test
    void pushModelByUrl_quantityMismatchingClassesLinesIsRejectedBeforeAnyWrite() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));

        assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> service().pushModelByUrl(1L, DEVICE_ID, new JiangyiModelSyncService.ExplicitModel(
                        MODEL_NAME, "http://oss/m.rknn", classesUrl, 3)));
        verify(deploymentMapper, org.mockito.Mockito.never())
                .insert(any(JiangyiModelDeployment.class));
    }

    @Test
    void pregenerateForDeviceByUrl_writesDisabledRowsFromExplicitClasses() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of());

        int written = service().pregenerateForDeviceByUrl(1L, DEVICE_ID,
                new JiangyiModelSyncService.ExplicitModel(MODEL_NAME, "http://oss/m.rknn", classesUrl, 2), 0);

        assertEquals(2, written);
        verify(jiangyiGatherClient, org.mockito.Mockito.never()).modelFiles();
        ArgumentCaptor<com.aicabinet.trade.domain.JiangyiClassMapping> captor =
                ArgumentCaptor.forClass(com.aicabinet.trade.domain.JiangyiClassMapping.class);
        verify(jiangyiClassMappingMapper, org.mockito.Mockito.times(2)).upsert(captor.capture());
        assertEquals(List.of(0, 1),
                captor.getAllValues().stream().map(m -> m.getClassId()).toList());
        assertEquals("农夫山泉 550ml", captor.getAllValues().get(0).getTextName());
        assertTrue(captor.getAllValues().stream().allMatch(m -> "DISABLED".equals(m.getStatus())));
    }

    @Test
    void confirmModel_withoutPendingSentIsRejectedAsReplaySuspect() {
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().confirmModel(DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never())
                .updateModelInfo(anyString(), anyString(), anyString(), any());
    }

    /** V400：柜机回执一个我方从未 SENT、也从未 CONFIRMED 的模型（厂商直推/恢复出厂）⇒ 撤复核戳。 */
    @Test
    void confirmModel_unknownModelRevokesMappingVerification() {
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.empty());
        when(deploymentMapper.hasConfirmed(DEVICE_ID, MODEL_NAME)).thenReturn(false);

        assertThrows(ResponseStatusException.class, () -> service().confirmModel(DEVICE_ID, MODEL_NAME));

        verify(jiangyiDeviceMapper).clearMappingVerification(eq(DEVICE_ID), any());
    }

    /** 重放已知模型（曾 CONFIRMED）只是串包/重放，不能把已复核的柜子打成未复核。 */
    @Test
    void confirmModel_replayOfKnownModelKeepsMappingVerification() {
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.empty());
        when(deploymentMapper.hasConfirmed(DEVICE_ID, MODEL_NAME)).thenReturn(true);

        assertThrows(ResponseStatusException.class, () -> service().confirmModel(DEVICE_ID, MODEL_NAME));

        verify(jiangyiDeviceMapper, org.mockito.Mockito.never())
                .clearMappingVerification(anyString(), any());
    }

    @Test
    void confirmModel_fillsDeploymentAndDeviceModelInfo() {
        JiangyiModelDeployment sent = new JiangyiModelDeployment();
        sent.setId(55L);
        sent.setDeviceId(DEVICE_ID);
        sent.setModelName(MODEL_NAME);
        sent.setClassesTextUrl("https://x/classes.txt");
        sent.setClassesVersion("ab12cd34ef56");
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.of(sent));

        JiangyiModelDeployment returned = service().confirmModel(DEVICE_ID, MODEL_NAME);

        // 回执确认要把 deployment 交回调用方：到期自动复核靠它的 classesTextUrl + 内容指纹
        assertEquals(sent, returned);
        assertEquals("https://x/classes.txt", returned.getClassesTextUrl());
        verify(deploymentMapper).confirm(eq(55L), eq("ab12cd34ef56"), any());
        verify(jiangyiDeviceMapper).updateModelInfo(eq(DEVICE_ID), eq(MODEL_NAME),
                eq("ab12cd34ef56"), any());
    }

    private JiangyiModelDeployment sentWithTxtUrl(String classesTextUrl) {
        JiangyiModelDeployment sent = new JiangyiModelDeployment();
        sent.setId(55L);
        sent.setDeviceId(DEVICE_ID);
        sent.setModelName(MODEL_NAME);
        sent.setStatus("SENT");
        sent.setClassesTextUrl(classesTextUrl);
        sent.setClassesVersion("ab12cd34ef56");
        return sent;
    }

    /**
     * 2026-11-10 真机实证：806 固件不发 downloadModelNotify，开机 receiveCurrentTxtUrl 报回
     * 相对路径 {@code model/xxx.txt}；命中 SENT 的全 URL classesTextUrl 后缀即闭环 CONFIRMED。
     */
    @Test
    void confirmModelByTxtUrl_matchesSentByRelativeSuffixAndConfirms() {
        JiangyiModelDeployment sent = sentWithTxtUrl("https://oss/model/hysxr_3.txt");
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of(sent));
        // R6-D-R3-3：确认前重读目标行（selectById）—— 仍 SENT 才进 confirmModel
        when(deploymentMapper.selectById(55L)).thenReturn(sent);
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.of(sent));

        JiangyiModelDeployment returned = service().confirmModelByTxtUrl(DEVICE_ID, "model/hysxr_3.txt");

        assertEquals(sent, returned);
        verify(deploymentMapper).confirm(eq(55L), eq("ab12cd34ef56"), any());
        verify(jiangyiDeviceMapper).updateModelInfo(eq(DEVICE_ID), eq(MODEL_NAME), eq("ab12cd34ef56"), any());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never()).clearMappingVerification(anyString(), any());
    }

    /** 命中不到任何 SENT（厂商直推/恢复出厂的开机例行回执）⇒ no-op，绝不触发撤销复核分支。 */
    @Test
    void confirmModelByTxtUrl_noMatchingSentIsNoOpAndNeverRevokes() {
        JiangyiModelDeployment sent = sentWithTxtUrl("https://oss/model/another_3.txt");
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of(sent));

        assertNull(service().confirmModelByTxtUrl(DEVICE_ID, "model/hysxr_3.txt"));

        verify(deploymentMapper, org.mockito.Mockito.never()).confirm(anyLong(), anyString(), any());
        verify(deploymentMapper, org.mockito.Mockito.never()).findLatestSent(anyString(), anyString());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never()).clearMappingVerification(anyString(), any());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never()).updateModelInfo(anyString(), anyString(), anyString(), any());
    }

    /** 无任何下发记录的空柜开机 ⇒ no-op。 */
    @Test
    void confirmModelByTxtUrl_emptyDeploymentsIsNoOp() {
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of());

        assertNull(service().confirmModelByTxtUrl(DEVICE_ID, "model/hysxr_3.txt"));

        verify(deploymentMapper, org.mockito.Mockito.never()).confirm(anyLong(), anyString(), any());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never()).clearMappingVerification(anyString(), any());
    }

    /** 路径边界：othermodel/x.txt 不得误命中 model/x.txt（leading-slash 后缀判据）。 */
    @Test
    void confirmModelByTxtUrl_pathBoundaryPreventsSubstringMisMatch() {
        JiangyiModelDeployment sent = sentWithTxtUrl("https://oss/othermodel/x.txt");
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of(sent));

        assertNull(service().confirmModelByTxtUrl(DEVICE_ID, "model/x.txt"));

        verify(deploymentMapper, org.mockito.Mockito.never()).confirm(anyLong(), anyString(), any());
    }

    /** 已 CONFIRMED（无 SENT）的重放开机回执 ⇒ no-op，不重复确认、不撤复核。 */
    @Test
    void confirmModelByTxtUrl_alreadyConfirmedIsNoOp() {
        JiangyiModelDeployment confirmed = sentWithTxtUrl("https://oss/model/hysxr_3.txt");
        confirmed.setStatus("CONFIRMED");
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of(confirmed));

        assertNull(service().confirmModelByTxtUrl(DEVICE_ID, "model/hysxr_3.txt"));

        verify(deploymentMapper, org.mockito.Mockito.never()).confirm(anyLong(), anyString(), any());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never()).clearMappingVerification(anyString(), any());
    }

    @Test
    void txtUrlMatchesCoversEqualitySuffixAndBoundary() {
        assertTrue(JiangyiModelSyncService.txtUrlMatches("https://oss/model/x.txt", "model/x.txt"));
        assertTrue(JiangyiModelSyncService.txtUrlMatches("https://oss/model/x.txt", "https://oss/model/x.txt"));
        assertTrue(JiangyiModelSyncService.txtUrlMatches("model/x.txt", "https://oss/model/x.txt"));
        assertTrue(!JiangyiModelSyncService.txtUrlMatches("https://oss/othermodel/x.txt", "model/x.txt"));
        assertTrue(!JiangyiModelSyncService.txtUrlMatches("https://oss/model/x.txt", ""));
        assertTrue(!JiangyiModelSyncService.txtUrlMatches(null, "model/x.txt"));
        assertTrue(!JiangyiModelSyncService.txtUrlMatches("https://oss/model/x.txt", null));
    }

    @Test
    void markPushTimeoutDelegatesToFail() {
        when(deploymentMapper.fail(eq(55L), anyString(), any())).thenReturn(1);
        service().markPushTimeout(55L, "watchdog 600s 超时");
        verify(deploymentMapper).fail(eq(55L), anyString(), any());
    }

    /** V400：超时置败成功 ⇒ 柜机可能已切到新模型，必须撤复核戳（否则按旧模型映射错扣款）。 */
    @Test
    void markPushTimeoutWithChangedRowRevokesMappingVerification() {
        JiangyiModelDeployment sent = new JiangyiModelDeployment();
        sent.setDeviceId(DEVICE_ID);
        when(deploymentMapper.fail(eq(55L), anyString(), any())).thenReturn(1);
        when(deploymentMapper.byId(55L)).thenReturn(Optional.of(sent));

        service().markPushTimeout(55L, "watchdog 600s 超时");

        verify(jiangyiDeviceMapper).clearMappingVerification(eq(DEVICE_ID), any());
    }

    /** 已 FAILED 的重入 no-op（rows=0）不得反复撤销：幂等语义照旧。 */
    @Test
    void markPushTimeoutIdempotentReentryDoesNotRevokeMappingVerification() {
        when(deploymentMapper.fail(eq(55L), anyString(), any())).thenReturn(0);

        service().markPushTimeout(55L, "watchdog 600s 超时");

        verify(jiangyiDeviceMapper, org.mockito.Mockito.never())
                .clearMappingVerification(anyString(), any());
    }

    @Test
    void activatePregeneratedRequiresBoundDevice() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().activatePregenerated(1L, DEVICE_ID, MODEL_NAME));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void pregenerate_incompatibleModelRejectedBeforeAnyWrite() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pregenerateForDevice(1L, DEVICE_ID, MODEL_NAME, 0));
        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiClassMappingMapper, org.mockito.Mockito.never())
                .upsert(any(com.aicabinet.trade.domain.JiangyiClassMapping.class));
    }

    /**
     * AU-183 连带修复：白名单拒 / classes.txt 地址为空 / OSS 不可达都只降级成该行原因，
     * 不得让整页模型预览 400（真模型一来厂商桶常不在将邑域内，此前后台连列表都打不开）。
     */
    @Test
    void previewModels_degradesUnreachableClassesTextPerModelInsteadOfFailingWholeList() {
        JiangyiModelFile blocked = new JiangyiModelFile(8L, "JY-BLOCKED", null, "http://oss/b.rknn",
                "88", "https://evil.example.com/classes.txt", 2);
        JiangyiModelFile blank = new JiangyiModelFile(9L, "JY-BLANK", null, "http://oss/c.rknn",
                "88", "  ", 2);
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(blocked, blank, modelFile("88")));
        when(jiangyiGatherClient.learnableProducts()).thenReturn(List.of());

        List<JiangyiModelSyncService.ModelPreview> previews = service().previewModels(0);

        assertEquals(3, previews.size());
        assertEquals("模型 classes.txt 地址不在允许的将邑域内", previews.get(0).rejectReason());
        assertTrue(previews.get(0).rows().isEmpty());
        assertEquals("模型 classes.txt 地址为空", previews.get(1).rejectReason());
        assertNull(previews.get(2).rejectReason());
        assertEquals(2, previews.get(2).rows().size());
    }

    @Test
    void listDeploymentsDelegates() {
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of());
        assertTrue(service().listDeployments(DEVICE_ID).isEmpty());
    }

    // ---------- §5.5.3 厂商对照解码（只读，一行都不写） ----------

    private static com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiTextNameSku vendorRow(
            Long stdSkuId, String stdSkuName, String textName, String barCode, Integer classId) {
        return new com.aicabinet.trade.dto.JiangyiGatherDtos.JiangyiTextNameSku(
                stdSkuId, stdSkuName, textName, barCode, classId);
    }

    private static com.aicabinet.trade.domain.SkuCatalog sku(String skuId, String skuName, String barcode) {
        com.aicabinet.trade.domain.SkuCatalog s = new com.aicabinet.trade.domain.SkuCatalog();
        s.setSkuId(skuId);
        s.setSkuName(skuName);
        s.setBarcode(barcode);
        return s;
    }

    private static JiangyiClassMapping mapping(Integer classId, String skuId, String status) {
        JiangyiClassMapping m = new JiangyiClassMapping();
        m.setDeviceId(DEVICE_ID);
        m.setClassId(classId);
        m.setSkuId(skuId);
        m.setStatus(status);
        return m;
    }

    /** 正向：classes 行 + 厂商 stdSku + 本平台条码候选 + 该柜当前挂接，四列拼齐且零写入。 */
    @Test
    void decode_mergesVendorStdSkuAndLocalCandidatesWithoutWriting() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl)).thenReturn(List.of(
                vendorRow(101L, "农夫山泉饮用天然水550ml", "农夫山泉 550ml", "6921168596348", 0),
                vendorRow(777L, "不在这个 classes 里的商品", "别的产品", "6900000000000", 9)));
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of(
                sku("SKU-1", "农夫山泉 550ml", "6921168596348")));
        when(jiangyiClassMappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(0, "SKU-1", "ACTIVE")));

        JiangyiModelSyncService.ClassDecodeView view =
                service().decodeVendorSkuByClasses(DEVICE_ID, classesUrl, 2, 0);

        assertEquals(2, view.classesLines());
        assertEquals(2, view.vendorRows());
        assertEquals(1, view.vendorMatchedRows(), "厂商回了 2 行，只有 1 行在本 classes 文件里");
        assertTrue(view.classesVersion() != null && view.classesVersion().length() == 12);
        JiangyiModelSyncService.DecodeRow first = view.rows().get(0);
        assertEquals("农夫山泉饮用天然水550ml", first.stdSkuName());
        assertEquals("6921168596348", first.vendorBarcode());
        assertEquals(List.of("SKU-1"), first.localCandidates().stream()
                .map(JiangyiModelSyncService.SkuCandidate::skuId).toList());
        assertEquals("SKU-1", first.mappedSkuId());
        assertEquals("农夫山泉 550ml", first.mappedSkuName());
        assertEquals("ACTIVE", first.mappingStatus());
        JiangyiModelSyncService.DecodeRow second = view.rows().get(1);
        assertNull(second.stdSkuName(), "厂商对不上号的行必须显式留空，不能拿别人的行凑");
        assertTrue(second.localCandidates().isEmpty());
        assertNull(second.mappingStatus());
        verify(jiangyiClassMappingMapper, org.mockito.Mockito.never())
                .upsert(any(com.aicabinet.trade.domain.JiangyiClassMapping.class));
        verify(deploymentMapper, org.mockito.Mockito.never()).insert(any(JiangyiModelDeployment.class));
    }

    /** 已挂 SKU 不在条码候选里时仍要回品名（否则「当前挂接」列只有 id）。 */
    @Test
    void decode_namesMappedSkuEvenWhenItIsNotABarcodeCandidate() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl))
                .thenReturn(List.of(vendorRow(101L, "厂商名", "农夫山泉 550ml", "6921168596348", 0)));
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of());
        when(jiangyiClassMappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(0, "SKU-9", "DISABLED")));
        when(skuCatalogMapper.findAllById(any())).thenReturn(List.of(sku("SKU-9", "人工挂的商品", "6901")));

        var row = service().decodeVendorSkuByClasses(DEVICE_ID, classesUrl, 2, 0).rows().get(0);

        assertEquals("人工挂的商品", row.mappedSkuName());
        assertTrue(row.localCandidates().isEmpty(), "厂商条码在本平台无同条码商品 → 候选为空，靠人工挂接");
    }

    /**
     * classIdBase 旋钮拨到 1 时合并仍按 textName 成立（按 classId 合并会整体错行且看不出来）；
     * 厂商 classId 原样回显，与本行 classId 不一致 = 肉眼可见的基址冲突。
     */
    @Test
    void decode_keepsTextNameMergeWhenClassIdBaseDiffersFromVendor() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl))
                .thenReturn(List.of(vendorRow(101L, "农夫山泉饮用天然水550ml", "农夫山泉 550ml", "6921168596348", 0)));
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of(
                sku("SKU-1", "农夫山泉 550ml", "6921168596348")));
        when(jiangyiClassMappingMapper.listByDevice(DEVICE_ID))
                .thenReturn(List.of(mapping(0, "SKU-1", "ACTIVE")));

        var row = service().decodeVendorSkuByClasses(DEVICE_ID, classesUrl, 2, 1).rows().get(0);

        assertEquals(1, row.classId());
        assertEquals(0, row.vendorClassId());
        assertEquals("农夫山泉饮用天然水550ml", row.stdSkuName(), "基址不同也要正确合并，不能整表错位");
        assertNull(row.mappedSkuId(), "映射按 classId 取：基址改了就该显示未挂接，而不是冒充已挂");
    }

    @Test
    void decode_rejectsOffAllowlistClassesHostBeforeCallingVendor() {
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().decodeVendorSkuByClasses(DEVICE_ID, "https://evil.example.com/c.txt", 2, 0));
        assertEquals(400, e.getStatusCode().value());
        verify(jiangyiGatherClient, org.mockito.Mockito.never()).stdSkuByTextNames(anyString());
    }

    /** 行数≠quantity 同样拒绝解码显示：拿错文件翻译出来的对照比不翻译更危险。 */
    @Test
    void decode_rejectsQuantityMismatchingClassesLines() {
        assertThrows(JiangyiClassesParser.ClassesParseRejectException.class,
                () -> service().decodeVendorSkuByClasses(DEVICE_ID, classesUrl, 3, 0));
    }

    /** quantity=0 只做翻译（后台「先看看这文件是什么」），此时不校验行数。 */
    @Test
    void decode_withZeroQuantitySkipsLineCountCheck() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl)).thenReturn(List.of());
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of());
        when(jiangyiClassMappingMapper.listByDevice(DEVICE_ID)).thenReturn(List.of());

        assertEquals(2, service().decodeVendorSkuByClasses(DEVICE_ID, classesUrl, 0, 0).classesLines());
    }

    // ---------- 自动挂接判定（条码唯一命中，H-27 自动档） ----------

    @Test
    void uniqueBarcodeSkuMatches_returnsOnlyUnambiguousOnSaleSku() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl)).thenReturn(List.of(
                vendorRow(101L, "可口可乐 330", "kkkl_jdmw_pttz_330ml", "6928804010145", 26),
                vendorRow(102L, "雪碧 330", "kkkl_xb_qsnmw_pttz_330ml", " 6954767430386 ", 29),
                vendorRow(103L, "没条码的新货", "new_item_no_barcode", "  ", 30)));
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of(
                sku("100016", "可口可乐 330ml", "6928804010145"),
                sku("100013", "雪碧 330ml", "6954767430386")));

        var matched = service().uniqueBarcodeSkuMatches(classesUrl, List.of(
                "kkkl_jdmw_pttz_330ml", "kkkl_xb_qsnmw_pttz_330ml", "new_item_no_barcode"));

        assertEquals(java.util.Map.of(
                "kkkl_jdmw_pttz_330ml", "100016",
                "kkkl_xb_qsnmw_pttz_330ml", "100013"), matched, "条码逐字相等（含首尾空格）才算命中");
    }

    @Test
    void uniqueBarcodeSkuMatches_rejectsBarcodeSharedByTwoTextNames() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl)).thenReturn(List.of(
                vendorRow(101L, "A 包装", "a_pack", "690000000001", 0),
                vendorRow(102L, "A 整箱", "a_case", "690000000001", 1)));
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of(
                sku("SKU-1", "A", "690000000001")));

        assertTrue(service().uniqueBarcodeSkuMatches(classesUrl, List.of("a_pack", "a_case")).isEmpty(),
                "一个条码被两个类目认领 ⇒ 挂谁都可能错，留人工");
    }

    @Test
    void uniqueBarcodeSkuMatches_rejectsMultipleOrOffSaleLocalSku() {
        when(jiangyiGatherClient.stdSkuByTextNames(classesUrl)).thenReturn(List.of(
                vendorRow(101L, "B", "b_item", "690000000002", 0),
                vendorRow(102L, "C", "c_item", "690000000003", 1)));
        com.aicabinet.trade.domain.SkuCatalog offSale = sku("SKU-3", "C", "690000000003");
        offSale.setStatus("DISABLED");
        when(skuCatalogMapper.findByBarcodes(any())).thenReturn(List.of(
                sku("SKU-1", "B 旧", "690000000002"),
                sku("SKU-2", "B 新", "690000000002"),
                offSale));

        var matched = service().uniqueBarcodeSkuMatches(classesUrl, List.of("b_item", "c_item"));

        assertTrue(matched.isEmpty(), "我方同条码两个 SKU / 命中停售 SKU ⇒ 都不挂");
    }

    @Test
    void uniqueBarcodeSkuMatches_skipsWithoutTextNamesAndDoesNotCallVendor() {
        assertTrue(service().uniqueBarcodeSkuMatches(classesUrl, List.of()).isEmpty());
        org.mockito.Mockito.verify(jiangyiGatherClient, org.mockito.Mockito.never())
                .stdSkuByTextNames(anyString());
    }
}
