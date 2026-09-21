package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.QcSaveDTO;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionOrderStep;
import org.example.back.entity.BizProductionQc;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizProductionOrderStepMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.vo.QcStateVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 质检状态推导（D64 列表修复）+ D125 工序-质检顺序门禁 单测。
 */
@ExtendWith(MockitoExtension.class)
class QcServiceTest {

    @Mock private BizProductionQcMapper qcMapper;
    @Mock private BizProductionOrderStepMapper stepMapper;
    @Mock private BizProductionOrderMapper orderMapper;
    @Mock private AuthzService authzService;
    @Mock private AuthService authService;
    @Mock private MessageService messageService;

    @InjectMocks private QcService service;

    private BizProductionOrder order(long id) {
        BizProductionOrder o = new BizProductionOrder();
        o.setId(id);
        o.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        return o;
    }

    private BizProductionQc qc(long orderId, String point, String result) {
        BizProductionQc r = new BizProductionQc();
        r.setOrderId(orderId);
        r.setTestPoint(point);
        r.setResult(result);
        return r;
    }

    /** 已打卡完成的人工工序行 */
    private BizProductionOrderStep doneStep(long orderId, int stepNo) {
        BizProductionOrderStep s = new BizProductionOrderStep();
        s.setOrderId(orderId);
        s.setStepNo(stepNo);
        s.setStatus(BizProductionOrderStep.STATUS_DONE);
        return s;
    }

    @Test
    void buildStateBatch_groupsRecordsPerOrder() {
        when(qcMapper.selectList(any())).thenReturn(List.of(
                qc(1L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_OK),
                qc(2L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_NG),
                qc(2L, BizProductionQc.POINT_FINAL, BizProductionQc.RESULT_OK)
        ));
        // D125：门禁解锁标记——单1工序1-5已打卡，单2全部解锁工序已打卡
        when(stepMapper.selectList(any())).thenReturn(List.of(
                doneStep(1L, 1), doneStep(1L, 2), doneStep(1L, 3), doneStep(1L, 4), doneStep(1L, 5),
                doneStep(2L, 1), doneStep(2L, 2), doneStep(2L, 3), doneStep(2L, 4), doneStep(2L, 5), doneStep(2L, 7)
        ));

        Map<Long, QcStateVO> states = service.buildStateBatch(List.of(order(1L), order(2L)));

        // 两张单只触发一次 in 查询（列表页不逐行 N 次查询）
        verify(qcMapper, times(1)).selectList(any());
        verify(stepMapper, times(1)).selectList(any());

        // 单 1：首测合格、成品测未测 → 整体未通过
        QcStateVO s1 = states.get(1L);
        assertTrue(s1.getFirstPassed());
        assertEquals("ok", s1.getFirstStatus());
        assertEquals("untested", s1.getFinalStatus());
        assertFalse(s1.getPassed());
        assertEquals(1, s1.getRecords().size());
        // D125 解锁标记：1-5 已打卡→首测解锁；7 未打卡→成品测锁定
        assertTrue(s1.getFirstUnlocked());
        assertFalse(s1.getFinalUnlocked());

        // 单 2：首测 NG 待处置、成品测合格 → 整体仍未通过
        QcStateVO s2 = states.get(2L);
        assertEquals("ng", s2.getFirstStatus());
        assertFalse(s2.getFirstPassed());
        assertTrue(s2.getFinalPassed());
        assertFalse(s2.getPassed());
        assertEquals(2, s2.getRecords().size());
        assertTrue(s2.getFirstUnlocked());
        assertTrue(s2.getFinalUnlocked());
    }

    // ---------- D125：门禁解锁标记（最新记录口径：NG→返工→重测合格即解锁） ----------

    @Test
    void buildState_reworkThenRetestOk_unlocksPoint() {
        // 首测 NG（已处置返工）→ 重测 OK：最新一条为 OK → 解锁口径=合格
        BizProductionQc ng = qc(7L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_NG);
        ng.setDisposition(BizProductionQc.DISP_REWORK);
        BizProductionQc retestOk = qc(7L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_OK);
        when(qcMapper.selectList(any())).thenReturn(List.of(ng, retestOk));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                doneStep(7L, 1), doneStep(7L, 2), doneStep(7L, 3), doneStep(7L, 4), doneStep(7L, 5)));

        QcStateVO state = service.buildState(order(7L));

        assertEquals("ok", state.getFirstStatus());
        assertTrue(state.getFirstPassed());
        assertTrue(state.getFirstUnlocked());
        assertFalse(state.getFinalUnlocked()); // 工序 7 未打卡
    }

    // ---------- D125：录入门禁（首测←1-5 全打卡；成品测←工序 7 打卡） ----------

    @Test
    void record_firstQc_blockedWhenSteps15NotAllDone() {
        when(orderMapper.selectById(7L)).thenReturn(order(7L));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                doneStep(7L, 1), doneStep(7L, 2), doneStep(7L, 3)));

        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);
        dto.setTestPoint("first");
        dto.setResult("OK");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.record(dto));
        assertTrue(ex.getMessage().contains("尚未完成"), "实际: " + ex.getMessage());
        verify(qcMapper, never()).insert(any(BizProductionQc.class));
    }

    @Test
    void record_finalQc_blockedWhenStep7NotDone() {
        when(orderMapper.selectById(7L)).thenReturn(order(7L));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                doneStep(7L, 1), doneStep(7L, 2), doneStep(7L, 3), doneStep(7L, 4), doneStep(7L, 5)));

        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);
        dto.setTestPoint("final");
        dto.setResult("OK");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.record(dto));
        assertTrue(ex.getMessage().contains("成品测试需先完成工序 7"), "实际: " + ex.getMessage());
        verify(qcMapper, never()).insert(any(BizProductionQc.class));
    }

    @Test
    void record_gatesSatisfied_insertsAndDerivesState() {
        when(orderMapper.selectById(7L)).thenReturn(order(7L));
        when(stepMapper.selectList(any())).thenReturn(List.of(
                doneStep(7L, 1), doneStep(7L, 2), doneStep(7L, 3), doneStep(7L, 4), doneStep(7L, 5)));
        when(authService.getUserInfo()).thenReturn(user());
        when(qcMapper.insert(any(BizProductionQc.class))).thenReturn(1);
        // 录入后重推导状态：首测最新 OK、成品测未测 → 整体未通过、不转待入库
        when(qcMapper.selectList(any())).thenReturn(List.of(
                qc(7L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_OK)));

        service.record(dto());

        verify(qcMapper).insert(any(BizProductionQc.class));
        verify(orderMapper, never()).update(any(), any());
    }

    private LoginResponse.UserInfoVO user() {
        LoginResponse.UserInfoVO u = new LoginResponse.UserInfoVO();
        u.setId(9L);
        u.setRealName("质检员");
        return u;
    }

    private QcSaveDTO dto() {
        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);
        dto.setTestPoint("first");
        dto.setResult("OK");
        return dto;
    }

    @Test
    void buildStateBatch_emptyInputSkipsQuery() {
        assertTrue(service.buildStateBatch(List.of()).isEmpty());
        assertTrue(service.buildStateBatch(null).isEmpty());
        verify(qcMapper, never()).selectList(any());
    }

    // ---------- D73：已终止订单冻结（质检拦截） ----------

    @Test
    void record_terminatedOrder_throws() {
        // D73：已终止单质检冻结（ensureTestable 先于测点/结果校验，dto 仅需 orderId）
        BizProductionOrder order = order(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.record(dto));
        assertTrue(ex.getMessage().contains("已终止"));
    }
}
