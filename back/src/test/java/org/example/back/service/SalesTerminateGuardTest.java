package org.example.back.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/** Session 58: SalesTerminateGuard freeze branches (line terminated / order voided / order deleted). */
@ExtendWith(MockitoExtension.class)
class SalesTerminateGuardTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, BizProductionOrder.class);
        TableInfoHelper.initTableInfo(assistant, BizSalesDetail.class);
        TableInfoHelper.initTableInfo(assistant, BizSales.class);
    }

    @Mock private BizProductionOrderMapper bizProductionOrderMapper;
    @Mock BizSalesDetailMapper bizSalesDetailMapper;
    @Mock BizSalesMapper bizSalesMapper;
    @InjectMocks SalesTerminateGuard guard;

    private BizProductionOrder order(Long salesOrderId) {
        BizProductionOrder o = new BizProductionOrder();
        o.setId(1L);
        o.setSalesOrderId(salesOrderId);
        o.setSalesDetailId(11L);
        o.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        return o;
    }

    @Test
    void notFrozenWhenNoSalesLink() {
        assertFalse(guard.freezeInfo(order(null)).isFrozen());
    }

    @Test
    void frozenWhenSalesOrderDeleted() {
        when(bizSalesMapper.selectById(100L)).thenReturn(null);
        SalesTerminateGuard.FreezeInfo info = guard.freezeInfo(order(100L));
        assertTrue(info.isFrozen());
        assertEquals("关联的销售单已取消", info.getReason());
    }

    @Test
    void frozenWhenSalesOrderVoided() {
        BizSales sales = new BizSales();
        sales.setId(100L);
        sales.setBizStatus(BizSales.BIZ_STATUS_VOIDED);
        when(bizSalesMapper.selectById(100L)).thenReturn(sales);
        SalesTerminateGuard.FreezeInfo info = guard.freezeInfo(order(100L));
        assertTrue(info.isFrozen());
        assertEquals("关联的销售单已作废", info.getReason());
    }

    @Test
    void frozenWhenLineTerminated() {
        BizSales sales = new BizSales();
        sales.setId(100L);
        sales.setBizStatus(BizSales.BIZ_STATUS_NORMAL);
        when(bizSalesMapper.selectById(100L)).thenReturn(sales);
        BizSalesDetail line = new BizSalesDetail();
        line.setTerminateStatus(BizSalesDetail.TERMINATE_TERMINATED);
        line.setTerminateReason("客户取消");
        when(bizSalesDetailMapper.selectById(11L)).thenReturn(line);
        SalesTerminateGuard.FreezeInfo info = guard.freezeInfo(order(100L));
        assertTrue(info.isFrozen());
        assertEquals("关联的销售明细行已终止", info.getReason());
        assertEquals("客户取消", info.getTerminateReason());
    }

    @Test
    void notFrozenWhenLineActive() {
        BizSales sales = new BizSales();
        sales.setBizStatus(BizSales.BIZ_STATUS_NORMAL);
        when(bizSalesMapper.selectById(100L)).thenReturn(sales);
        BizSalesDetail line = new BizSalesDetail();
        line.setTerminateStatus(BizSalesDetail.TERMINATE_NORMAL);
        when(bizSalesDetailMapper.selectById(11L)).thenReturn(line);
        assertFalse(guard.freezeInfo(order(100L)).isFrozen());
    }

    @Test
    void guardMessageLineTerminated() {
        BizSales sales = new BizSales();
        sales.setBizStatus(BizSales.BIZ_STATUS_NORMAL);
        when(bizSalesMapper.selectById(100L)).thenReturn(sales);
        BizSalesDetail line = new BizSalesDetail();
        line.setTerminateStatus(BizSalesDetail.TERMINATE_TERMINATED);
        when(bizSalesDetailMapper.selectById(11L)).thenReturn(line);
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.ensureSalesLineActive(order(100L)));
        assertEquals("该任务单关联的销售明细行已终止，禁止继续消耗资源，请在生产任务单列表执行终止操作（退料不受影响）", ex.getMessage());
    }

    @Test
    void guardMessageOrderVoided() {
        BizSales sales = new BizSales();
        sales.setBizStatus(BizSales.BIZ_STATUS_VOIDED);
        when(bizSalesMapper.selectById(100L)).thenReturn(sales);
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.ensureSalesLineActive(order(100L)));
        assertEquals("该任务单关联的销售单已作废，禁止继续消耗资源，请在生产任务单列表执行终止操作（退料不受影响）", ex.getMessage());
    }

    @Test
    void guardMessageOrderDeleted() {
        when(bizSalesMapper.selectById(100L)).thenReturn(null);
        BusinessException ex = assertThrows(BusinessException.class, () -> guard.ensureSalesLineActive(order(100L)));
        assertEquals("该任务单关联的销售单已取消，禁止继续消耗资源，请在生产任务单列表执行终止操作（退料不受影响）", ex.getMessage());
    }
}
