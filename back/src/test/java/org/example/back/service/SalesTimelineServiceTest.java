package org.example.back.service;

import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.example.back.vo.SalesTimelineLineVO;
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
 * D110 决策⑦：时间线按明细行逐行展示——一单 N 行 → N 根时间线，单行节点结构与旧版一致。
 */
@ExtendWith(MockitoExtension.class)
class SalesTimelineServiceTest {

    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BizSalesDetailMapper bizSalesDetailMapper;
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

    private BizSales sales(int confirmStatus) {
        BizSales sales = new BizSales();
        sales.setId(501L);
        sales.setSalesNo("XS260910001");
        sales.setBizStatus(1);
        sales.setConfirmStatus(confirmStatus);
        sales.setOperationTime(LocalDateTime.of(2026, 9, 10, 9, 0));
        when(bizSalesMapper.selectById(501L)).thenReturn(sales);
        return sales;
    }

    private BizSalesDetail detail(long id, long goodsId, String name, int quantity) {
        BizSalesDetail d = new BizSalesDetail();
        d.setId(id);
        d.setSalesId(501L);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of(d));
        return d;
    }

    private void goodsWithStock(int stock) {
        BaseGoods goods = new BaseGoods();
        goods.setId(29L);
        goods.setStock(stock);
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods));
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
        when(productionOrderMapper.selectList(any())).thenReturn(List.of(order));
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
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 5);
        goodsWithStock(10);
        when(productionOrderMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals("PTO153", line.getGoodsName());
        assertEquals(5, line.getQuantity());
        assertEquals(10, line.getStock());
        assertEquals(2, line.getNodes().size()); // 下单 → 发货
        assertEquals("下单", line.getNodes().get(0).getTitle());
        assertEquals("发货", line.getNodes().get(1).getTitle());
        assertTrue(line.getEstimatedDeliveryText().contains("立即可发"), "实际: " + line.getEstimatedDeliveryText());
        assertEquals("none", line.getEstimatedSource());
    }

    @Test
    void noOrder_insufficientStock_pendingScheduling() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        when(productionOrderMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals(3, line.getNodes().size()); // 下单 → 排产(待) → 发货(待)
        assertEquals("pending", line.getNodes().get(1).getStatus());
        assertEquals("待生产排产", line.getEstimatedDeliveryText());
    }

    @Test
    void shippedOrder_showsShipped() {
        BizSales s = sales(SalesService.CONFIRM_SHIPPED);
        java.time.LocalDateTime confirmTime = java.time.LocalDateTime.of(2026, 9, 12, 16, 0);
        s.setConfirmTime(confirmTime);
        detail(1L, 29L, "PTO153", 5);
        goodsWithStock(10);
        when(productionOrderMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals("done", line.getNodes().get(1).getStatus());
        assertEquals(confirmTime, line.getNodes().get(1).getTime()); // review 修复：无关联单发货节点须带确认出库时间
        assertEquals("已发货", line.getEstimatedDeliveryText());
    }

    // ---------- D110 决策⑦：多明细行 → 多根时间线 ----------

    @Test
    void multiLineOrder_oneTimelinePerLine() {
        sales(SalesService.CONFIRM_PENDING);
        BizSalesDetail line1 = detail(1L, 29L, "PTO153", 5);
        BizSalesDetail line2 = new BizSalesDetail();
        line2.setId(2L);
        line2.setSalesId(501L);
        line2.setGoodsId(30L);
        line2.setGoodsName("轴承");
        line2.setQuantity(3);
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of(line1, line2));
        BaseGoods g29 = new BaseGoods();
        g29.setId(29L);
        g29.setStock(10);
        BaseGoods g30 = new BaseGoods();
        g30.setId(30L);
        g30.setStock(0);
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g29, g30));
        when(productionOrderMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);

        assertEquals(2, vo.getLines().size());
        assertEquals("PTO153", vo.getLines().get(0).getGoodsName());
        assertEquals(5, vo.getLines().get(0).getQuantity());
        assertTrue(vo.getLines().get(0).getEstimatedDeliveryText().contains("立即可发"));
        assertEquals("轴承", vo.getLines().get(1).getGoodsName());
        assertEquals(3, vo.getLines().get(1).getQuantity());
        assertEquals("待生产排产", vo.getLines().get(1).getEstimatedDeliveryText()); // 缺货行独立判断
    }

    @Test
    void linkedOrder_manualExpectedCompletionWins() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        BizProductionOrder order = order(BizProductionOrder.STATUS_IN_PROGRESS);
        LocalDateTime manual = LocalDateTime.of(2026, 9, 25, 18, 0);
        order.setExpectedCompletionTime(manual);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals(manual, line.getEstimatedDeliveryTime());
        assertEquals("manual", line.getEstimatedSource());
        assertTrue(line.getEstimatedDeliveryText().contains("生产确认"), "实际: " + line.getEstimatedDeliveryText());
        assertEquals(8, line.getNodes().size());
    }

    @Test
    void linkedOrder_done_readyToShip() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_DONE);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertTrue(line.getEstimatedDeliveryText().contains("立即可发"), "实际: " + line.getEstimatedDeliveryText());
        assertEquals("done", line.getNodes().get(6).getStatus()); // 成品入库
    }

    @Test
    void linkedOrder_noLeadDays_pendingEvaluation() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(null);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertTrue(line.getEstimatedDeliveryText().contains("待生产评估"), "实际: " + line.getEstimatedDeliveryText());
        assertNull(line.getEstimatedDeliveryTime());
    }

    @Test
    void linkedOrder_shortage_estimatedByMaxArrivalPlusLeadDays() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(5);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());
        when(productionOrderService.computeShortageForOrder(301L)).thenReturn(List.of(new KitShortageVO()));

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(77L);
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of(request));
        BizPurchaseRequestDetail purchaseDetail = new BizPurchaseRequestDetail();
        purchaseDetail.setExpectedArrivalTime(LocalDateTime.of(2026, 9, 20, 0, 0));
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(purchaseDetail));

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals(LocalDateTime.of(2026, 9, 25, 0, 0), line.getEstimatedDeliveryTime());
        assertEquals("system", line.getEstimatedSource());
        assertTrue(line.getEstimatedDeliveryText().contains("系统推算"), "实际: " + line.getEstimatedDeliveryText());
        assertEquals("current", line.getNodes().get(2).getStatus()); // 物料准备 进行中
    }

    @Test
    void linkedOrder_shortageNoArrival_pendingPurchaseClaim() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_PENDING);
        bomWithLeadDays(5);
        when(productionStepService.listSteps(any())).thenReturn(null);
        when(qcService.buildState(any())).thenReturn(new QcStateVO());
        when(productionOrderService.computeShortageForOrder(301L)).thenReturn(List.of(new KitShortageVO()));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of());

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals("缺料待采购确认到货时间", line.getEstimatedDeliveryText());
        assertNull(line.getEstimatedDeliveryTime());
    }

    @Test
    void linkedOrder_inProgress_estimatedByStartPlusLeadDays() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
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
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals(LocalDateTime.of(2026, 9, 15, 8, 0), line.getEstimatedDeliveryTime());
        assertEquals("system", line.getEstimatedSource());
        assertEquals("done", line.getNodes().get(3).getStatus()); // 开工
        assertEquals(LocalDateTime.of(2026, 9, 12, 8, 0), line.getNodes().get(3).getTime());
    }

    @Test
    void linkedOrder_voidedOrder_backToPendingScheduling() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 10);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_VOIDED);

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals(3, line.getNodes().size());
        assertTrue(line.getNodes().get(1).getDescription().contains("已作废"), "实际: " + line.getNodes().get(1).getDescription());
        assertEquals("待生产排产", line.getEstimatedDeliveryText());
    }

    // ---------- D73：关联生产单已终止 → 时间线回退待排产 ----------

    @Test
    void getTimeline_terminatedLinkedOrder_fallsBackToReschedule() {
        sales(SalesService.CONFIRM_PENDING);
        detail(1L, 29L, "PTO153", 5);
        goodsWithStock(0);
        order(BizProductionOrder.STATUS_TERMINATED);

        SalesTimelineVO vo = service.getTimeline(501L);
        SalesTimelineLineVO line = vo.getLines().get(0);

        assertEquals("待生产排产", line.getEstimatedDeliveryText());
        assertTrue(line.getNodes().stream().anyMatch(n -> "scheduled".equals(n.getKey())
                && n.getDescription() != null && n.getDescription().contains("已终止")));
    }
}
