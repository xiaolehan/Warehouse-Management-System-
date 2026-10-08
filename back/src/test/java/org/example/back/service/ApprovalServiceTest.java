package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.ApprovalCreateDTO;
import org.example.back.dto.ApprovalQueryDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.ApprovalOrderVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalServiceTest {

    @Mock
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Mock
    private BizSalesMapper bizSalesMapper;

    @Mock
    private AuthService authService;

    @Mock
    private AuthzService authzService;

    @Mock
    private MessageService messageService;

    @InjectMocks
    private ApprovalService approvalService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaQueryWrapper 解析 BizApprovalOrder::getRequestAction 需要 lambda 缓存（D120 行级过滤断言用）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizApprovalOrder.class);
    }

    @Test
    void create_shouldRejectEmployeeRequester() {
        LoginResponse.UserInfoVO requester = new LoginResponse.UserInfoVO();
        requester.setId(100L);
        requester.setRole("employee");
        requester.setDeptCode("sales");

        ApprovalCreateDTO dto = new ApprovalCreateDTO();
        dto.setBizType("purchase");
        dto.setBizId(1L);
        dto.setRequestAction("void");
        dto.setReason("test");

        when(authService.getUserInfo()).thenReturn(requester);

        BusinessException ex = assertThrows(BusinessException.class, () -> approvalService.create(dto));

        assertEquals(403, ex.getCode());
        assertEquals("仅采购或销售部门可提交该类作废审批申请", ex.getMsg());
        verifyNoInteractions(bizApprovalOrderMapper);
    }

    // ---------- D120：价格偏离审批权 超管→销售管理员 ----------

    @Test
    void approve_priceDeviation_salesAdminAllowed_andRevokesPendingMessage() {
        LoginResponse.UserInfoVO approver = new LoginResponse.UserInfoVO();
        approver.setId(9L);
        approver.setRealName("销售管理员");
        when(authService.getUserInfo()).thenReturn(approver);
        // authzService 为 mock：requireDeptAdmin 默认放行；守卫参数锁定见 superadminForbidden 用例

        BizApprovalOrder pending = new BizApprovalOrder();
        pending.setId(7L);
        pending.setBizType("sales");
        pending.setBizId(55L);
        pending.setRequestAction("price_deviation_confirm");
        pending.setStatus(1);
        when(bizApprovalOrderMapper.selectById(7L)).thenReturn(pending);
        when(bizApprovalOrderMapper.update(any(), any())).thenReturn(1);

        BizSales sales = new BizSales();
        sales.setId(55L);
        sales.setSalesNo("XS260920001");
        sales.setBizStatus(1);
        when(bizSalesMapper.selectById(55L)).thenReturn(sales);

        approvalService.approve(7L, null);

        // D21：审批终态按标题撤回未读待办消息，不误伤同单其他消息
        verify(messageService).revokeUnreadByBizAndTitles("sales", 55L,
                List.of(MessageService.TITLE_PRICE_DEVIATION_PENDING));
    }

    @Test
    void approve_priceDeviation_superadminForbidden() {
        LoginResponse.UserInfoVO superadmin = new LoginResponse.UserInfoVO();
        superadmin.setId(1L);
        superadmin.setRole("superadmin");
        when(authService.getUserInfo()).thenReturn(superadmin);
        doThrow(BusinessException.forbidden("价格偏离审批需销售部管理员处理"))
                .when(authzService).requireDeptAdmin(AuthzService.DEPT_SALES, "价格偏离审批需销售部管理员处理");

        BizApprovalOrder pending = new BizApprovalOrder();
        pending.setId(7L);
        pending.setBizType("sales");
        pending.setBizId(55L);
        pending.setRequestAction("price_deviation_confirm");
        pending.setStatus(1);
        when(bizApprovalOrderMapper.selectById(7L)).thenReturn(pending);

        BusinessException ex = assertThrows(BusinessException.class, () -> approvalService.approve(7L, null));

        assertEquals(403, ex.getCode());
        assertEquals("价格偏离审批需销售部管理员处理", ex.getMsg());
        // 认领与业务执行都未发生
        verify(bizApprovalOrderMapper, never()).update(any(), any());
        verifyNoInteractions(bizSalesMapper);
        verifyNoInteractions(messageService);
    }

    // ---------- D120：审批页行级过滤（同页共管） ----------

    @Test
    void page_salesAdmin_seesOnlyPriceDeviationRows() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_SALES)).thenReturn(true);

        Page<BizApprovalOrder> empty = new Page<>(1, 10);
        empty.setRecords(List.of());
        when(bizApprovalOrderMapper.selectPage(any(), any())).thenReturn(empty);

        approvalService.page(new ApprovalQueryDTO());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<BizApprovalOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(bizApprovalOrderMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<BizApprovalOrder> wrapper = captor.getValue();
        wrapper.getSqlSegment(); // 触发行参物化
        assertTrue(wrapper.getParamNameValuePairs().containsValue("price_deviation_confirm"),
                "应含 request_action 参数, 实际: " + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getSqlSegment().contains("requestAction = "),
                "销售管理员应走 eq 只看价格偏离行, 实际: " + wrapper.getSqlSegment());
    }

    @Test
    void page_warehouseAdmin_seesOnlyVoidRows() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(8L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(authzService.isDeptAdmin(AuthzService.DEPT_SALES)).thenReturn(false);

        Page<BizApprovalOrder> empty = new Page<>(1, 10);
        empty.setRecords(List.of());
        when(bizApprovalOrderMapper.selectPage(any(), any())).thenReturn(empty);

        approvalService.page(new ApprovalQueryDTO());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<BizApprovalOrder>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(bizApprovalOrderMapper).selectPage(any(), captor.capture());
        LambdaQueryWrapper<BizApprovalOrder> wrapper = captor.getValue();
        wrapper.getSqlSegment(); // 触发行参物化
        assertTrue(wrapper.getParamNameValuePairs().containsValue("price_deviation_confirm"),
                "应含 request_action 参数, 实际: " + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getSqlSegment().contains("requestAction <> "),
                "仓储管理员应走 ne 只看作废类行, 实际: " + wrapper.getSqlSegment());
    }

    @Test
    void page_salesEmployee_forbidden() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptAdmin(any())).thenReturn(false);
        when(authzService.isSuperAdmin()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalService.page(new ApprovalQueryDTO()));

        assertEquals(403, ex.getCode());
        verifyNoInteractions(bizApprovalOrderMapper);
    }

    // ---------- D137：审批单详情（getById 与列表同口径行级过滤，越权 404 不泄露存在性） ----------

    @Test
    void getById_salesAdmin_scopeSameAsPage_andReturnsVO() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_SALES)).thenReturn(true);

        BizApprovalOrder entity = new BizApprovalOrder();
        entity.setId(66L);
        entity.setApprovalNo("SP20261008001");
        entity.setRequestAction("price_deviation_confirm");
        AtomicReference<LambdaQueryWrapper<BizApprovalOrder>> captured = new AtomicReference<>();
        when(bizApprovalOrderMapper.selectOne(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return entity;
        });

        ApprovalOrderVO vo = approvalService.getById(66L);

        assertEquals(66L, vo.getId());
        assertEquals("SP20261008001", vo.getApprovalNo());
        LambdaQueryWrapper<BizApprovalOrder> wrapper = captured.get();
        wrapper.getSqlSegment(); // 触发行参物化
        assertTrue(wrapper.getParamNameValuePairs().containsValue("price_deviation_confirm"),
                "应含 request_action 参数, 实际: " + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getSqlSegment().contains("requestAction = "),
                "销售管理员详情应与列表同口径 eq, 实际: " + wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().contains("id = "),
                "详情应按主键查询, 实际: " + wrapper.getSqlSegment());
    }

    @Test
    void getById_warehouseAdmin_scopeSameAsPage() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(8L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(authzService.isDeptAdmin(AuthzService.DEPT_SALES)).thenReturn(false);

        BizApprovalOrder entity = new BizApprovalOrder();
        entity.setId(67L);
        entity.setRequestAction("void");
        AtomicReference<LambdaQueryWrapper<BizApprovalOrder>> captured = new AtomicReference<>();
        when(bizApprovalOrderMapper.selectOne(any())).thenAnswer(inv -> {
            captured.set(inv.getArgument(0));
            return entity;
        });

        ApprovalOrderVO vo = approvalService.getById(67L);

        assertEquals(67L, vo.getId());
        LambdaQueryWrapper<BizApprovalOrder> wrapper = captured.get();
        wrapper.getSqlSegment(); // 触发行参物化
        assertTrue(wrapper.getParamNameValuePairs().containsValue("price_deviation_confirm"),
                "应含 request_action 参数, 实际: " + wrapper.getParamNameValuePairs());
        assertTrue(wrapper.getSqlSegment().contains("requestAction <> "),
                "仓储管理员详情应与列表同口径 ne, 实际: " + wrapper.getSqlSegment());
    }

    @Test
    void getById_outOfScope_notFound() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        when(authService.getUserInfo()).thenReturn(user);
        when(authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_SALES)).thenReturn(true);

        when(bizApprovalOrderMapper.selectOne(any())).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> approvalService.getById(68L));

        assertEquals(404, ex.getCode());
        assertEquals("审批单不存在", ex.getMessage());
    }
}


