package org.example.back.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * D71：生产手工修正预计完工时间。传 null 视为清除手工值（恢复系统推算）。
 */
@Data
public class ExpectedCompletionDTO {

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime expectedCompletionTime;
}
