package org.example.back.service;

import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.example.back.vo.SalesTimelineVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D71：履约时间线 + 预计可交付时间推算（现货/缺料/齐套/未排产/手工修正 分支）。
 */
@ExtendWith(MockitoExtension.class)
class SalesTimelineServiceTest {

    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizProductionOrderMapper productionOrderMapper;
    @Mock private BizBomMapper bomMapper;
    @Mock private BizPurchaseRequestMapper purchaseRequestMapper;
    @Mock private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;
    @Mock private ProductionOrderService productionOrderService;
    @Mock private ProductionStepService productionStepService;
    @Mock private QcService qcService;
    @Mock private AuthzService authzService;

    @InjectMocks private SalesTimelineService service;

    private BizSales sales(int quantity, int confirmStatus) {
        BizSales sales = new BizSales();
        sales.setId(501L);
        sales.setSalesNo("XS260910001");
        sales.setGoodsId(29L);
        sales.setGoodsName("PTO153");
        sales.setQuantity(quantity);
        sales.setBizStatus(1);
        sales.setConfirmStatus(confirmStatus);
        sales.setOperationTime(LocalDateTime.of(2026, 9, 10, 9, 0));
        when(bizSalesMapper.selectById(501L)).thenReturn(sales);
        return sales;
    }

    private void goodsWithStock(int stock) {
        BaseGoods goods = new BaseGoods();
        goods.setId(29L);
        goods.setStock(stock);
        when(baseGoodsMapper.selectById(29L)).thenReturn(goods);
    }

    private BizProductionOrder order(int status) {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(301L);
        order.setOrderNo("PO260910001");
        order.setGoodsId(29L);
        order.setQuantity(10);
        order.setStatus(status);
        order.setSalesOrderId(501L);
        order.setCreateTime(LocalDateTime.of(2026, 9, 10, 10, 0));
        order.setUpdateTime(LocalDateTime.of(2026, 9, 11, 10, 0));
        when(productionOrderMapper.selectOne(any())).thenReturn(order);
        return order;
    }

    private void bomWithLeadDays(Integer leadDays) {
        BizBom bom = new BizBom();
        bom.setId(1L);
        bom.setGoodsId(29L);
        bom.setLeadDays(leadDays);
        when(bomMapper.selectOne(any())).thenReturn(bom);
    }

    @Test
    void noOrder_sufficientStock_twoNodesReadyToShip() {
        sales(5, SalesService.CONFIRM_PENDING);
        goodsWithStock(10);
        when(productionOrderMapper.selectOne(any())).thenReturn(null);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(2, vo.getNodes().size()); // 下单 → 发货
        assertEquals("下单", vo.getNodes().get(0).getTitle());
        assertEquals("发货", vo.getNodes().get(1).getTitle());
        assertTrue(vo.getEstimatedDeliveryText().contains("立即可发"), "实际: " + vo.getEstimatedDeliveryText());
        assertEquals("none", vo.getEstimatedSource());
    }

