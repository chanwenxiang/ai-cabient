package com.aicabinet.trade.service;



import com.aicabinet.common.dto.GravityDeltaRequest;

import com.aicabinet.trade.client.VisionServiceClient;

import com.fasterxml.jackson.core.type.TypeReference;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.slf4j.Logger;

import org.slf4j.LoggerFactory;

import org.springframework.stereotype.Service;



import java.util.ArrayList;

import java.util.LinkedHashMap;

import java.util.List;

import java.util.Map;



@Service

public class GravitySettlementHelper {



    private static final Logger log = LoggerFactory.getLogger(GravitySettlementHelper.class);



    private final ObjectMapper objectMapper;



    public GravitySettlementHelper(ObjectMapper objectMapper) {

        this.objectMapper = objectMapper;

    }



    public String mergeGravityJson(String existingJson, String incomingJson) {

        List<GravityDeltaRequest.GravityDeltaItem> merged = new ArrayList<>(parse(existingJson));

        for (GravityDeltaRequest.GravityDeltaItem delta : parse(incomingJson)) {

            String key = mergeKey(delta);

            boolean found = false;

            for (int i = 0; i < merged.size(); i++) {

                if (mergeKey(merged.get(i)).equals(key)) {

                    GravityDeltaRequest.GravityDeltaItem prev = merged.get(i);

                    merged.set(i, new GravityDeltaRequest.GravityDeltaItem(

                            prev.skuId(), prev.delta() + delta.delta(), prev.slotId()));

                    found = true;

                    break;

                }

            }

            if (!found) {

                merged.add(delta);

            }

        }

        try {

            return objectMapper.writeValueAsString(merged);

        } catch (Exception e) {

            throw new IllegalStateException(e);

        }

    }



    public List<VisionServiceClient.RecognizedItem> toRecognizedItems(String gravityDeltasJson) {

        List<VisionServiceClient.RecognizedItem> items = new ArrayList<>();

        for (GravityDeltaRequest.GravityDeltaItem delta : parse(gravityDeltasJson)) {

            if (delta.delta() >= 0) {

                continue;

            }

            items.add(new VisionServiceClient.RecognizedItem(delta.skuId(), -delta.delta(), 0.98f));

        }

        return items;

    }



    public List<VisionServiceClient.RecognizedItem> mergeWithVision(String gravityDeltasJson,

                                                                    List<VisionServiceClient.RecognizedItem> visionItems) {

        if (visionItems != null && !visionItems.isEmpty()) {

            return visionItems;

        }

        List<VisionServiceClient.RecognizedItem> gravityItems = toRecognizedItems(gravityDeltasJson);

        if (!gravityItems.isEmpty()) {

            log.info("using gravity fallback items={}", gravityItems.size());

        }

        return gravityItems;

    }



    /**

     * 视觉为主；若重力与视觉 SKU 数量不一致，强制人工审核。

     */

    public VisionServiceClient.RecognitionResult reconcileWithGravity(

            String gravityDeltasJson,

            VisionServiceClient.RecognitionResult vision) {

        List<VisionServiceClient.RecognizedItem> gravityItems = toRecognizedItems(gravityDeltasJson);

        List<VisionServiceClient.RecognizedItem> visionItems =

                vision.items() != null ? vision.items() : List.of();



        if (visionItems.isEmpty()) {

            if (gravityItems.isEmpty()) {

                return vision;

            }

            float gravityConf = gravityItems.stream()
                    .map(VisionServiceClient.RecognizedItem::confidence)
                    .max(Float::compare)
                    .orElse(0.98f);
            return new VisionServiceClient.RecognitionResult(
                    vision.taskId(),
                    gravityItems,
                    gravityConf,
                    false,
                    vision.modelVersion() + "+gravity",
                    vision.detectedClasses()
            );

        }



        if (!gravityItems.isEmpty() && !quantitiesMatch(visionItems, gravityItems)) {

            log.warn("vision-gravity mismatch vision={} gravity={}", visionItems, gravityItems);

            return new VisionServiceClient.RecognitionResult(

                    vision.taskId(),

                    visionItems,

                    vision.overallConfidence(),

                    true,

                    vision.modelVersion() + "+gravity-mismatch",

                    vision.detectedClasses()

            );

        }



        return vision;

    }



    public List<GravityDeltaRequest.GravityDeltaItem> parse(String json) {

        if (json == null || json.isBlank()) {

            return List.of();

        }

        try {

            return objectMapper.readValue(json, new TypeReference<>() {});

        } catch (Exception e) {

            log.warn("invalid gravity deltas json: {}", e.getMessage());

            return List.of();

        }

    }



    public String fromRequestItems(List<GravityDeltaRequest.GravityDeltaItem> deltas) {

        Map<String, GravityDeltaRequest.GravityDeltaItem> merged = new LinkedHashMap<>();

        for (GravityDeltaRequest.GravityDeltaItem delta : deltas) {

            String key = mergeKey(delta);

            GravityDeltaRequest.GravityDeltaItem prev = merged.get(key);

            if (prev == null) {

                merged.put(key, delta);

            } else {

                merged.put(key, new GravityDeltaRequest.GravityDeltaItem(

                        prev.skuId(), prev.delta() + delta.delta(), prev.slotId()));

            }

        }

        List<GravityDeltaRequest.GravityDeltaItem> normalized = new ArrayList<>(merged.values());

        try {

            return objectMapper.writeValueAsString(normalized);

        } catch (Exception e) {

            throw new IllegalStateException(e);

        }

    }



    public boolean hasSlotSpecificDeltas(List<GravityDeltaRequest.GravityDeltaItem> deltas) {

        return deltas.stream().anyMatch(d -> d.slotId() != null && !d.slotId().isBlank());

    }



    private static boolean quantitiesMatch(List<VisionServiceClient.RecognizedItem> visionItems,

                                           List<VisionServiceClient.RecognizedItem> gravityItems) {

        Map<String, Integer> visionQty = aggregateBySku(visionItems);

        Map<String, Integer> gravityQty = aggregateBySku(gravityItems);

        return visionQty.equals(gravityQty);

    }



    private static Map<String, Integer> aggregateBySku(List<VisionServiceClient.RecognizedItem> items) {

        Map<String, Integer> qty = new LinkedHashMap<>();

        for (VisionServiceClient.RecognizedItem item : items) {

            qty.merge(item.skuId(), item.quantity(), Integer::sum);

        }

        return qty;

    }



    private static String mergeKey(GravityDeltaRequest.GravityDeltaItem delta) {

        if (delta.slotId() != null && !delta.slotId().isBlank()) {

            return delta.slotId().trim().toUpperCase() + ":" + delta.skuId();

        }

        return delta.skuId();

    }

}


