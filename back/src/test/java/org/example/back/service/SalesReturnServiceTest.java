package org.example.back.service;

import org.example.back.dto.LoginResponse;
import org.example.back.dto.SalesReturnSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.entity.BizSalesReturn;
import org.example.back.entity.BizSalesReturnDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSalesReturnDetailMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D110 决策③：客退单多行化（biz_sales_return_detail）——按行退、行级可退量校验、
 * 越单拦截、行成本优先 SOURCE_SALE、确认入库逐行加库存、作废逐行回冲。
 */
@ExtendWith(MockitoExtension.class)
class SalesReturnServiceTest {

    @Mock private BizSalesReturnMapper bizSalesReturnMapper;
    @Mock private BizSalesReturnDetailMapper bizSalesReturnDetailMapper;
    @Mock private BizSalesDetailMapper bizSalesDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizApprovalOrderMapper bizApprovalOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;

    @InjectMocks private SalesReturnService service;

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
                BizSalesReturn.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizSalesReturnDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizApprovalOrder.class);
    }

    private BizSales shippedSourceSales() {
        BizSales sales = new BizSales();
        sales.setId(501L);
        sales.setSalesNo("XS260910001");
        sales.setCustomerName("客户甲");
        sales.setBizStatus(1);
        sales.setConfirmStatus(SalesService.CONFIRM_SHIPPED);
        sales.setOperationTime(LocalDateTime.of(2026, 9, 10, 9, 0));
        return sales;
    }

    private BizSalesDetail sourceLine(long id, long goodsId, String name, int quantity,
                                      String unitPrice, String costUnitPrice) {
        BizSalesDetail line = new BizSalesDetail();
        line.setId(id);
        line.setSalesId(501L);
        line.setGoodsId(goodsId);
        line.setGoodsName(name);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        line.setCostUnitPrice(costUnitPrice == null ? null : new BigDecimal(costUnitPrice));
        return line;
    }

    private SalesReturnSaveDTO.Item item(long sourceDetailId, int quantity) {
        SalesReturnSaveDTO.Item item = new SalesReturnSaveDTO.Item();
        item.setSourceSalesDetailId(sourceDetailId);
        item.setQuantity(quantity);
        return item;
    }

    private SalesReturnSaveDTO dto(long sourceSalesId, List<SalesReturnSaveDTO.Item> items) {
        SalesReturnSaveDTO dto = new SalesReturnSaveDTO();
        dto.setSourceSalesId(sourceSalesId);
        dto.setItems(items);
        return dto;
    }

    private LoginResponse.UserInfoVO operator() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(7L);
        user.setRealName("销售管理员");
        user.setRole("admin");
        return user;
    }

    private BaseGoods goods(long id, String name, String purchasePrice) {
        BaseGoods goods = new BaseGoods();
        goods.setId(id);
        goods.setStatus(1);
        goods.setGoodsName(name);
        goods.setPurchasePrice(purchasePrice == null ? null : new BigDecimal(purchasePrice));
        return goods;
    }

    // ---------- 建单：多行退货 + 头汇总 + 行成本快照（SOURCE_SALE 优先 / 进价兜底） ----------

    @Test
    void create_multiLine_aggregatesHead_andSnapshotsCostPerLine() {
        when(bizSalesMapper.selectById(501L)).thenReturn(shippedSourceSales());
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of(
                sourceLine(1L, 29L, "PTO153", 5, "100.00", "60.00"),
                sourceLine(2L, 30L, "轴承", 3, "40.00", null)));
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of()); // 无历史退货
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                goods(29L, "PTO153", "55.00"), goods(30L, "轴承", "25.00")));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesReturnMapper.insert(any(BizSalesReturn.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSalesReturn.class).setId(601L);
            return 1;
        });

        service.create(dto(501L, List.of(item(1L, 2), item(2L, 3))));

        ArgumentCaptor<BizSalesReturn> headCaptor = ArgumentCaptor.forClass(BizSalesReturn.class);
        verify(bizSalesReturnMapper).insert(headCaptor.capture());
        assertEquals(5, headCaptor.getValue().getTotalQuantity()); // 2+3
        assertEquals(0, new BigDecimal("320.00").compareTo(headCaptor.getValue().getTotalAmount())); // 2×100+3×40
        assertEquals("XS260910001", headCaptor.getValue().getSourceSalesNo());

        ArgumentCaptor<BizSalesReturnDetail> lineCaptor = ArgumentCaptor.forClass(BizSalesReturnDetail.class);
        verify(bizSalesReturnDetailMapper, times(2)).insert(lineCaptor.capture());
        BizSalesReturnDetail line1 = lineCaptor.getAllValues().get(0);
        assertEquals("SOURCE_SALE", line1.getCostSource()); // D110 决策③：优先原销售行成本
        assertEquals(0, new BigDecimal("60.00").compareTo(line1.getCostUnitPrice()));
        BizSalesReturnDetail line2 = lineCaptor.getAllValues().get(1);
        assertEquals("GOODS_PRICE", line2.getCostSource()); // 原行无成本 → 商品进价兜底
        assertEquals(0, new BigDecimal("25.00").compareTo(line2.getCostUnitPrice()));

        verify(messageService).sendSalesReturnPendingConfirmToWarehouseAdmins(
                anyString(), eq("销售管理员"), eq(601L));
    }

    // ---------- D128：联系人/手机号快照（带出与覆盖） ----------

    @Test
    void create_withoutContact_carriesFromSourceSales() {
        BizSales source = shippedSourceSales();
        source.setCustomerContactName("王五");
        source.setCustomerPhone("13800138000");
        when(bizSalesMapper.selectById(501L)).thenReturn(source);
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(sourceLine(1L, 29L, "PTO153", 5, "100.00", "60.00")));
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of());
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(goods(29L, "PTO153", "55.00")));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesReturnMapper.insert(any(BizSalesReturn.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSalesReturn.class).setId(602L);
            return 1;
        });

        service.create(dto(501L, List.of(item(1L, 1))));

        // 未传 → 从来源销售单带出
        ArgumentCaptor<BizSalesReturn> headCaptor = ArgumentCaptor.forClass(BizSalesReturn.class);
        verify(bizSalesReturnMapper).insert(headCaptor.capture());
        assertEquals("王五", headCaptor.getValue().getCustomerContactName());
        assertEquals("13800138000", headCaptor.getValue().getCustomerPhone());
    }

    @Test
    void create_explicitContact_overridesSourceValues() {
        BizSales source = shippedSourceSales();
        source.setCustomerContactName("王五");
        source.setCustomerPhone("13800138000");
        when(bizSalesMapper.selectById(501L)).thenReturn(source);
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(sourceLine(1L, 29L, "PTO153", 5, "100.00", "60.00")));
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of());
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(goods(29L, "PTO153", "55.00")));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesReturnMapper.insert(any(BizSalesReturn.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSalesReturn.class).setId(603L);
            return 1;
        });

        SalesReturnSaveDTO explicit = dto(501L, List.of(item(1L, 1)));
        explicit.setCustomerContactName("赵六");
        explicit.setCustomerPhone("13900139000");
        service.create(explicit);

        // 显式传参优先于源单值
        ArgumentCaptor<BizSalesReturn> headCaptor = ArgumentCaptor.forClass(BizSalesReturn.class);
        verify(bizSalesReturnMapper).insert(headCaptor.capture());
        assertEquals("赵六", headCaptor.getValue().getCustomerContactName());
        assertEquals("13900139000", headCaptor.getValue().getCustomerPhone());
    }

    @Test
    void create_exceedsLineReturnable_rejected() {
        when(bizSalesMapper.selectById(501L)).thenReturn(shippedSourceSales());
        when(bizSalesDetailMapper.selectList(any()))
                .thenReturn(List.of(sourceLine(1L, 29L, "PTO153", 5, "100.00", "60.00")));
        // 已有效退货 3 件 → 行可退量 2
        BizSalesReturnDetail prior = new BizSalesReturnDetail();
        prior.setReturnId(701L);
        prior.setSourceSalesDetailId(1L);
        prior.setQuantity(3);
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of(prior));
        BizSalesReturn priorReturn = new BizSalesReturn();
        priorReturn.setId(701L);
        priorReturn.setBizStatus(1);
        priorReturn.setConfirmStatus(SalesReturnService.CONFIRM_RECEIVED);
        when(bizSalesReturnMapper.selectBatchIds(anyCollection())).thenReturn(List.of(priorReturn));

        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(501L, List.of(item(1L, 3)))));

        assertTrue(ex.getMessage().contains("超出可退数量，当前最多可退: 2"), "实际: " + ex.getMessage());
        verify(bizSalesReturnMapper, never()).insert(any(BizSalesReturn.class));
    }

    @Test
    void create_sourceLineFromAnotherSales_rejected() {
        when(bizSalesMapper.selectById(501L)).thenReturn(shippedSourceSales());
        // 明细行查询（限定 sales_id=来源单）查不到 → 行不属于该单，越单拦截
        when(bizSalesDetailMapper.selectList(any())).thenReturn(List.of());

        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(501L, List.of(item(999L, 1)))));

        assertTrue(ex.getMessage().contains("禁止越单退货"), "实际: " + ex.getMessage());
    }

    @Test
    void create_duplicateSourceLine_rejected() {
        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(501L, List.of(item(1L, 1), item(1L, 2)))));

        assertTrue(ex.getMessage().contains("只能出现一次"), "实际: " + ex.getMessage());
        verify(bizSalesReturnMapper, never()).insert(any(BizSalesReturn.class));
    }

    // ---------- 确认入库：逐行加库存 ----------

    private BizSalesReturn pendingReturn() {
        BizSalesReturn entity = new BizSalesReturn();
        entity.setId(601L);
        entity.setReturnNo("XT260910001");
        entity.setBizStatus(1);
        entity.setConfirmStatus(SalesReturnService.CONFIRM_PENDING);
        entity.setOperationTime(LocalDateTime.now());
        return entity;
    }

    private BizSalesReturnDetail returnLine(long goodsId, String name, int quantity) {
        BizSalesReturnDetail d = new BizSalesReturnDetail();
        d.setReturnId(601L);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        return d;
    }

    @Test
    void confirm_increasesStockPerLine() {
        when(bizSalesReturnMapper.selectById(601L)).thenReturn(pendingReturn());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizApprovalOrderMapper.selectCount(any())).thenReturn(0L);
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of(
                returnLine(29L, "PTO153", 2), returnLine(30L, "轴承", 3)));
        when(baseGoodsMapper.update(isNull(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizSalesReturnMapper.update(isNull(), any())).thenReturn(1);

        service.confirm(601L);

        verify(baseGoodsMapper, times(2)).update(isNull(), any()); // 每行一次回补
        verify(messageService).revokeUnreadByBiz("sales_return", 601L);
    }

    // ---------- 作废：已入库单逐行回冲库存 ----------

    @Test
    void voidDocument_received_decreasesStockPerLine() {
        BizSalesReturn entity = pendingReturn();
        entity.setConfirmStatus(SalesReturnService.CONFIRM_RECEIVED);
        when(bizSalesReturnMapper.selectById(601L)).thenReturn(entity);
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizSalesReturnMapper.update(isNull(), any())).thenReturn(1);
        when(bizSalesReturnDetailMapper.selectList(any())).thenReturn(List.of(
                returnLine(29L, "PTO153", 2), returnLine(30L, "轴承", 3)));
        when(baseGoodsMapper.update(isNull(), any())).thenReturn(1);

        service.voidDocument(601L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> stockCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, times(2)).update(isNull(), stockCaptor.capture());
        assertTrue(stockCaptor.getAllValues().stream().anyMatch(w ->
                        String.valueOf(w.getSqlSet()).contains("stock - 2")), "应回冲 PTO153 2 件");
        assertTrue(stockCaptor.getAllValues().stream().anyMatch(w ->
                        String.valueOf(w.getSqlSet()).contains("stock - 3")), "应回冲轴承 3 件");
        verify(messageService).revokeUnreadByBiz("sales_return", 601L);
    }

    @Test
    void voidDocument_pending_noStockChange() {
        when(bizSalesReturnMapper.selectById(601L)).thenReturn(pendingReturn());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizSalesReturnMapper.update(isNull(), any())).thenReturn(1);

        service.voidDocument(601L, null);

        verify(baseGoodsMapper, never()).update(isNull(), any()); // 未入库不触库存
        verify(messageService).revokeUnreadByBiz("sales_return", 601L);
    }

    // ---------- 行级可退量选项（SalesService.returnableOptions 联动同口径，此处验已退过滤在选项侧） ----------

    @Test
    void delete_pendingReturn_deletesDirectly() {
        when(bizSalesReturnMapper.selectById(601L)).thenReturn(pendingReturn());
        // requireSalesReturnAdminOrSuperAdmin 走 authzService.requireDeptAdminOrSuperAdmin（void，默认放行）

        service.delete(601L);

        verify(bizSalesReturnMapper).deleteById(601L);
        // review 修复：级联软删退货行，防止孤立行永久阻塞成品删除
        verify(bizSalesReturnDetailMapper).delete(any());
        verify(messageService).revokeUnreadByBiz("sales_return", 601L);
    }
}
