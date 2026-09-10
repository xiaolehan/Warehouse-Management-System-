package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BomSaveDTO {

    /** BOM 编码：可空。留空时后端自动生成「{成品名称}-BOM」 */
    private String bomCode;

    /** 成品名称（type=product）。一个 BOM 即一种成品，名称即成品身份 */
    @NotBlank(message = "成品名称不能为空")
    private String goodsName;

    /** 成品单位（可空，用于新建成品主档） */
    private String unit;

    /** D71：标准工期（天，选填）；定制新品不知道工期可留空，时间线显示"待生产评估" */
    @Min(value = 1, message = "标准工期至少为1天")
    private Integer leadDays;

    private String remark;

    @NotNull(message = "BOM 明细不能为空")
    @Size(min = 1, message = "至少添加一条 BOM 明细")
    @Valid
    private List<BomDetailDTO> details;
}