    @Test
    void noOrder_insufficientStock_pendingScheduling() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        when(productionOrderMapper.selectOne(any())).thenReturn(null);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(3, vo.getNodes().size()); // 下单 → 排产(待) → 发货(待)
        assertEquals("pending", vo.getNodes().get(1).getStatus());
        assertEquals("待生产排产", vo.getEstimatedDeliveryText());
    }

    @Test
    void shippedOrder_showsShipped() {
        sales(5, SalesService.CONFIRM_SHIPPED);
        goodsWithStock(10);
        when(productionOrderMapper.selectOne(any())).thenReturn(null);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals("done", vo.getNodes().get(1).getStatus());
        assertEquals("已发货", vo.getEstimatedDeliveryText());
    }

    @Test
    void linkedOrder_manualExpectedCompletionWins() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        BizProductionOrder order = order(BizProductionOrder.STATUS_IN_PROGRESS);
        LocalDateTime manual = LocalDateTime.of(2026, 9, 25, 18, 0);
        order.setExpectedCompletionTime(manual);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(manual, vo.getEstimatedDeliveryTime());
        assertEquals("manual", vo.getEstimatedSource());
        assertTrue(vo.getEstimatedDeliveryText().contains("生产确认"), "实际: " + vo.getEstimatedDeliveryText());
        assertEquals(8, vo.getNodes().size());
    }

    @Test
    void linkedOrder_done_readyToShip() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_DONE);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertTrue(vo.getEstimatedDeliveryText().contains("立即可发"), "实际: " + vo.getEstimatedDeliveryText());
        assertEquals("done", vo.getNodes().get(6).getStatus()); // 成品入库
    }

    @Test
    void linkedOrder_noLeadDays_pendingEvaluation() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(null);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertTrue(vo.getEstimatedDeliveryText().contains("待生产评估"), "实际: " + vo.getEstimatedDeliveryText());
        assertNull(vo.getEstimatedDeliveryTime());
    }

    @Test
    void linkedOrder_shortage_estimatedByMaxArrivalPlusLeadDays() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(5);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());
        when(productionOrderService.computeShortageForOrder(301L)).thenReturn(List.of(new KitShortageVO()));

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(77L);
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of(request));
        BizPurchaseRequestDetail detail = new BizPurchaseRequestDetail();
        detail.setExpectedArrivalTime(LocalDateTime.of(2026, 9, 20, 0, 0));
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(detail));

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(LocalDateTime.of(2026, 9, 25, 0, 0), vo.getEstimatedDeliveryTime());
        assertEquals("system", vo.getEstimatedSource());
        assertTrue(vo.getEstimatedDeliveryText().contains("系统推算"), "实际: " + vo.getEstimatedDeliveryText());
        assertEquals("current", vo.getNodes().get(2).getStatus()); // 物料准备 进行中
    }

    @Test
    void linkedOrder_shortageNoArrival_pendingPurchaseClaim() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(5);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());
        when(productionOrderService.computeShortageForOrder(301L)).thenReturn(List.of(new KitShortageVO()));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals("缺料待采购确认到货时间", vo.getEstimatedDeliveryText());
        assertNull(vo.getEstimatedDeliveryTime());
    }

    @Test
    void linkedOrder_inProgress_estimatedByStartPlusLeadDays() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_IN_PROGRESS);
        bomWithLeadDays(3);
        ProductionStepVO step = new ProductionStepVO();
        step.setType("manual");
        step.setDone(true);
        step.setOperateTime(LocalDateTime.of(2026, 9, 12, 8, 0));
        when(productionStepService.listSteps(any())).thenReturn(List.of(step));
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(LocalDateTime.of(2026, 9, 15, 8, 0), vo.getEstimatedDeliveryTime());
        assertEquals("system", vo.getEstimatedSource());
        assertEquals("done", vo.getNodes().get(3).getStatus()); // 开工
        assertEquals(LocalDateTime.of(2026, 9, 12, 8, 0), vo.getNodes().get(3).getTime());
    }

    @Test
    void linkedOrder_voidedOrder_backToPendingScheduling() {
        sales(10, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_VOIDED);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(3, vo.getNodes().size());
        assertTrue(vo.getNodes().get(1).getDescription().contains("已作废"), "实际: " + vo.getNodes().get(1).getDescription());
        assertEquals("待生产排产", vo.getEstimatedDeliveryText());
    }

    // ---------- D73：关联生产单已终止 → 时间线回退待排产 ----------

    @Test
    void getTimeline_terminatedLinkedOrder_fallsBackToReschedule() {
        sales(5, SalesService.CONFIRM_PENDING);
        goodsWithStock(0);
        BizProductionOrder order = new BizProductionOrder();
        order.setId(88L);
        order.setOrderNo("PRO260910001");
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(productionOrderMapper.selectOne(any())).thenReturn(order);

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals("待生产排产", vo.getEstimatedDeliveryText());
        assertTrue(vo.getNodes().stream().anyMatch(n -> "scheduled".equals(n.getKey())
                && n.getDescription() != null && n.getDescription().contains("已终止")));
    }
}
