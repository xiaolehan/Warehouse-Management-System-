package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.QcSaveDTO;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionQc;
import org.example.back.mapper.BizProductionOrderMapper;
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
 * 质检状态推导（D64 列表修复）单测：buildStateBatch 按单分组、一次 in 查询。
 */
@ExtendWith(MockitoExtension.class)
class QcServiceTest {

    @Mock private BizProductionQcMapper qcMapper;
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

    @Test
    void buildStateBatch_groupsRecordsPerOrder() {
        when(qcMapper.selectList(any())).thenReturn(List.of(
                qc(1L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_OK),
                qc(2L, BizProductionQc.POINT_FIRST, BizProductionQc.RESULT_NG),
                qc(2L, BizProductionQc.POINT_FINAL, BizProductionQc.RESULT_OK)
        ));

        Map<Long, QcStateVO> states = service.buildStateBatch(List.of(order(1L), order(2L)));

        // 两张单只触发一次 in 查询（列表页不逐行 N 次查询）
        verify(qcMapper, times(1)).selectList(any());

        // 单 1：首测合格、成品测未测 → 整体未通过
        QcStateVO s1 = states.get(1L);
        assertTrue(s1.getFirstPassed());
        assertEquals("ok", s1.getFirstStatus());
        assertEquals("untested", s1.getFinalStatus());
        assertFalse(s1.getPassed());
        assertEquals(1, s1.getRecords().size());

        // 单 2：首测 NG 待处置、成品测合格 → 整体仍未通过
        QcStateVO s2 = states.get(2L);
        assertEquals("ng", s2.getFirstStatus());
        assertFalse(s2.getFirstPassed());
        assertTrue(s2.getFinalPassed());
        assertFalse(s2.getPassed());
        assertEquals(2, s2.getRecords().size());
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
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(orderMapper.selectById(7L)).thenReturn(order);

        QcSaveDTO dto = new QcSaveDTO();
        dto.setOrderId(7L);
        dto.setTestPoint("first");
        dto.setResult("OK");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.record(dto));
        assertTrue(ex.getMessage().contains("已终止"));
    }
}
