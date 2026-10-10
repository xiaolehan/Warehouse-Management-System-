package org.example.back.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.UserSaveDTO;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysEmployee;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysEmployeeMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.BatchDeleteResultVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserManageServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysDeptMapper sysDeptMapper;

    @Mock
    private SysEmployeeMapper sysEmployeeMapper;

    @Mock
    private AuthzService authzService;

    @Mock
    private MessageService messageService;

    @InjectMocks
    private UserManageService userManageService;

    @Test
    void create_shouldCreateEmployeeProfileWhenTargetRoleIsEmployee() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("employee_from_user_page");
        dto.setRealName("用户页员工");
        dto.setRole("employee");
        dto.setDeptId(4L);
        dto.setStatus(1);
        dto.setPhone("13800000011");
        dto.setEmail("employee_from_user_page@test.com");

        LoginResponse.UserInfoVO operator = new LoginResponse.UserInfoVO();
        operator.setRole("superadmin");

        SysDept dept = new SysDept();
        dept.setId(4L);
        dept.setDeptCode("sales");

        when(authzService.currentUser()).thenReturn(operator);
        when(authzService.isAdmin()).thenReturn(false);
        when(authzService.isSuperAdmin()).thenReturn(true);
        when(authzService.currentOperatorLabel()).thenReturn("超级管理员");
        when(authzService.requireDept(4L)).thenReturn(dept);
        when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(77L);
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));

        userManageService.create(dto);

        ArgumentCaptor<SysEmployee> employeeCaptor = ArgumentCaptor.forClass(SysEmployee.class);
        verify(sysEmployeeMapper, times(1)).insert(employeeCaptor.capture());
        SysEmployee savedEmployee = employeeCaptor.getValue();
        assertEquals(77L, savedEmployee.getUserId());
        assertEquals("用户页员工", savedEmployee.getEmpName());
        assertEquals(4L, savedEmployee.getDeptId());
        assertEquals("普通员工", savedEmployee.getPosition());

        // 不填初始密码时落库为默认密码 123456（BCrypt 可验）
        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, times(1)).insert(userCaptor.capture());
        assertTrue(BCrypt.checkpw("123456", userCaptor.getValue().getPassword()),
                "不填初始密码时默认密码 123456 应可登录");

        verify(messageService, times(1)).sendNewEmployeePasswordReminder("用户页员工", 4L, "超级管理员");
    }

    @Test
    void delete_shouldDeleteLinkedEmployeeProfileForEmployeeUser() {
        SysUser user = new SysUser();
        user.setId(77L);
        user.setRealName("待删除员工");
        user.setRole("employee");
        user.setDeptId(4L);

        when(sysUserMapper.selectById(77L)).thenReturn(user);
        when(authzService.isSuperAdmin()).thenReturn(true);
        when(authzService.currentOperatorLabel()).thenReturn("超级管理员");
        when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });

        userManageService.delete(77L);

        verify(sysEmployeeMapper, times(1)).delete(any());
        verify(sysUserMapper, times(1)).deleteById(77L);
        verify(messageService, times(1)).sendEmployeeDeletedReminder("待删除员工", 4L, "超级管理员");
    }

    // ---------- 手测问题 1（2026-09-23）：批量删除（尽力而为聚合） ----------

    @Test
    void batchDelete_superAdminRowBlocked_employeeRowDeleted() {
        SysUser superRow = new SysUser();
        superRow.setId(1L);
        superRow.setRealName("超管");
        superRow.setRole("superadmin");
        when(sysUserMapper.selectById(1L)).thenReturn(superRow);

        SysUser empRow = new SysUser();
        empRow.setId(77L);
        empRow.setRealName("待删除员工");
        empRow.setRole("employee");
        empRow.setDeptId(4L);
        when(sysUserMapper.selectById(77L)).thenReturn(empRow);

        when(authzService.isSuperAdmin()).thenReturn(true);
        when(authzService.currentOperatorLabel()).thenReturn("超级管理员");
        when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });

        BatchDeleteResultVO result = userManageService.batchDelete(java.util.List.of(1L, 77L));

        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getFailureCount());
        assertEquals(1L, result.getFailures().get(0).getId());
        assertTrue(result.getFailures().get(0).getReason().contains("超级管理员账号不允许删除"),
                "实际: " + result.getFailures().get(0).getReason());
        verify(sysUserMapper, never()).deleteById(1L);
        verify(sysUserMapper).deleteById(77L);
    }

    @Test
    void create_shouldRejectHrAdminAcrossDepartmentsForEmployeeUsers() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("employee_cross_dept");
        dto.setRealName("跨部门员工");
        dto.setRole("employee");
        dto.setDeptId(5L);
        dto.setStatus(1);

        LoginResponse.UserInfoVO operator = new LoginResponse.UserInfoVO();
        operator.setRole("admin");
        operator.setDeptId(2L);
        operator.setDeptCode("hr");

        SysDept dept = new SysDept();
        dept.setId(5L);
        dept.setDeptCode("purchase");

        when(authzService.currentUser()).thenReturn(operator);
        when(authzService.isAdmin()).thenReturn(true);
        when(authzService.requireDept(5L)).thenReturn(dept);
        when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });
        when(authzService.normalizeDeptCode(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });

        assertThrows(BusinessException.class, () -> userManageService.create(dto));

        verify(sysUserMapper, never()).insert(any(SysUser.class));
    }

    @Test
    void resetPassword_shouldRejectWeakPassword() {
        BusinessException ex = assertThrows(BusinessException.class, () -> userManageService.resetPassword(77L, "12345678"));

        assertEquals(400, ex.getCode());
        assertEquals("新密码长度为8–20位，须同时包含字母和数字", ex.getMsg());
        verify(sysUserMapper, never()).updateById(any(SysUser.class));
    }

    // ---------- 会话 72（ADR-0024）：初始密码语义 + 重置密码会话失效 ----------

    private SysUser targetUser() {
        SysUser user = new SysUser();
        user.setId(77L);
        user.setRealName("被重置员工");
        user.setRole("employee");
        user.setDeptId(4L);
        return user;
    }

    @Test
    void create_shouldHashCustomInitialPassword() {
        UserSaveDTO dto = new UserSaveDTO();
        dto.setUsername("custom_pwd_user");
        dto.setRealName("自定义密码员工");
        dto.setRole("employee");
        dto.setDeptId(4L);
        dto.setStatus(1);
        dto.setPassword("abc11122");

        LoginResponse.UserInfoVO operator = new LoginResponse.UserInfoVO();
        operator.setRole("superadmin");

        SysDept dept = new SysDept();
        dept.setId(4L);
        dept.setDeptCode("sales");

        when(authzService.currentUser()).thenReturn(operator);
        when(authzService.isAdmin()).thenReturn(false);
        when(authzService.isSuperAdmin()).thenReturn(true);
        when(authzService.currentOperatorLabel()).thenReturn("超级管理员");
        when(authzService.requireDept(4L)).thenReturn(dept);
        when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
            Object value = invocation.getArgument(0);
            return value == null ? "" : String.valueOf(value).trim().toLowerCase();
        });
        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(77L);
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));

        userManageService.create(dto);

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, times(1)).insert(userCaptor.capture());
        assertTrue(BCrypt.checkpw("abc11122", userCaptor.getValue().getPassword()),
                "填写初始密码时应按规则校验并哈希落库");
    }

    @Test
    void resetPassword_shouldUpdateHashAndInvalidateTargetSessions() {
        SysUser operator = new SysUser();
        operator.setId(11L);
        operator.setRole("superadmin");

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("11");
            when(sysUserMapper.selectById(11L)).thenReturn(operator);
            when(sysUserMapper.selectById(77L)).thenReturn(targetUser());
            when(authzService.isSuperAdmin()).thenReturn(true);
            when(authzService.currentOperatorLabel()).thenReturn("超级管理员");
            when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
                Object value = invocation.getArgument(0);
                return value == null ? "" : String.valueOf(value).trim().toLowerCase();
            });

            userManageService.resetPassword(77L, "abc11122");

            ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
            verify(sysUserMapper, times(1)).updateById(captor.capture());
            SysUser saved = captor.getValue();
            assertTrue(BCrypt.checkpw("abc11122", saved.getPassword()));
            stp.verify(() -> StpUtil.logout(77L));
            verify(messageService, times(1)).sendEmployeePasswordChangedReminder("被重置员工", 4L, "超级管理员");
        }
    }

    @Test
    void resetPassword_shouldRejectAdminCrossDeptTarget() {
        SysUser operator = new SysUser();
        operator.setId(11L);
        operator.setRole("admin");

        SysUser target = targetUser();
        target.setDeptId(5L);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("11");
            when(sysUserMapper.selectById(11L)).thenReturn(operator);
            when(sysUserMapper.selectById(77L)).thenReturn(target);
            when(authzService.isSuperAdmin()).thenReturn(false);
            when(authzService.normalizeRole(any())).thenAnswer(invocation -> {
                Object value = invocation.getArgument(0);
                return value == null ? "" : String.valueOf(value).trim().toLowerCase();
            });

            doThrow(BusinessException.forbidden("部门管理员仅可操作本部门员工账号"))
                    .when(authzService).requireCurrentDept(eq(5L), anyString());

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> userManageService.resetPassword(77L, "abc11122"));

            assertEquals(403, ex.getCode());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }
}