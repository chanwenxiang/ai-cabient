package com.aicabinet.trade.mapper;

import com.aicabinet.trade.domain.FileAttachment;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.apache.ibatis.annotations.Mapper;

import java.util.Collection;
import java.util.List;

@Mapper
public interface FileAttachmentMapper extends BaseTradeMapper<FileAttachment> {

    default List<FileAttachment> findByRef(String refType, String refId) {
        return selectList(Wrappers.<FileAttachment>lambdaQuery()
                .eq(FileAttachment::getRefType, refType)
                .eq(FileAttachment::getRefId, refId)
                .orderByAsc(FileAttachment::getFileId));
    }

    default List<FileAttachment> findByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return selectList(Wrappers.<FileAttachment>lambdaQuery()
                .in(FileAttachment::getFileId, ids)
                .orderByAsc(FileAttachment::getFileId));
    }

    default List<FileAttachment> findByContentSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<FileAttachment>lambdaQuery()
                .eq(FileAttachment::getContentSha256, sha256)
                .orderByAsc(FileAttachment::getFileId));
    }

    default List<FileAttachment> findByStoragePath(String storagePath) {
        if (storagePath == null || storagePath.isBlank()) {
            return List.of();
        }
        return selectList(Wrappers.<FileAttachment>lambdaQuery()
                .eq(FileAttachment::getStoragePath, storagePath));
    }
}
