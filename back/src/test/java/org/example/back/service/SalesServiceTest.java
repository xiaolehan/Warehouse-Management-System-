package org.example.back.service;

import org.example.back.dto.LoginResponse;
import org.example.back.dto.SalesSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.SalesDetailVO;
import org.example.back.vo.SalesSourceOptionVO;
import org.example.back.vo.SalesVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D69/D70：建单零库存校验（允许超卖）+ 缺货通知生产管理员。
 * D110：一单多明细行（biz_sales_detail）——同品一行约束/整单头汇总/缺货按单汇总一条消息/
 * 价格偏离整单一笔（列偏离行明细）/逐行扣库存整单确认/按行回补库存。
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BizSalesDetailMapper bizSalesDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizApprovalOrderMapper bizApprovalOrderMapper;
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private SysConfigService sysConfigService;
    @Mock private SalesReturnService salesReturnService;
    @Mock private BizBomMapper bizBomMapper;

    @InjectMocks private SalesService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BaseGoods.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizSales.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizSalesDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizApprovalOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizProductionOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizBom.class);
    }

    private BaseGoods product(long id, String name, int stock, String salePrice) {
        BaseGoods goods = new BaseGoods();
        goods.setId(id);
        goods.setType(GoodsService.GOODS_TYPE_PRODUCT);
        goods.setStatus(1);
        goods.setGoodsName(name);
        goods.setStock(stock);
        goods.setSalePrice(new BigDecimal(salePrice));
        return goods;
    }

    private SalesSaveDTO.Item item(long goodsId, int quantity, String unitPrice) {
        SalesSaveDTO.Item item = new SalesSaveDTO.Item();
        item.setGoodsId(goodsId);
        item.setQuantity(quantity);
        item.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return item;
    }

    private SalesSaveDTO dto(List<SalesSaveDTO.Item> items) {
        SalesSaveDTO dto = new SalesSaveDTO();
        dto.setItems(items);
        dto.setCustomerName("客户甲");
        return dto;
    }

    private LoginResponse.UserInfoVO operator() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(7L);
        user.setRealName("销售管理员");
        user.setRole("admin");
        return user;
    }

    // ---------- D110：建单多行 + 头汇总 + 缺货按单汇总一条消息 ----------

    @Test
    void create_multiLine_aggregatesHeadTotals_andNotifiesShortageOnce() {
        BaseGoods pto = product(29L, "PTO153", 0, "100.00");
        BaseGoods bearing = product(30L, "轴承", 5, "40.00");
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(pto, bearing));
        when(bizPurchaseMapper.latestValidUnitPrices(anyCollection(), any())).thenReturn(java.util.List.of());
        when(sysConfigService.getPriceDeviationThreshold()).thenReturn(new BigDecimal("0.05"));
        when(authService.getUserInfo()).thenReturn(operator());
        // D112：轴承已建档 BOM，PTO153 未建档（缺货文案带建档指引）
        BizBom bom = new BizBom();
        bom.setId(1L);
        bom.setGoodsId(30L);
        when(bizBomMapper.selectList(any())).thenReturn(java.util.List.of(bom));
        when(bizSalesMapper.insert(any(BizSales.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSales.class).setId(501L);
            return 1;
        });

        // PTO153 缺货（0<10）、轴承超卖（5<8）→ 按单汇总一条缺货消息（D110 决策⑤）
        service.create(dto(List.of(item(29L, 10, "100.00"), item(30L, 8, "40.00"))));

        ArgumentCaptor<BizSales> headCaptor = ArgumentCaptor.forClass(BizSales.class);
        verify(bizSalesMapper).insert(headCaptor.capture());
        assertEquals(18, headCaptor.getValue().getTotalQuantity()); // 10+8
        assertEquals(0, new BigDecimal("1320.00").compareTo(headCaptor.getValue().getTotalAmount())); // 1000+320

        verify(bizSalesDetailMapper, times(2)).insert(any(BizSalesDetail.class));
        verify(messageService).sendSalesDemandToProductionAdmins(
                anyString(), eq("PTO153×10（现存 0）（未建档 BOM，需先在 BOM 管理建档）、轴承×8（现存 5）"),
                eq("客户甲"), eq("销售管理员"), eq(501L)); // 一单一缺货消息
        verify(messageService).sendSalesPendingConfirmToWarehouseAdmins(
                anyString(), eq("客户甲"), eq("销售管理员"), eq(501L));
    }

    @Test
    void create_noBomShortageLine_messageCarriesGuidance() {
        BaseGoods pto = product(29L, "新品A", 0, "100.00");
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(pto));
        when(bizPurchaseMapper.latestValidUnitPrices(anyCollection(), any())).thenReturn(java.util.List.of());
        when(sysConfigService.getPriceDeviationThreshold()).thenReturn(new BigDecimal("0.05"));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizBomMapper.selectList(any())).thenReturn(java.util.List.of()); // 无 BOM
        when(bizSalesMapper.insert(any(BizSales.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSales.class).setId(502L);
            return 1;
        });

        service.create(dto(List.of(item(29L, 5, "100.00"))));

        ArgumentCaptor<String> descCap = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendSalesDemandToProductionAdmins(
                anyString(), descCap.capture(), eq("客户甲"), eq("销售管理员"), eq(502L));
        assertTrue(descCap.getValue().contains("新品A×5（现存 0）（未建档 BOM，需先在 BOM 管理建档）"),
                descCap.getValue());
    }

    @Test
    void getById_flagsZeroStockAndNoBom() {
        BizSales head = new BizSales();
        head.setId(501L);
        head.setSalesNo("SAL260921001");
        head.setBizStatus(1);
        head.setConfirmStatus(1);
        when(bizSalesMapper.selectById(501L)).thenReturn(head);
        BizSalesDetail zeroNoBom = new BizSalesDetail();
        zeroNoBom.setId(9001L);
        zeroNoBom.setSalesId(501L);
        zeroNoBom.setGoodsId(29L);
        zeroNoBom.setGoodsName("新品A");
        zeroNoBom.setQuantity(3);
        BizSalesDetail okLine = new BizSalesDetail();
        okLine.setId(9002L);
        okLine.setSalesId(501L);
        okLine.setGoodsId(30L);
        okLine.setGoodsName("PTO153");
        okLine.setQuantity(2);
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of(zeroNoBom, okLine));
        BaseGoods g29 = product(29L, "新品A", 0, "50.00");
        BaseGoods g30 = product(30L, "PTO153", 10, "100.00");
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(g29, g30));
        BizBom bom = new BizBom();
        bom.setId(1L);
        bom.setGoodsId(30L);
        when(bizBomMapper.selectList(any())).thenReturn(List.of(bom));

        SalesVO vo = service.getById(501L);

        SalesDetailVO l1 = vo.getDetails().stream().filter(d -> d.getGoodsId() == 29L).findFirst().orElseThrow();
        SalesDetailVO l2 = vo.getDetails().stream().filter(d -> d.getGoodsId() == 30L).findFirst().orElseThrow();
        assertTrue(l1.getZeroStock());
        assertFalse(l1.getHasBom());
        assertFalse(l2.getZeroStock());
        assertTrue(l2.getHasBom());
    }

    @Test
    void create_duplicateGoodsLine_rejected() {
        // D110 决策①：同一成品一单只允许一行
        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(List.of(item(29L, 5, "100.00"), item(29L, 3, "100.00")))));
        assertTrue(ex.getMessage().contains("只能有一行"), "实际: " + ex.getMessage());

        verify(bizSalesMapper, never()).insert(any(BizSales.class));
        verify(bizSalesDetailMapper, never()).insert(any(BizSalesDetail.class));
    }

    @Test
    void create_sufficientStock_noProductionNotification() {
        when(baseGoodsMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(product(29L, "PTO153", 100, "100.00")));
        when(bizPurchaseMapper.latestValidUnitPrices(anyCollection(), any())).thenReturn(java.util.List.of());
        when(sysConfigService.getPriceDeviationThreshold()).thenReturn(new BigDecimal("0.05"));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesMapper.insert(any(BizSales.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSales.class).setId(501L);
            return 1;
        });

        service.create(dto(List.of(item(29L, 10, "100.00"))));

        verify(messageService, never()).sendSalesDemandToProductionAdmins(
                anyString(), anyString(), any(), anyString(), anyLong());
        verify(messageService).sendSalesPendingConfirmToWarehouseAdmins(anyString(), any(), anyString(), eq(501L));
    }

    // ---------- D110 决策④：价格偏离整单一笔审批，request_reason 列偏离行 ----------

    @Test
    void create_multiDeviationLines_singleApprovalListingLines() {
        BaseGoods pto = product(29L, "PTO153", 100, "100.00");
        BaseGoods bearing = product(30L, "轴承", 100, "40.00");
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(pto, bearing));
        when(bizPurchaseMapper.latestValidUnitPrices(anyCollection(), any())).thenReturn(java.util.List.of());
        when(sysConfigService.getPriceDeviationThreshold()).thenReturn(new BigDecimal("0.05"));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesMapper.insert(any(BizSales.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSales.class).setId(501L);
            return 1;
        });

        // 两行均偏离标准售价（+50% / -25%）
        service.create(dto(List.of(item(29L, 5, "150.00"), item(30L, 4, "30.00"))));

        ArgumentCaptor<BizApprovalOrder> approvalCaptor = ArgumentCaptor.forClass(BizApprovalOrder.class);
        verify(bizApprovalOrderMapper, times(1)).insert(approvalCaptor.capture()); // 整单一笔
        BizApprovalOrder approval = approvalCaptor.getValue();
        assertEquals("sales", approval.getBizType());
        assertTrue(approval.getRequestReason().contains("第1行 PTO153 偏离 50%"),
                "实际: " + approval.getRequestReason());
        assertTrue(approval.getRequestReason().contains("第2行 轴承 偏离 25%"),
                "实际: " + approval.getRequestReason());
        verify(messageService).sendPriceDeviationToSuperAdmin(
                anyString(), eq("销售管理员"), anyString(), eq(501L));
    }

    // ---------- D110 决策②：确认出库逐行扣库存（任一行不足整单失败） ----------

    private BizSales pendingSales() {
        BizSales entity = new BizSales();
        entity.setId(501L);
        entity.setSalesNo("XS260910001");
        entity.setBizStatus(1);
        entity.setConfirmStatus(SalesService.CONFIRM_PENDING);
        entity.setOperationTime(LocalDateTime.now());
        return entity;
    }

    private BizSalesDetail detail(long id, long goodsId, String name, int quantity) {
        BizSalesDetail d = new BizSalesDetail();
        d.setId(id);
        d.setSalesId(501L);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        return d;
    }

    @Test
    void confirm_decreasesStockPerLine() {
        when(bizSalesMapper.selectById(501L)).thenReturn(pendingSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizApprovalOrderMapper.selectCount(any())).thenReturn(0L);
        when(bizApprovalOrderMapper.selectList(any())).thenReturn(List.of());
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "PTO153", 5), detail(2L, 30L, "轴承", 3)));
        when(baseGoodsMapper.update(isNull(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(501L);

        verify(baseGoodsMapper, times(2)).update(isNull(), any()); // 每行一次条件扣减
        verify(messageService).revokeUnreadByBiz("sales", 501L);
    }

    // ---------- D127：出库完成 → 销售管理员提醒 ----------

    @Test
    void confirm_notifiesSalesAdminsAfterRevokingPendingMessages() {
        BizSales entity = pendingSales();
        entity.setCustomerName("华东一店");
        when(bizSalesMapper.selectById(501L)).thenReturn(entity);
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizApprovalOrderMapper.selectCount(any())).thenReturn(0L);
        when(bizApprovalOrderMapper.selectList(any())).thenReturn(List.of());
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "PTO153", 5)));
        when(baseGoodsMapper.update(isNull(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(501L);

        // D127：先撤旧待确认消息再发提醒——两者同 biz 绑定，顺序颠倒提醒会被本次撤销误撤
        InOrder inOrder = inOrder(messageService);
        inOrder.verify(messageService).revokeUnreadByBiz("sales", 501L);
        inOrder.verify(messageService).sendSalesShippedToSalesAdmins(
                eq("XS260910001"), eq("华东一店"), eq("销售管理员"), eq(501L));
    }

    @Test
    void confirm_alreadyShipped_rejectedAndNotNotifies() {
        when(bizSalesMapper.selectById(501L)).thenReturn(shippedSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);

        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class, () -> service.confirm(501L));
        assertTrue(ex.getMessage().contains("禁止重复确认"), "实际: " + ex.getMessage());
        verify(messageService, never()).sendSalesShippedToSalesAdmins(anyString(), any(), anyString(), anyLong());
    }

    // ---------- D110 决策⑨前传：已出库单作废按行回补库存；D73 通知带汇总成品描述 ----------

    private BizSales shippedSales() {
        BizSales entity = pendingSales();
        entity.setConfirmStatus(SalesService.CONFIRM_SHIPPED);
        entity.setConfirmTime(LocalDateTime.now());
        return entity;
    }

    @Test
    void voidDocument_shipped_restoresStockPerLine() {
        when(bizSalesMapper.selectById(501L)).thenReturn(shippedSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizSalesMapper.update(isNull(), any())).thenReturn(1);
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "PTO153", 5), detail(2L, 30L, "轴承", 3)));
        when(baseGoodsMapper.update(isNull(), any())).thenReturn(1);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of());

        service.voidDocument(501L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> stockCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, times(2)).update(isNull(), stockCaptor.capture());
        assertTrue(stockCaptor.getAllValues().stream().anyMatch(w ->
                        String.valueOf(w.getSqlSet()).contains("stock + 5")), "应回补 PTO153 5 件");
        assertTrue(stockCaptor.getAllValues().stream().anyMatch(w ->
                        String.valueOf(w.getSqlSet()).contains("stock + 3")), "应回补轴承 3 件");
        verify(messageService).revokeUnreadByBiz("sales", 501L);
    }

    @Test
    void delete_withUnfinishedLinkedOrder_notifiesWithAggregatedGoodsDesc() {
        BizSales entity = pendingSales();
        when(bizSalesMapper.selectById(501L)).thenReturn(entity);
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "PTO153", 5), detail(2L, 30L, "轴承", 3)));
        BizProductionOrder order = new BizProductionOrder();
        order.setId(88L);
        order.setOrderNo("PRO260910001");
        order.setStatus(1);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of(order));

        service.delete(501L);

        // D110：跨部门消息成品描述按明细行汇总
        verify(messageService).sendSalesCancelledToProductionAdmins(
                eq("XS260910001"), eq("PTO153×5、轴承×3"), eq("PRO260910001"), eq(88L), eq("删除"));
        verify(bizSalesMapper).deleteById(501L);
        // review 修复：级联软删明细行，防止孤立行永久阻塞成品删除
        verify(bizSalesDetailMapper).delete(any());
    }

    @Test
    void delete_withFinishedLinkedOrder_doesNotNotify() {
        when(bizSalesMapper.selectById(501L)).thenReturn(pendingSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        // SQL 层 in 过滤，已完工/已作废/已报废/已终止的关联单不会返回
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of());

        service.delete(501L);

        verify(messageService, never()).sendSalesCancelledToProductionAdmins(
                any(), any(), any(), any(), any());
    }

    // ---------- D110：生产建单关联下拉（(头单,成品) 唯一解析，顶层 quantity=行数量） ----------

    @Test
    void linkableOptions_resolvesLineQuantityForProductionForm() {
        BizSales head = new BizSales();
        head.setId(501L);
        head.setSalesNo("XS260910001");
        head.setCustomerName("客户甲");
        head.setOperationTime(LocalDateTime.of(2026, 9, 10, 9, 0));
        when(bizSalesMapper.selectList(any())).thenReturn(List.of(head));
        BizSalesDetail line = detail(1L, 29L, "PTO153", 6);
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of(line));

        List<SalesSourceOptionVO> options = service.linkableOptions(29L);

        assertEquals(1, options.size());
        assertEquals(6, options.get(0).getQuantity()); // 前端标签「销售单（客户 × 数量）」数据源
    }
}
