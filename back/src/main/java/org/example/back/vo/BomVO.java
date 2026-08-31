package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class BomVO {

    private Long id;

    /** BOM 编码 */
    private String bomCode;

    /** 成品 goods_id(type=product) */
    private Long goodsId;

    /** 成品名称(冗余) */
    private String goodsName;

    /** 成品单位 */
    private String goodsUnit;

    private String remark;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    private Integer isDeleted;

    /** 明细列表 */
    private List<BomDetailVO> details;
}