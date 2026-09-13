package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.mapper.SysDeptMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthzServiceTest {

    @Mock private AuthService authService;
    @Mock private SysDeptMapper sysDeptMapper;

    @InjectMocks private AuthzService authzService;

    private void mockRole(String role) {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(1L);
        user.setUsername("u1");
        user.setRole(role);
        when(authService.getUserInfo()).thenReturn(user);
    }

    // ---------- D77/ADR-0009：超管真只读——业务写操作一律 403 ----------
    @Test
    void requireNotSuperAdminForBusinessWrite_superAdmin_forbidden() {
        mockRole(AuthzService.ROLE_SUPERADMIN);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> authzService.requireNotSuperAdminForBusinessWrite());
        assertTrue(ex.getMessage().contains("只读审计角色"), "实际: " + ex.getMessage());
    }

    @Test
    void requireNotSuperAdminForBusinessWrite_admin_passes() {
        mockRole(AuthzService.ROLE_ADMIN);
        assertDoesNotThrow(() -> authzService.requireNotSuperAdminForBusinessWrite());
    }

    @Test
    void requireNotSuperAdminForBusinessWrite_employee_passes() {
        mockRole(AuthzService.ROLE_EMPLOYEE);
        assertDoesNotThrow(() -> authzService.requireNotSuperAdminForBusinessWrite());
    }

    // ---------- 大小写/空白归一化后仍识别超管 ----------
    @Test
    void requireNotSuperAdminForBusinessWrite_roleNormalized() {
        mockRole(" SuperAdmin ");
        assertThrows(BusinessException.class,
                () -> authzService.requireNotSuperAdminForBusinessWrite());
    }
}
