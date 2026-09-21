package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionOrderStep;
import org.example.back.entity.BizProductionQc;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizProductionOrderStepMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生产工序打卡（D64）单测：初始化/打卡/撤销/状态闸口/权限/推导合并。
 */
@ExtendWith(MockitoExtension.class)
class ProductionStepServiceTest {

    @Mock private BizProductionOrderStepMapper stepMapper;
    @Mock private BizProductionOrderMapper orderMapper;
    @Mock private BizProductionQcMapper qcMapper;
    @Mock private QcService qcService;
    @Mock private AuthzService authzService;
    @Mock private AuthService authService;

    @InjectMocks private ProductionStepService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaUpdateWrapper.set() 解析列名需实体元数据缓存（LambdaQueryWrapper.eq 是惰性求值不受影响）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizProductionOrderStep.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizProductionQc.class);
    }

    private BizProductionOrder order(int status) {
        BizProductionOrder o = new BizProductionOrder();
        o.setId(7L);
        o.setStatus(status);
        return o;
    }

    private BizProductionOrderStep step(int stepNo, int status, Long operatorId) {
        BizProductionOrderStep s = new BizProductionOrderStep();
        s.setId(100L);
        s.setOrderId(7L);
        s.setStepNo(stepNo);
        s.setStatus(status);
        s.setOperatorId(operatorId);
        if (operatorId != null) {
            s.setOperatorName("张三");
            s.setOperateTime(LocalDateTime.now());
        }
        return s;
    }

    private LoginResponse.UserInfoVO user(long id) {
        LoginResponse.UserInfoVO u = new LoginResponse.UserInfoVO();
        u.setId(id);
        u.setRealName("李四");
        return u;
    }

    // ---------- 初始化 ----------

    @Test
    void initStepsForOrder_insertsSevenManualRows() {
        service.initStepsForOrder(7L);

        ArgumentCaptor<BizProductionOrderStep> cap = ArgumentCaptor.forClass(BizProductionOrderStep.class);
        verify(stepMapper, times(7)).insert(cap.capture());
        List<BizProductionOrderStep> rows = cap.getAllValues();
        assertEquals(List.of(1, 2, 3, 4, 5, 7, 9),
                rows.stream().map(BizProductionOrderStep::getStepNo).toList());
        for (BizProductionOrderStep row : rows) {
            assertEquals(7L, row.getOrderId());
            assertEquals(BizProductionOrderStep.STATUS_UNDONE, row.getStatus());
        }
        assertEquals("磁性材料装配", rows.get(0).getStepName());
        assertEquals("底座结构组装", rows.get(1).getStepName());
        assertEquals("手柄机构装配", rows.get(2).getStepName());
        assertEquals("PCB板焊接及安装", rows.get(3).getStepName());
        assertEquals("程序烧录", rows.get(4).getStepName());
        assertEquals("屏蔽壳安装", rows.get(5).getStepName());
        assertEquals("发合格证、条码、标签、配件及包装", rows.get(6).getStepName());
    }

    // ---------- 打卡 ----------

    @Test
    void complete_marksDoneWithOperator() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_UNDONE, null));
        when(authService.getUserInfo()).thenReturn(user(9L));

        service.complete(7L, 3);

        ArgumentCaptor<BizProductionOrderStep> cap = ArgumentCaptor.forClass(BizProductionOrderStep.class);
        verify(stepMapper).updateById(cap.capture());
        assertEquals(BizProductionOrderStep.STATUS_DONE, cap.getValue().getStatus());
        assertEquals(9L, cap.getValue().getOperatorId());
        assertEquals("李四", cap.getValue().getOperatorName());
        assertNotNull(cap.getValue().getOperateTime());
    }

    @Test
    void complete_rejectsRepeat() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_DONE, 9L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 3));
        assertTrue(ex.getMessage().contains("已完成打卡"));
        verify(stepMapper, never()).updateById(any(BizProductionOrderStep.class));
    }

    @Test
    void complete_rejectsDerivedStep() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 6));
        assertTrue(ex.getMessage().contains("不支持打卡"));
        verify(stepMapper, never()).selectOne(any());
    }

    @Test
    void complete_rejectsBeforeStart() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_PENDING));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 1));
        assertTrue(ex.getMessage().contains("尚未开工"));
        verify(stepMapper, never()).selectOne(any());
    }

    @Test
    void complete_rejectsAfterDone() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_DONE));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 1));
        assertTrue(ex.getMessage().contains("已固化"));
        verify(stepMapper, never()).selectOne(any());
    }

    // ---------- 权限 ----------

    @Test
    void complete_rejectsWithoutProductionAccess() {
        doThrow(BusinessException.validateFail("仅生产研发部可操作工序打卡")).when(authzService)
                .requireAnyDeptMemberOrSuperAdmin(anyString(), eq(AuthzService.DEPT_PRODUCTION));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 3));
        assertTrue(ex.getMessage().contains("生产研发部"));
        verify(orderMapper, never()).selectById(7L);
    }

    // ---------- 撤销 ----------

    @Test
    void revoke_bySelfSkipsAdminCheck() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_DONE, 9L));
        when(authService.getUserInfo()).thenReturn(user(9L));
        when(qcMapper.selectCount(any())).thenReturn(0L); // D125：无首测记录，放行

        service.revoke(7L, 3);

        verify(authzService, never()).requireDeptAdminOrSuperAdmin(anyString(), anyString());
        verify(stepMapper).update(isNull(), any());
    }

    @Test
    void revoke_byAdminAllowed() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_DONE, 8L));
        when(authService.getUserInfo()).thenReturn(user(9L));
        when(qcMapper.selectCount(any())).thenReturn(0L); // D125：无首测记录，放行

        service.revoke(7L, 3);

        verify(authzService).requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_PRODUCTION), anyString());
        verify(stepMapper).update(isNull(), any());
    }

    @Test
    void revoke_rejectsNonSelfNonAdmin() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_DONE, 8L));
        when(authService.getUserInfo()).thenReturn(user(9L));
        doThrow(BusinessException.validateFail("仅打卡本人或生产研发部管理员可撤销打卡"))
                .when(authzService).requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_PRODUCTION), anyString());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 3));
        assertTrue(ex.getMessage().contains("撤销"));
        verify(stepMapper, never()).update(isNull(), any());
    }

    @Test
    void revoke_rejectsUndone() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(3, BizProductionOrderStep.STATUS_UNDONE, null));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 3));
        assertTrue(ex.getMessage().contains("无需撤销"));
    }

    // ---------- 列表合并（10 道 = 7 人工 + 质检/入库推导） ----------

    @Test
    void listSteps_mergesManualRowsWithDerivedLines() {
        BizProductionOrderStep done = step(3, BizProductionOrderStep.STATUS_DONE, 9L);
        done.setOperatorName("王五");
        when(stepMapper.selectList(any())).thenReturn(List.of(done));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("ok");
        qc.setFinalStatus("untested");
        when(qcService.buildState(any())).thenReturn(qc);

        List<ProductionStepVO> steps = service.listSteps(order(BizProductionOrder.STATUS_AWAIT_QC));

        assertEquals(10, steps.size());
        // 人工行：step1 未完成可打卡，step3 已完成可撤销
        assertEquals("manual", steps.get(0).getType());
        assertFalse(steps.get(0).getDone());
        assertTrue(steps.get(0).getOperable());
        assertEquals("info", steps.get(0).getTagType());
        assertTrue(steps.get(2).getDone());
        assertEquals("王五", steps.get(2).getOperatorName());
        assertTrue(steps.get(2).getRevocable());
        assertEquals("success", steps.get(2).getTagType());
        // 第 6 道由首测推导：合格
        assertEquals("首次测试", steps.get(5).getStepName());
        assertEquals("qc", steps.get(5).getType());
        assertTrue(steps.get(5).getDone());
        assertEquals("已完成（合格）", steps.get(5).getStatusText());
        assertFalse(steps.get(5).getOperable());
        // 第 8 道由成品测推导：未测
        assertEquals("成品测试", steps.get(7).getStepName());
        assertFalse(steps.get(7).getDone());
        assertEquals("未测", steps.get(7).getStatusText());
        // 第 10 道由订单状态推导：待入库
        assertEquals("成品入库", steps.get(9).getStepName());
        assertEquals("receipt", steps.get(9).getType());
        assertFalse(steps.get(9).getDone());
        assertEquals("待入库（质检合格）", steps.get(9).getStatusText());
        assertEquals("warning", steps.get(9).getTagType());
    }

    @Test
    void listSteps_returnsNullWhenNoRows() {
        when(stepMapper.selectList(any())).thenReturn(List.of());

        assertNull(service.listSteps(order(BizProductionOrder.STATUS_IN_PROGRESS)));
        verify(qcService, never()).buildState(any());
    }

    @Test
    void listSteps_returnsNullWhenVoided() {
        assertNull(service.listSteps(order(BizProductionOrder.STATUS_VOIDED)));
        verify(qcService, never()).buildState(any());
        verify(stepMapper, never()).selectList(any());
    }

    @Test
    void listSteps_terminatedOrder_receiptLineShowsTerminated() {
        BizProductionOrderStep done = step(3, BizProductionOrderStep.STATUS_DONE, 9L);
        when(stepMapper.selectList(any())).thenReturn(List.of(done));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("untested");
        qc.setFinalStatus("untested");
        when(qcService.buildState(any())).thenReturn(qc);

        List<ProductionStepVO> steps = service.listSteps(order(BizProductionOrder.STATUS_TERMINATED));

        // 入库行（第 10 道）应显示"已终止"
        ProductionStepVO receipt = steps.get(9);
        assertEquals("receipt", receipt.getType());
        assertFalse(receipt.getDone());
        assertEquals("已终止", receipt.getStatusText());
        assertEquals("danger", receipt.getTagType());
    }

    // ---------- D73：已终止订单冻结（打卡/撤销拦截） ----------

    @Test
    void complete_terminatedOrder_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 1));
        assertTrue(ex.getMessage().contains("已终止"));
    }

    @Test
    void revoke_terminatedOrder_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 1));
        assertTrue(ex.getMessage().contains("已终止"));
    }

    // ---------- D125：工序-质检顺序门禁 ----------

    @Test
    void complete_step7_beforeFirstPass_rejected() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(7, BizProductionOrderStep.STATUS_UNDONE, null));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("untested");
        when(qcService.buildState(any())).thenReturn(qc);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 7));
        assertTrue(ex.getMessage().contains("首次测试尚未通过"), "实际: " + ex.getMessage());
        verify(stepMapper, never()).updateById(any(BizProductionOrderStep.class));
    }

    @Test
    void complete_step9_beforeFinalPass_rejected() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(9, BizProductionOrderStep.STATUS_UNDONE, null));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("ok");
        qc.setFinalStatus("untested");
        when(qcService.buildState(any())).thenReturn(qc);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.complete(7L, 9));
        assertTrue(ex.getMessage().contains("成品测试尚未通过"), "实际: " + ex.getMessage());
        verify(stepMapper, never()).updateById(any(BizProductionOrderStep.class));
    }

    @Test
    void complete_step7_afterFirstOk_passes() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(7, BizProductionOrderStep.STATUS_UNDONE, null));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("ok"); // NG→返工→重测合格也走此口径（buildState 取最新一条）
        when(qcService.buildState(any())).thenReturn(qc);
        when(authService.getUserInfo()).thenReturn(user(9L));

        service.complete(7L, 7);

        ArgumentCaptor<BizProductionOrderStep> cap = ArgumentCaptor.forClass(BizProductionOrderStep.class);
        verify(stepMapper).updateById(cap.capture());
        assertEquals(BizProductionOrderStep.STATUS_DONE, cap.getValue().getStatus());
    }

    @Test
    void complete_step9_afterFinalOk_passes() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(9, BizProductionOrderStep.STATUS_UNDONE, null));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("ok");
        qc.setFinalStatus("ok");
        when(qcService.buildState(any())).thenReturn(qc);
        when(authService.getUserInfo()).thenReturn(user(9L));

        service.complete(7L, 9);

        verify(stepMapper).updateById(any(BizProductionOrderStep.class));
    }

    @Test
    void complete_step1_noGate_notEvenWhenQcUntested() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(1, BizProductionOrderStep.STATUS_UNDONE, null));
        when(authService.getUserInfo()).thenReturn(user(9L));

        service.complete(7L, 1);

        // 工序 1-5 不设质检前置，无需查质检状态
        verify(qcService, never()).buildState(any());
        verify(stepMapper).updateById(any(BizProductionOrderStep.class));
    }

    // ---------- D125：撤销保护（撤销不得使已发生的质检门禁失效） ----------

    @Test
    void revoke_firstTestExists_blocksSteps15Revoke() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(5, BizProductionOrderStep.STATUS_DONE, 9L));
        when(authService.getUserInfo()).thenReturn(user(9L));
        when(qcMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 5));
        assertTrue(ex.getMessage().contains("已存在首次测试记录"), "实际: " + ex.getMessage());
        verify(stepMapper, never()).update(isNull(), any());
    }

    @Test
    void revoke_finalTestExists_blocksStep7Revoke() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(7, BizProductionOrderStep.STATUS_DONE, 9L));
        when(authService.getUserInfo()).thenReturn(user(9L));
        when(qcMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 7));
        assertTrue(ex.getMessage().contains("已存在成品测试记录"), "实际: " + ex.getMessage());
        verify(stepMapper, never()).update(isNull(), any());
    }

    @Test
    void revoke_step9_blockedAfterLeavingProduction() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_AWAIT_QC));
        when(stepMapper.selectOne(any())).thenReturn(step(9, BizProductionOrderStep.STATUS_DONE, 9L));
        when(authService.getUserInfo()).thenReturn(user(9L));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.revoke(7L, 9));
        assertTrue(ex.getMessage().contains("待入库"), "实际: " + ex.getMessage());
        verify(stepMapper, never()).update(isNull(), any());
    }

    @Test
    void revoke_step9_allowedWhileInProgress() {
        when(orderMapper.selectById(7L)).thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS));
        when(stepMapper.selectOne(any())).thenReturn(step(9, BizProductionOrderStep.STATUS_DONE, 9L));
        when(authService.getUserInfo()).thenReturn(user(9L));

        service.revoke(7L, 9);

        // 生产中且无成品测门禁问题 → 正常撤销（工序 9 保护只看订单状态，不查质检表）
        verify(qcMapper, never()).selectCount(any());
        verify(stepMapper).update(isNull(), any());
    }

    // ---------- D125：列表门禁锁定标记 ----------

    @Test
    void listSteps_lockedStep7and9_getLockReasonAndNotOperable() {
        BizProductionOrderStep done5 = step(5, BizProductionOrderStep.STATUS_DONE, 9L);
        when(stepMapper.selectList(any())).thenReturn(List.of(done5));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("untested");
        qc.setFinalStatus("untested");
        when(qcService.buildState(any())).thenReturn(qc);

        List<ProductionStepVO> steps = service.listSteps(order(BizProductionOrder.STATUS_IN_PROGRESS));

        // 工序 1-5 已打卡/可打卡不受门禁影响；7/9 未解锁灰置并带锁定原因
        assertTrue(steps.get(0).getOperable());
        assertNull(steps.get(0).getLockReason());
        assertFalse(steps.get(6).getOperable());
        assertTrue(steps.get(6).getLockReason().contains("首次测试尚未通过"), steps.get(6).getLockReason());
        assertFalse(steps.get(8).getOperable());
        assertTrue(steps.get(8).getLockReason().contains("成品测试尚未通过"), steps.get(8).getLockReason());
    }

    @Test
    void listSteps_unlockedAfterQcPass_gateReleased() {
        BizProductionOrderStep done5 = step(5, BizProductionOrderStep.STATUS_DONE, 9L);
        when(stepMapper.selectList(any())).thenReturn(List.of(done5));
        QcStateVO qc = new QcStateVO();
        qc.setFirstStatus("ok"); // 首测合格 → 工序 7 解锁
        qc.setFinalStatus("ok"); // 成品测合格 → 工序 9 解锁
        when(qcService.buildState(any())).thenReturn(qc);

        List<ProductionStepVO> steps = service.listSteps(order(BizProductionOrder.STATUS_IN_PROGRESS));

        assertTrue(steps.get(6).getOperable());
        assertNull(steps.get(6).getLockReason());
        assertTrue(steps.get(8).getOperable());
        assertNull(steps.get(8).getLockReason());
    }
}
