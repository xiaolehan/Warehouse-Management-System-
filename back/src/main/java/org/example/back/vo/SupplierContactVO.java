package org.example.back.vo;

import lombok.Data;

@Data
public class SupplierContactVO {

    private Long id;

    private String contactPerson;

    private String contactPhone;

    private String position;

    private Integer isDefault;
}