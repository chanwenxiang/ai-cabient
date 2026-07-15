package com.aicabinet.trade.service;

import com.aicabinet.trade.domain.Announcement;
import com.aicabinet.trade.mapper.AnnouncementMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

@Service
public class AnnouncementService {

    private static final Logger log = LoggerFactory.getLogger(AnnouncementService.class);

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
    public Announcement publish(Long announceId) {
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        a.setStatus("PUBLISHED");
        a.setPublishAt(Instant.now());
        repository.save(a);
        log.info("announcement published id={} title={}", announceId, a.getTitle());
        return a;
    }

    @Transactional
    public Announcement archive(Long announceId) {
        Announcement a = repository.findById(announceId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "公告不存在"));
        a.setStatus("ARCHIVED");
        repository.save(a);
        return a;
    }
}
