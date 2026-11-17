package com.java2nb.novel.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ChapterFeedbackRequest {
    @NotNull @Positive
    private Long bookId;

    @NotNull @Positive
    private Long bookIndexId;

    @NotBlank @Pattern(regexp = "typo|missing|duplicate|order|other")
    private String type;

    @Size(max = 200, message = "补充说明最多 200 字")
    private String details;
}
