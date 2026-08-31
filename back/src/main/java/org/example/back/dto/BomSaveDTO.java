package org.example.back.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class BomSaveDTO {

    @NotBlank(message = "BOM 编码不能为空")
    private String bomCode;

    /** 成品 goods_id（type=product） */
    @NotNull(message = "成品不能为空")
    private Long goodsId;

    private String remark;

    @NotNull(message = "BOM 明细不能为空")
    @Size(min = 1, message = "至少添加一条 BOM 明细")
    @Valid
    private List<BomDetailDTO> details;
}