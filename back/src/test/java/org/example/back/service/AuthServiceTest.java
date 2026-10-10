package org.example.back.service;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.crypto.digest.BCrypt;
import org.example.back.dto.ChangePasswordDTO;
import org.example.back.dto.RegisterRequest;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysEmployee;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysEmployeeMapper;
import org.example.back.mapper.SysUserMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @Mock
    private SysDeptMapper sysDeptMapper;

    @Mock
    private SysEmployeeMapper sysEmployeeMapper;

    @InjectMocks
    private AuthService authService;

    @Test
    void register_shouldCreateUserAndEmployeeProfile() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("new_employee");
        request.setPassword("abc12345");
        request.setRealName("新员工");
        request.setDeptId(2L);

        SysDept dept = new SysDept();
        dept.setId(2L);
        dept.setDeptCode("sales");
        dept.setStatus(2);

        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(sysDeptMapper.selectById(2L)).thenReturn(dept);
        doAnswer(invocation -> {
            SysUser user = invocation.getArgument(0);
            user.setId(100L);
            return 1;
        }).when(sysUserMapper).insert(any(SysUser.class));

        authService.register(request);

        ArgumentCaptor<SysUser> userCaptor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper, times(1)).insert(userCaptor.capture());
        SysUser savedUser = userCaptor.getValue();
        assertEquals("new_employee", savedUser.getUsername());
        assertEquals("新员工", savedUser.getRealName());
        assertEquals("employee", savedUser.getRole());
        assertEquals(2L, savedUser.getDeptId());
        assertEquals(1, savedUser.getStatus());
        assertTrue(BCrypt.checkpw("abc12345", savedUser.getPassword()));

        ArgumentCaptor<SysEmployee> employeeCaptor = ArgumentCaptor.forClass(SysEmployee.class);
        verify(sysEmployeeMapper, times(1)).insert(employeeCaptor.capture());
        SysEmployee savedEmployee = employeeCaptor.getValue();
        assertEquals(100L, savedEmployee.getUserId());
        assertNotNull(savedEmployee.getEmpCode());
        assertTrue(savedEmployee.getEmpCode().startsWith("EMP"));
        assertEquals("新员工", savedEmployee.getEmpName());
        assertEquals(2L, savedEmployee.getDeptId());
        assertEquals("普通员工", savedEmployee.getPosition());
        assertEquals(1, savedEmployee.getStatus());
    }

    @Test
    void register_shouldRejectNonApprovedDept() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("pending_user");
        request.setPassword("abc12345");
        request.setRealName("待审批员工");
        request.setDeptId(9L);

        SysDept dept = new SysDept();
        dept.setId(9L);
        dept.setDeptCode("pending_dept");
        dept.setStatus(1);

        when(sysUserMapper.selectCount(any())).thenReturn(0L);
        when(sysDeptMapper.selectById(9L)).thenReturn(dept);

        org.junit.jupiter.api.Assertions.assertThrows(
                org.example.back.common.exception.BusinessException.class,
                () -> authService.register(request)
        );
    }

    @Test
    void register_shouldRejectWeakPassword() {
        RegisterRequest request = new RegisterRequest();
        request.setUsername("weak_employee");
        request.setPassword("12345678");
        request.setRealName("弱密码员工");
        request.setDeptId(2L);

        org.example.back.common.exception.BusinessException ex = assertThrows(
                org.example.back.common.exception.BusinessException.class,
                () -> authService.register(request)
        );

        assertEquals(400, ex.getCode());
        assertEquals("密码长度为8–20位，须同时包含字母和数字", ex.getMsg());
        verify(sysUserMapper, never()).insert(any(SysUser.class));
        verify(sysEmployeeMapper, never()).insert(any(SysEmployee.class));
    }

    // ---------- 会话 72（ADR-0024）：本人修改密码 ----------

    private SysUser userWithHash(String rawPassword) {
        SysUser user = new SysUser();
        user.setId(77L);
        user.setUsername("u77");
        user.setPassword(BCrypt.hashpw(rawPassword));
        return user;
    }

    private ChangePasswordDTO changeDto(String oldPassword, String newPassword) {
        ChangePasswordDTO dto = new ChangePasswordDTO();
        dto.setOldPassword(oldPassword);
        dto.setNewPassword(newPassword);
        return dto;
    }

    @Test
    void changePassword_shouldUpdateHashAndInvalidateAllSessions() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("77");
            when(sysUserMapper.selectById(77L)).thenReturn(userWithHash("abc11122"));

            authService.changePassword(changeDto("abc11122", "xyz99887"));

            ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
            verify(sysUserMapper, times(1)).updateById(captor.capture());
            SysUser saved = captor.getValue();
            assertTrue(BCrypt.checkpw("xyz99887", saved.getPassword()));
            stp.verify(() -> StpUtil.logout(77L));
        }
    }

    @Test
    void changePassword_shouldRejectWrongOldPassword() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("77");
            when(sysUserMapper.selectById(77L)).thenReturn(userWithHash("abc11122"));

            org.example.back.common.exception.BusinessException ex = assertThrows(
                    org.example.back.common.exception.BusinessException.class,
                    () -> authService.changePassword(changeDto("wrongpass9", "xyz99887"))
            );

            assertEquals(400, ex.getCode());
            assertEquals("旧密码不正确", ex.getMsg());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }

    @Test
    void changePassword_shouldRejectSameAsOld() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("77");
            when(sysUserMapper.selectById(77L)).thenReturn(userWithHash("abc11122"));

            org.example.back.common.exception.BusinessException ex = assertThrows(
                    org.example.back.common.exception.BusinessException.class,
                    () -> authService.changePassword(changeDto("abc11122", "abc11122"))
            );

            assertEquals(400, ex.getCode());
            assertEquals("新密码不能与旧密码相同", ex.getMsg());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }

    @Test
    void changePassword_shouldRejectDigitsOnlyNewPassword() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("77");
            when(sysUserMapper.selectById(77L)).thenReturn(userWithHash("abc11122"));

            org.example.back.common.exception.BusinessException ex = assertThrows(
                    org.example.back.common.exception.BusinessException.class,
                    () -> authService.changePassword(changeDto("abc11122", "12345678"))
            );

            assertEquals(400, ex.getCode());
            assertEquals("新密码长度为8–20位，须同时包含字母和数字", ex.getMsg());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }

    @Test
    void changePassword_shouldRejectEdgeWhitespaceNewPassword() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("77");
            when(sysUserMapper.selectById(77L)).thenReturn(userWithHash("abc11122"));

            org.example.back.common.exception.BusinessException ex = assertThrows(
                    org.example.back.common.exception.BusinessException.class,
                    () -> authService.changePassword(changeDto("abc11122", "xyz99887 "))
            );

            assertEquals(400, ex.getCode());
            assertEquals("新密码首尾不能包含空格", ex.getMsg());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }

    @Test
    void changePassword_shouldRequireLogin() {
        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn(null);

            org.example.back.common.exception.BusinessException ex = assertThrows(
                    org.example.back.common.exception.BusinessException.class,
                    () -> authService.changePassword(changeDto("abc11122", "xyz99887"))
            );

            assertEquals(401, ex.getCode());
            assertEquals("用户未登录", ex.getMsg());
            verify(sysUserMapper, never()).updateById(any(SysUser.class));
        }
    }
}