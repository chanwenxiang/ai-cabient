package com.aicabinet.trade.recognition;

/**
 * 识别结果里的单个商品行。旧云识别链退役后（2026-10-11），本记录是
 * 将邑归一化结果进结算管道的统一载体（由 {@code RecognitionResult} 持有）。
 */
public record RecognizedItem(String skuId, int quantity, float confidence) {}
