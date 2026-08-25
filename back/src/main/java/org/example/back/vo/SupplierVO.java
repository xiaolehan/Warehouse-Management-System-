package org.example.back.vo;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SupplierVO {

    private Long id;

    private String supplierCode;

    private String supplierName;

    /** 主联系人姓名（列表展示用，取自 is_default=1 或第一条） */
    private String contact;

    /** 主联系人职务 */
    private String position;

    /** 主联系人电话 */
    private String phone;

    private String address;

    private Integer status;

    private String description;

    private LocalDateTime createTime;

    private List<SupplierContactVO> contacts;
}