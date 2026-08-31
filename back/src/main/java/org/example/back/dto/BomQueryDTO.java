package org.example.back.dto;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class BomQueryDTO extends PageQuery {

    /** BOM 编码模糊匹配 */
    private String bomCode;

    /** 成品名称模糊匹配 */
    private String goodsName;
}