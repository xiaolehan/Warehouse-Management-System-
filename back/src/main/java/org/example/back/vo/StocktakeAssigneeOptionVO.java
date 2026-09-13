package org.example.back.vo;

import lombok.Data;

/**
 * 负责人候选项（D85：仓储部门启用成员，admin+员工）
 */
@Data
public class StocktakeAssigneeOptionVO {

    private Long userId;

    private String realName;

    /**
     * admin / employee（前端可标注角色）
     */
    private String role;
}
