package com.aicabinet.trade.service;

import com.aicabinet.common.dto.SystemConfigDto;
import com.aicabinet.common.dto.SystemConfigHistoryDto;
import com.aicabinet.common.util.PhoneMask;
import com.aicabinet.trade.domain.SystemConfigHistory;
import com.aicabinet.trade.domain.UserInfo;
import com.aicabinet.trade.mapper.SystemConfigHistoryMapper;
import com.aicabinet.trade.mapper.UserInfoMapper;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 系统配置的「策略版本」读侧与回滚（F1 动态定价 · 策略版本与审计）。
 *
 * <p>与 {@link SystemConfigService} 的分工：写路径（在 upsert/delete 里留痕）归
 * {@code SystemConfigService}（它才持有配置锁与当前值）；查询历史与回滚是两个独立的运营动作，
 * 拆出来避免把「配置读写的核心服务」再撑大。
 *
 * <p><b>读侧不受 {@code ops.config.audit.enabled} 影响</b>：关掉开关只是不再新增版本，
 * 已记录的历史仍可查、仍可回滚。开关只决定「写不写」，不决定「看不看」。
 */
@Service
public class SystemConfigAuditService {

    /** 单次返回的历史条数上限（配置变更是低频人工操作，200 条足够覆盖数年）。 */
    static final int HISTORY_LIMIT = 200;

    private final PermissionService permissionService;
    private final SystemConfigHistoryMapper historyRepository;
    private final UserInfoMapper userInfoRepository;
    private final SystemConfigService systemConfigService;

    public SystemConfigAuditService(PermissionService permissionService,
                                    SystemConfigHistoryMapper historyRepository,
                                    UserInfoMapper userInfoRepository,
                                    SystemConfigService systemConfigService) {
        this.permissionService = permissionService;
        this.historyRepository = historyRepository;
        this.userInfoRepository = userInfoRepository;
        this.systemConfigService = systemConfigService;
    }

    /** 某配置键的版本序列（最新在前）。权限与配置列表一致（能看配置就能看它的历史）。 */
    @Transactional(readOnly = true)
    public List<SystemConfigHistoryDto> listHistory(Long operatorId, String configKey) {
        permissionService.requirePermission(operatorId, "ops:config:list");
        if (configKey == null || configKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置键不能为空");
        }
        String key = configKey.trim();
        return enrich(historyRepository.findByConfigKeyOrderByCreatedAtDesc(key, HISTORY_LIMIT));
    }

    /**
     * 回滚到指定历史版本的<b>变更前值</b>。
     *
     * <p>语义是「把该键设回第 N 版之前的值」——用 {@code oldValue} 而不是 {@code newValue}，
     * 因为运营看到「被改坏了」时想撤销的正是那一次变更本身。
     *
     * <p>回滚要改运行期的真实配置，故权限与 {@code upsert} 同级（{@code ops:config:edit}）；
     * 它本身就是一次 upsert ⇒ 会再留一条历史（新值的 oldValue = 被撤销的那一版），
     * 于是「撤销」也可被撤销，链上不会出现断点。描述刻意传 null（只改值、不动描述）。
     *
     * @throws ResponseStatusException 404 历史不存在或不属于该键；400 该版是首次创建（无更早值）
     */
    @Transactional
    public SystemConfigDto rollback(Long operatorId, String configKey, long historyId) {
        permissionService.requirePermission(operatorId, "ops:config:edit");
        if (configKey == null || configKey.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "配置键不能为空");
        }
        String key = configKey.trim();
        SystemConfigHistory history = historyRepository.findById(historyId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "历史版本不存在"));
        if (!key.equals(history.getConfigKey())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "历史版本不属于该配置键");
        }
        if (history.getOldValue() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "该版本是配置首次创建，没有更早的值可回滚");
        }
        return systemConfigService.upsert(key, history.getOldValue(), null, operatorId);
    }

    private List<SystemConfigHistoryDto> enrich(List<SystemConfigHistory> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> operatorIds = rows.stream()
                .map(SystemConfigHistory::getOperatorId)
                .distinct()
                .toList();
        Map<Long, UserInfo> users = userInfoRepository.findByUserIdIn(operatorIds).stream()
                .collect(Collectors.toMap(UserInfo::getUserId, u -> u));
        return rows.stream().map(row -> toDto(row, users.get(row.getOperatorId()))).toList();
    }

    private static SystemConfigHistoryDto toDto(SystemConfigHistory row, UserInfo operator) {
        Long opId = row.getOperatorId();
        String name;
        if (opId == null || opId <= 0L) {
            name = "系统";
        } else if (operator != null && operator.getName() != null && !operator.getName().isBlank()) {
            name = operator.getName();
        } else if (operator != null && operator.getPhoneNumber() != null
                && !operator.getPhoneNumber().isBlank()) {
            name = PhoneMask.mask(operator.getPhoneNumber());
        } else {
            name = "账号 " + opId;
        }
        return new SystemConfigHistoryDto(
                row.getHistoryId(), row.getConfigKey(), row.getOldValue(), row.getNewValue(),
                opId, name, row.getCreatedAt());
    }
}
