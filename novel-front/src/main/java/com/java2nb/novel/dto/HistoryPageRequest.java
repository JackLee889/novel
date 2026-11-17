package com.java2nb.novel.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class HistoryPageRequest {
    public static final int MAX_LIMIT = 100;

    @NotNull
    @Min(1)
    private Integer curr = 1;

    @NotNull
    @Min(1)
    @Max(MAX_LIMIT)
    private Integer limit = 10;
}
