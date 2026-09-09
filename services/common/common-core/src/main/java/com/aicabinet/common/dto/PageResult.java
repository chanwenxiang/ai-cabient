package com.aicabinet.common.dto;

import com.fasterxml.jackson.annotation.JsonView;

import java.util.List;

public record PageResult<T>(
        @JsonView(OrderViews.Public.class) List<T> items,
        @JsonView(OrderViews.Public.class) int page,
        @JsonView(OrderViews.Public.class) int size,
        @JsonView(OrderViews.Public.class) long total
) {}
