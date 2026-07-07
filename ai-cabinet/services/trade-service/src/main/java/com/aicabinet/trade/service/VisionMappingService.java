package com.aicabinet.trade.service;

import com.aicabinet.trade.repository.AliyunCategoryMappingRepository;
import com.aicabinet.trade.repository.SkuVisionMappingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class VisionMappingService {

    private final SkuVisionMappingRepository yoloRepository;
    private final AliyunCategoryMappingRepository aliyunRepository;

    public VisionMappingService(SkuVisionMappingRepository yoloRepository,
                                AliyunCategoryMappingRepository aliyunRepository) {
        this.yoloRepository = yoloRepository;
        this.aliyunRepository = aliyunRepository;
    }

    @Transactional(readOnly = true)
    public VisionMappingsDto listMappings() {
        List<YoloMappingDto> yolo = yoloRepository.findAll().stream()
                .map(m -> new YoloMappingDto(m.getClassName(), m.getSkuId(), m.getMinConfidence()))
                .toList();
        List<AliyunMappingDto> aliyun = aliyunRepository.findAll().stream()
                .map(m -> new AliyunMappingDto(
                        m.getCategoryId(), m.getCategoryName(), m.getSkuId(), m.getMinConfidence()))
                .toList();
        return new VisionMappingsDto(yolo, aliyun);
    }

    public record YoloMappingDto(String className, String skuId, float minConfidence) {}

    public record AliyunMappingDto(String categoryId, String categoryName, String skuId, float minConfidence) {}

    public record VisionMappingsDto(List<YoloMappingDto> yolo, List<AliyunMappingDto> aliyun) {}
}
