package org.example.back.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UserSaveDTO {

    @NotBlank(message = "用户名不能为空")
    private String username;

    @NotBlank(message = "真实姓名不能为空")
    private String realName;

    @NotBlank(message = "角色不能为空")
    private String role;

    private Long deptId;

    private Integer status;

    private String phone;

    private String email;

    /**
     * 初始密码（可选）：填写时须符合密码规则，不填使用默认密码
     */
    private String password;
}