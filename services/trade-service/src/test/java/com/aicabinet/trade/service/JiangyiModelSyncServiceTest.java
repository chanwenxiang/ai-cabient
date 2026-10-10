package com.aicabinet.trade.service;

import com.aicabinet.trade.client.JiangyiGatherClient;
import com.aicabinet.trade.client.JiangyiGatewayClient;
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

    private JiangyiModelSyncService service() {
        return new JiangyiModelSyncService(jiangyiGatherClient, jiangyiDeviceMapper,
                jiangyiClassMappingMapper, deploymentMapper, jiangyiGatewayClient);
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
        return new JiangyiModelFile(7L, MODEL_NAME, "http://oss/model.rknn",
                industrialControlModel, classesUrl, 2);
    }

    @Test
    void pushModel_deviceWithoutIndustrialControlModelIsRejected() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice(null));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        verify(deploymentMapper, org.mockito.Mockito.never())
                .insert(any(JiangyiModelDeployment.class));
    }

    @Test
    void pushModel_unknownModelIs404() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(DEVICE_ID, "不存在的模型"));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void pushModel_industrialControlModelMismatchIsRejected() {
        // 机型校验 = 字符串相等（原值 "76"/"88" 不解释——PDF 示例两主板同为 "88"，映射关系文档自证不了）
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        assertTrue(e.getReason().contains("机型不匹配"));
    }

    @Test
    void pushModel_pendingSentDeploymentBlocksAsIdempotent() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("88"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));
        when(deploymentMapper.hasPendingSent(DEVICE_ID)).thenReturn(true);

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pushModel(DEVICE_ID, MODEL_NAME));
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
                () -> service().pushModel(DEVICE_ID, MODEL_NAME));
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

        long deploymentId = service().pushModel(DEVICE_ID, MODEL_NAME);

        assertEquals(56L, deploymentId);
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

    @Test
    void confirmModel_withoutPendingSentIsRejectedAsReplaySuspect() {
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.empty());

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().confirmModel(DEVICE_ID, MODEL_NAME));
        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiDeviceMapper, org.mockito.Mockito.never())
                .updateModelInfo(anyString(), anyString(), anyString(), any());
    }

    @Test
    void confirmModel_fillsDeploymentAndDeviceModelInfo() {
        JiangyiModelDeployment sent = new JiangyiModelDeployment();
        sent.setId(55L);
        sent.setDeviceId(DEVICE_ID);
        sent.setModelName(MODEL_NAME);
        sent.setClassesVersion("ab12cd34ef56");
        when(deploymentMapper.findLatestSent(DEVICE_ID, MODEL_NAME)).thenReturn(Optional.of(sent));

        service().confirmModel(DEVICE_ID, MODEL_NAME);

        verify(deploymentMapper).confirm(eq(55L), eq("ab12cd34ef56"), any());
        verify(jiangyiDeviceMapper).updateModelInfo(eq(DEVICE_ID), eq(MODEL_NAME),
                eq("ab12cd34ef56"), any());
    }

    @Test
    void markPushTimeoutDelegatesToFail() {
        when(deploymentMapper.fail(eq(55L), anyString(), any())).thenReturn(1);
        service().markPushTimeout(55L, "watchdog 600s 超时");
        verify(deploymentMapper).fail(eq(55L), anyString(), any());
    }

    @Test
    void activatePregeneratedRequiresBoundDevice() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(null);
        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().activatePregenerated(DEVICE_ID, MODEL_NAME));
        assertEquals(404, e.getStatusCode().value());
    }

    @Test
    void pregenerate_incompatibleModelRejectedBeforeAnyWrite() {
        when(jiangyiDeviceMapper.selectById(DEVICE_ID)).thenReturn(boundDevice("76"));
        when(jiangyiGatherClient.modelFiles()).thenReturn(List.of(modelFile("88")));

        ResponseStatusException e = assertThrows(ResponseStatusException.class,
                () -> service().pregenerateForDevice(DEVICE_ID, MODEL_NAME, 0));
        assertEquals(409, e.getStatusCode().value());
        verify(jiangyiClassMappingMapper, org.mockito.Mockito.never())
                .upsert(any(com.aicabinet.trade.domain.JiangyiClassMapping.class));
    }

    @Test
    void listDeploymentsDelegates() {
        when(deploymentMapper.listByDevice(DEVICE_ID)).thenReturn(List.of());
        assertTrue(service().listDeployments(DEVICE_ID).isEmpty());
    }
}
