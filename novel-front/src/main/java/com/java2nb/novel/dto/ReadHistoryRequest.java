package com.java2nb.novel.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ReadHistoryRequest {
    @NotNull
    @Positive
    private Long bookId;

    @NotNull
    @Positive
    private Long preContentId;
}
