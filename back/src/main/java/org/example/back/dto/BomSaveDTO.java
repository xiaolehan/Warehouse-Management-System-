package org.example.back.dto;

import jakarta.validation.Valid;
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

    private String remark;

    @NotNull(message = "BOM 明细不能为空")
    @Size(min = 1, message = "至少添加一条 BOM 明细")
    @Valid
    private List<BomDetailDTO> details;
}