package com.aicabinet.trade.architecture;

import com.aicabinet.trade.auth.RequiresPermissions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 防回潮：{@code /api/v2/ops/**} 下每个 HTTP 处理器都必须「表态」——
 * 要么带 {@link RequiresPermissions}（方法级或类级），要么落进下面带理由的豁免清单。
 *
 * <p>背景（2026-10-29 实测）：{@code PermissionAspect} 只在**有注解时**生效，
 * {@code AuthInterceptor} 只校验 JWT 有效。所以一个运营端点若既无注解、服务层也没有
 * {@code requireOperator}/授权调用，任何已登录账号（含消费者）都能打通：
 * <ul>
 *   <li>{@code PUT /api/v2/ops/admin/rbac/me/password} 消费者 JWT 实测回 400「原密码错误」
 *       ——已进入业务逻辑，不是 403；</li>
 *   <li>{@code POST /api/v2/ops/admin/rbac/me/avatar} 实测回 500（缺 multipart 才失败），
 *       同样没有被运营身份拦住；</li>
 *   <li>{@code POST /warehouse/stocktakes/{id}/adjust} 只靠服务层授权，漏了注解
 *       （同文件的兄弟端点都有）——运行时安全，但口径不一致，评审看不出来。</li>
 * </ul>
 *
 * <p>豁免清单只允许「个人中心 / 纯参照数据 / 权限码必须运行时分域」三类；新增前先想清楚
 * 为什么不能绑定一个静态权限码，并把理由写进清单。
 */
class OpsEndpointPermissionTest {

    private static final String OPS_PREFIX = "/api/v2/ops";

    /**
     * 豁免清单：键 = {@code ControllerSimpleName#methodName}，值 = 为什么不能/不必绑功能权限码。
     * 只列**当前真的没有** {@code @RequiresPermissions} 的处理器——加了注解就要把这里删掉。
     */
    private static final Map<String, String> EXEMPT = new LinkedHashMap<>();

    static {
        // 个人中心 / 双因子：自助操作，绑功能码会把没角色的运营账号锁死；服务层已 requireOperator
        EXEMPT.put("OpsRbacController#twoFactorStatus", "个人中心自助；OpsTwoFactorService 校验运营号段");
        EXEMPT.put("OpsRbacController#enrollTwoFactor", "个人中心自助；OpsTwoFactorService 校验运营号段");
        EXEMPT.put("OpsRbacController#confirmTwoFactor", "个人中心自助；OpsTwoFactorService 校验运营号段");
        EXEMPT.put("OpsRbacController#disableTwoFactor", "个人中心自助；OpsTwoFactorService 校验运营号段");
        EXEMPT.put("OpsRbacController#myPermissions", "返回当前登录人自己的权限集；服务层 requireOperator");
        EXEMPT.put("OpsRbacController#myActiveNav", "返回当前登录人自己的菜单；服务层 requireOperator");
        EXEMPT.put("OpsRbacController#myProfile", "个人中心自助；服务层 requireOperator");
        EXEMPT.put("OpsRbacController#updateMyProfile", "个人中心自助；服务层 requireOperator");
        EXEMPT.put("OpsRbacController#sendPhoneChangeSmsCode", "个人中心换绑；服务层 requireOperator");
        EXEMPT.put("OpsRbacController#uploadMyAvatar", "个人中心上传；FileAttachmentService#uploadOpsAvatar 校验运营账号");
        EXEMPT.put("OpsRbacController#changeMyPassword", "个人中心改密；OpsRbacService#changeMyPassword 校验运营账号");
        // 纯参照数据：行政区划，跨设备/仓库/场地多个权限域的表单共用，绑任何一个都会锁死另外几处
        EXEMPT.put("OpsGapFeaturesController#districts", "纯参照数据（行政区划名+adcode），跨权限域共用");
        // 权限码必须按 ownerType 运行时分域，静态注解表达不了
        EXEMPT.put("OpsPayoutAccountController#create", "权限码按 ownerType 分域（requireOwnerPermission）");
        EXEMPT.put("OpsPayoutAccountController#setDefault", "权限码按 ownerType 分域（requireOwnerPermission）");
        EXEMPT.put("OpsPayoutAccountController#disable", "权限码按 ownerType 分域（requireOwnerPermission）");
    }

    @Test
    void everyOpsHandlerMustDeclarePermissionOrBeExempt() {
        List<String> violations = new ArrayList<>();
        TreeSet<String> seen = new TreeSet<>();

        for (Class<?> controller : scanRestControllers()) {
            for (String base : pathsOf(controller)) {
                for (Method method : controller.getDeclaredMethods()) {
                    RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                    if (mapping == null) continue;
                    if (!isOpsEndpoint(base, mapping)) continue;

                    String key = controller.getSimpleName() + "#" + method.getName();
                    seen.add(key);
                    boolean declared = AnnotatedElementUtils.hasAnnotation(method, RequiresPermissions.class)
                            || AnnotatedElementUtils.hasAnnotation(controller, RequiresPermissions.class);
                    if (!declared && !EXEMPT.containsKey(key)) {
                        String sub = mapping.path().length == 0 ? "" : mapping.path()[0];
                        violations.add(key + " → " + joinPaths(base, sub));
                    }
                }
            }
        }

        assertTrue(seen.size() > 100,
                "只扫到 " + seen.size() + " 个 ops 处理器，锚点可能已失效（至少应有 100+）");
        assertTrue(violations.isEmpty(),
                "以下 /api/v2/ops 处理器既没有 @RequiresPermissions，也不在豁免清单里"
                        + "（任何登录账号都能打通，含消费者 JWT）：\n  " + String.join("\n  ", violations));

        List<String> stale = EXEMPT.keySet().stream().filter((k) -> !seen.contains(k)).toList();
        assertEquals(List.of(), stale,
                "豁免清单里有不存在（或已加注解/已改名）的条目，请删掉：" + stale);
    }

    private static List<Class<?>> scanRestControllers() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> classes = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.aicabinet.trade")) {
            try {
                classes.add(Class.forName(bd.getBeanClassName()));
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("无法加载 " + bd.getBeanClassName(), e);
            }
        }
        return classes;
    }

    private static List<String> pathsOf(Class<?> controller) {
        RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
        if (mapping == null || mapping.path().length == 0) return List.of("");
        return List.of(mapping.path());
    }

    private static boolean isOpsEndpoint(String base, RequestMapping mapping) {
        if (mapping.path().length == 0) return isOpsPath(joinPaths(base, ""));
        for (String sub : mapping.path()) {
            if (isOpsPath(joinPaths(base, sub))) return true;
        }
        return false;
    }

    private static String joinPaths(String base, String sub) {
        String b = base == null ? "" : base.replaceAll("/+$", "");
        String s = sub == null ? "" : sub;
        if (!s.startsWith("/")) s = "/" + s;
        return b + s;
    }

    private static boolean isOpsPath(String path) {
        return path.equals(OPS_PREFIX) || path.startsWith(OPS_PREFIX + "/");
    }
}
