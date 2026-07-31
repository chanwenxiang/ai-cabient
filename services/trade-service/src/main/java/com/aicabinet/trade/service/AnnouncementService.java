package com.aicabinet.trade.service;

import com.aicabinet.common.dto.AnnouncementDto;
import com.aicabinet.trade.domain.Announcement;
import com.aicabinet.trade.mapper.AnnouncementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);
    private static final Set<String> AUDIENCES = Set.of("CONSUMER", "MERCHANT");

    private final AnnouncementMapper repository;

    public AnnouncementService(AnnouncementMapper repository) {
        this.repository = repository;
    }

    public List<Announcement> listAll() {
        return repository.findAll();
    }

    public List<Announcement> listPublished() {
        return repository.findByStatusOrderByPublishAtDesc("PUBLISHED");
    }

    public List<Announcement> listByScope(String scope) {
        return repository.findByTargetScopeAndStatusOrderByPublishAtDesc(scope, "PUBLISHED");
    }

    public List<AnnouncementDto> listPublishedForAudience(String audience) {
        String scope = normalizeAudience(audience);
        Instant now = Instant.now();
        return repository.findPublishedForAudience(scope, now).stream()
                .sorted(audienceComparator())
                .map(this::toDto)
                .toList();
    }

    public AnnouncementDto getPublishedForAudience(Long announceId, String audience) {
        String scope = normalizeAudience(audience);
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        if (!"PUBLISHED".equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在");
        }
        String target = a.getTargetScope() != null ? a.getTargetScope() : "ALL";
        if (!"ALL".equals(target) && !scope.equals(target)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在");
        }
        if (a.getExpireAt() != null && !a.getExpireAt().isAfter(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "公告已过期");
        }
        return toDto(a);
    }

    private String normalizeAudience(String audience) {
        String scope = audience == null ? "" : audience.trim().toUpperCase(Locale.ROOT);
        if (!AUDIENCES.contains(scope)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的公告受众");
        }
        return scope;
    }

    private Comparator<Announcement> audienceComparator() {
        return Comparator
                .comparingInt((Announcement a) -> priorityRank(a.getPriority()))
                .thenComparing(Announcement::getPublishAt, Comparator.nullsLast(Comparator.reverseOrder()));
    }

    private int priorityRank(String priority) {
        if (priority == null) return 3;
        return switch (priority.toUpperCase(Locale.ROOT)) {
            case "URGENT" -> 0;
            case "HIGH" -> 1;
            case "NORMAL" -> 2;
            case "LOW" -> 3;
            default -> 3;
        };
    }

    private AnnouncementDto toDto(Announcement a) {
        return new AnnouncementDto(
                a.getAnnounceId(),
                a.getTitle(),
                a.getContent(),
                a.getAnnounceType(),
                a.getTargetScope(),
                a.getPriority(),
                a.getPublishAt(),
                a.getExpireAt()
        );
    }

    @Transactional
    public Announcement create(String title, String content, String targetScope, String priority, Long operatorId) {
        Announcement a = new Announcement();
        a.setTitle(title);
        a.setContent(content);
        a.setAnnounceType("SYSTEM");
        a.setTargetScope(targetScope != null ? targetScope : "ALL");
        a.setPriority(priority != null ? priority : "NORMAL");
        a.setStatus("DRAFT");
        a.setOperatorId(operatorId);
        repository.save(a);
        log.info("announcement created id={} title={}", a.getAnnounceId(), title);
        return a;
    }

    @Transactional
    public Announcement update(Long announceId, String title, String content, String targetScope, String priority) {
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        if ("ARCHIVED".equals(a.getStatus())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "已归档公告不可编辑");
        }
        if (title == null || title.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "标题不能为空");
        }
        if (content == null || content.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "内容不能为空");
        }
        a.setTitle(title.trim());
        a.setContent(content.trim());
        if (targetScope != null && !targetScope.isBlank()) {
            a.setTargetScope(targetScope.trim());
        }
        if (priority != null && !priority.isBlank()) {
            a.setPriority(priority.trim());
        }
        a.setUpdatedAt(Instant.now());
        repository.save(a);
        log.info("announcement updated id={} title={}", announceId, a.getTitle());
        return a;
    }

    @Transactional
    public Announcement publish(Long announceId) {
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        a.setStatus("PUBLISHED");
        a.setPublishAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        repository.save(a);
        log.info("announcement published id={} title={}", announceId, a.getTitle());
        return a;
    }

    @Transactional
    public Announcement archive(Long announceId) {
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        a.setStatus("ARCHIVED");
        a.setUpdatedAt(Instant.now());
        repository.save(a);
        return a;
    }
}
