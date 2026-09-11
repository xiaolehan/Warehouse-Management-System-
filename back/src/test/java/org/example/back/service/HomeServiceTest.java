package org.example.back.service;

import org.example.back.dto.LoginResponse;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysUser;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysEmployeeMapper;
import org.example.back.mapper.SysErrorLogMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.EmployeeWorkbenchVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HomeServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        org.apache.ibatis.builder.MapperBuilderAssistant assistant =
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                org.example.back.entity.BaseGoods.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant,
                org.example.back.entity.SysEmployee.class);
    }

    @Mock private AuthService authService;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysErrorLogMapper sysErrorLogMapper;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private SysEmployeeMapper sysEmployeeMapper;
    @Mock private SysDeptMapper sysDeptMapper;
    @Mock private WorkRequirementService workRequirementService;

    @InjectMocks private HomeService service;

    private LoginResponse.UserInfoVO employeeUser(String deptCode) {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        user.setUsername("prod_emp");
        user.setRole("employee");
        user.setDeptId(5L);
        user.setDeptCode(deptCode);
        user.setDeptName("生产研发部");
        return user;
    }

    // ---------- 员工工作台预警数放开生产部门（首页低/零库存卡片数据源） ----------
    @Test
    void employeeWorkbench_returnsStockWarningCountsForProductionEmployee() {
        when(authService.getUserInfo()).thenReturn(employeeUser("production"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        when(baseGoodsMapper.selectCount(any())).thenReturn(4L, 1L);
        when(sysEmployeeMapper.selectOne(any())).thenReturn(null);
        SysDept dept = new SysDept();
        dept.setDeptName("生产研发部");
        when(sysDeptMapper.selectById(5L)).thenReturn(dept);
        when(workRequirementService.getEmployeeWorkTips(9L)).thenReturn(List.of());

        EmployeeWorkbenchVO result = service.employeeWorkbench();

        assertEquals(4L, result.getSummary().getLowStockCount());
        assertEquals(1L, result.getSummary().getZeroStockCount());
    }

    @Test
    void employeeWorkbench_leavesStockWarningCountsNullForHrEmployee() {
        when(authService.getUserInfo()).thenReturn(employeeUser("hr"));
        when(sysUserMapper.selectById(9L)).thenReturn(new SysUser());
        when(sysEmployeeMapper.selectOne(any())).thenReturn(null);
        when(sysDeptMapper.selectById(5L)).thenReturn(new SysDept());
        when(workRequirementService.getEmployeeWorkTips(9L)).thenReturn(List.of());

        EmployeeWorkbenchVO result = service.employeeWorkbench();

        assertNull(result.getSummary().getLowStockCount());
        assertNull(result.getSummary().getZeroStockCount());
    }
}
