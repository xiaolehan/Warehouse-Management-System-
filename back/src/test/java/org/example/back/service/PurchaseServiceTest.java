package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseMapper;
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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D111：进货单头行结构（biz_purchase / biz_purchase_detail）——
 * 多行建单头汇总/同物料一行约束/逐行加库存与进价回写/作废逐行回冲+进价重算/级联软删。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseServiceTest {

    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizPurchaseDetailMapper bizPurchaseDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BaseSupplierMapper baseSupplierMapper;
    @Mock private PurchaseReturnService purchaseReturnService;
    @Mock private BizApprovalOrderMapper bizApprovalOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;

    @InjectMocks private PurchaseService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BaseGoods.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPurchase.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPurchaseDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizApprovalOrder.class);
    }

    private BaseGoods material(long id, String name, int stock, String purchasePrice) {
        BaseGoods goods = new BaseGoods();
        goods.setId(id);
        goods.setType(GoodsService.GOODS_TYPE_MATERIAL);
        goods.setStatus(1);
        goods.setGoodsName(name);
        goods.setStock(stock);
        goods.setPurchasePrice(purchasePrice == null ? null : new BigDecimal(purchasePrice));
        return goods;
    }

    private PurchaseSaveDTO.LineDTO line(long goodsId, int quantity, String unitPrice) {
        PurchaseSaveDTO.LineDTO line = new PurchaseSaveDTO.LineDTO();
        line.setGoodsId(goodsId);
        line.setQuantity(quantity);
        line.setUnitPrice(unitPrice == null ? null : new BigDecimal(unitPrice));
        return line;
    }

    private PurchaseSaveDTO dto(List<PurchaseSaveDTO.LineDTO> lines) {
        PurchaseSaveDTO dto = new PurchaseSaveDTO();
        dto.setLines(lines);
        dto.setSupplierId(11L); // D123 头级供应商必填（内部 createInternal 渠道不校验）
        return dto;
    }

    private BaseSupplier supplier(long id, String name) {
        BaseSupplier s = new BaseSupplier();
        s.setId(id);
        s.setSupplierName(name);
        s.setStatus(1);
        return s;
    }

    private LoginResponse.UserInfoVO operator() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(7L);
        user.setRealName("采购管理员");
        user.setRole("admin");
        return user;
    }

    // ---------- D111：建单多行 + 头汇总（不加库存） ----------

    @Test
    void create_multiLine_aggregatesHeadTotals_andDoesNotTouchStock() {
        BaseGoods steel = material(29L, "钢板", 100, "50.00");
        BaseGoods screw = material(30L, "螺丝", 1000, "0.50");
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(steel, screw));
        when(baseSupplierMapper.selectById(11L)).thenReturn(supplier(11L, "华东钢业"));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizPurchaseMapper.insert(any(BizPurchase.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizPurchase.class).setId(501L);
            return 1;
        });

        service.create(dto(List.of(line(29L, 10, "50.00"), line(30L, 100, "0.50"))));

        ArgumentCaptor<BizPurchase> headCaptor = ArgumentCaptor.forClass(BizPurchase.class);
        verify(bizPurchaseMapper).insert(headCaptor.capture());
        assertEquals(110, headCaptor.getValue().getTotalQuantity()); // 10+100
        assertEquals(0, new BigDecimal("550.00").compareTo(headCaptor.getValue().getTotalAmount())); // 500+50
        assertEquals(PurchaseService.CONFIRM_PENDING, headCaptor.getValue().getConfirmStatus());

        verify(bizPurchaseDetailMapper, times(2)).insert(any(BizPurchaseDetail.class));
        // 建单不动库存，待采购到货 + 仓储确认入库
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void create_duplicateGoodsLine_rejected() {
        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(List.of(line(29L, 5, "50.00"), line(29L, 3, "50.00")))));
        assertTrue(ex.getMessage().contains("只能有一行"), "实际: " + ex.getMessage());

        verify(bizPurchaseMapper, never()).insert(any(BizPurchase.class));
        verify(bizPurchaseDetailMapper, never()).insert(any(BizPurchaseDetail.class));
    }

    // ---------- D123：手动进货头级供应商必填且须为有效供应商 ----------

    @Test
    void create_missingSupplier_rejected() {
        PurchaseSaveDTO noSupplier = dto(List.of(line(29L, 5, "50.00")));
        noSupplier.setSupplierId(null);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(noSupplier));
        assertTrue(ex.getMessage().contains("供应商不能为空"), "实际: " + ex.getMessage());

        verify(bizPurchaseMapper, never()).insert(any(BizPurchase.class));
    }

    @Test
    void create_unknownSupplier_rejected() {
        when(baseSupplierMapper.selectById(999L)).thenReturn(null);
        PurchaseSaveDTO badSupplier = dto(List.of(line(29L, 5, "50.00")));
        badSupplier.setSupplierId(999L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(badSupplier));
        assertTrue(ex.getMessage().contains("供应商不存在"), "实际: " + ex.getMessage());

        verify(bizPurchaseMapper, never()).insert(any(BizPurchase.class));
    }

    @Test
    void create_disabledSupplier_rejected() {
        BaseSupplier disabled = supplier(12L, "停用供应商");
        disabled.setStatus(0);
        when(baseSupplierMapper.selectById(12L)).thenReturn(disabled);
        PurchaseSaveDTO badSupplier = dto(List.of(line(29L, 5, "50.00")));
        badSupplier.setSupplierId(12L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.create(badSupplier));
        assertTrue(ex.getMessage().contains("供应商已停用"), "实际: " + ex.getMessage());

        verify(bizPurchaseMapper, never()).insert(any(BizPurchase.class));
    }

    @Test
    void create_withSupplier_persistsHeadSupplierId() {
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(material(29L, "钢板", 100, "50.00")));
        when(baseSupplierMapper.selectById(11L)).thenReturn(supplier(11L, "华东钢业"));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizPurchaseMapper.insert(any(BizPurchase.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizPurchase.class).setId(502L);
            return 1;
        });

        service.create(dto(List.of(line(29L, 5, "50.00"))));

        ArgumentCaptor<BizPurchase> headCaptor = ArgumentCaptor.forClass(BizPurchase.class);
        verify(bizPurchaseMapper).insert(headCaptor.capture());
        assertEquals(11L, headCaptor.getValue().getSupplierId());
    }

    // ---------- D124：批量最近成交价（到货提交预填） ----------

    @Test
    void latestPrices_latestWinsAndFallsBackToStandardPrice() {
        BizPurchaseMapper.LatestPurchasePrice latest = new BizPurchaseMapper.LatestPurchasePrice();
        latest.setGoodsId(29L);
        latest.setUnitPrice(new BigDecimal("52.00")); // 最近成交价
        when(bizPurchaseMapper.latestValidUnitPrices(anyCollection(), any())).thenReturn(List.of(latest));
        // 30 有标准进价回退，31 两种价都没有
        BaseGoods standard = material(30L, "螺丝", 100, "0.50");
        BaseGoods none = material(31L, "垫片", 100, null);
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(standard, none));

        Map<Long, BigDecimal> result =
                service.latestPrices(java.util.List.of(29L, 30L, 31L));

        assertEquals(0, new BigDecimal("52.00").compareTo(result.get(29L)));
        assertEquals(0, new BigDecimal("0.50").compareTo(result.get(30L)));
        org.junit.jupiter.api.Assertions.assertFalse(result.containsKey(31L), "两种价都没有的物料不入 map（前端留空）");
    }

    @Test
    void latestPrices_nonPurchaseMemberForbidden() {
        doThrow(new BusinessException("最近采购价仅采购部门可查看"))
                .when(authzService).requireDeptMemberOrSuperAdmin(anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.latestPrices(List.of(29L)));
        assertTrue(ex.getMessage().contains("仅采购部门"), "实际: " + ex.getMessage());
        verify(bizPurchaseMapper, never()).latestValidUnitPrices(anyCollection(), any());
    }

    // ---------- D111：内部创建=一张多行已入库单，逐行加库存 + 逐行回写进价 ----------

    @Test
    void createInternal_multiLine_increasesStockAndWritesPricePerLine() {
        when(baseGoodsMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(material(29L, "钢板", 0, "50.00"), material(30L, "螺丝", 0, "0.50")));
        when(bizPurchaseMapper.insert(any(BizPurchase.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizPurchase.class).setId(502L);
            return 1;
        });
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        service.createInternal(dto(List.of(line(29L, 10, "52.00"), line(30L, 100, "0.60"))), 9L, "仓储管理员");

        ArgumentCaptor<BizPurchase> headCaptor = ArgumentCaptor.forClass(BizPurchase.class);
        verify(bizPurchaseMapper).insert(headCaptor.capture());
        assertEquals(PurchaseService.CONFIRM_RECEIVED, headCaptor.getValue().getConfirmStatus());
        assertEquals(110, headCaptor.getValue().getTotalQuantity());

        // 每行一次库存自增 + 一次进价回写
        verify(baseGoodsMapper, times(4)).update(any(), any());
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> wrapperCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, times(4)).update(isNull(), wrapperCaptor.capture());
        List<String> sqlSets = wrapperCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getSqlSet())).toList();
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock + 10")), "钢板应入库 10");
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock + 100")), "螺丝应入库 100");
        // 单测环境 lambda 列名按属性名渲染（purchasePrice），真实 SQL 执行时才转下划线
        assertTrue(sqlSets.stream().filter(s -> s.contains("purchasePrice")).count() >= 2,
                "两行均应回写进价，实际 sqlSet: " + sqlSets);
    }

    // ---------- D111：仓储确认入库逐行加库存 + 逐行回写进价 ----------

    private BizPurchase awaitingPurchase() {
        BizPurchase entity = new BizPurchase();
        entity.setId(501L);
        entity.setPurchaseNo("JH260910001");
        entity.setBizStatus(1);
        entity.setConfirmStatus(PurchaseService.CONFIRM_AWAITING);
        entity.setOperationTime(LocalDateTime.now());
        return entity;
    }

    private BizPurchaseDetail detail(long id, long goodsId, String name, int quantity, String unitPrice) {
        BizPurchaseDetail d = new BizPurchaseDetail();
        d.setId(id);
        d.setPurchaseId(501L);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        d.setUnitPrice(new BigDecimal(unitPrice));
        return d;
    }

    @Test
    void confirmReceive_increasesStockPerLine_andWritesPrice_andRevokes() {
        when(bizPurchaseMapper.selectById(501L)).thenReturn(awaitingPurchase());
        when(bizApprovalOrderMapper.selectCount(any())).thenReturn(0L);
        when(bizPurchaseDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "钢板", 10, "52.00"),
                        detail(2L, 30L, "螺丝", 100, "0.60")));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizPurchaseMapper.update(any(), any())).thenReturn(1);

        service.confirmReceive(501L);

        // 每行一次库存自增 + 一次进价回写
        verify(baseGoodsMapper, times(4)).update(any(), any());
        verify(bizPurchaseMapper).update(any(), any()); // 头状态 AWAITING→RECEIVED（乐观条件）
        verify(messageService).revokeUnreadByBiz("purchase", 501L);
    }

    // ---------- D111：已入库作废逐行回冲库存 + 逐商品重算进价 ----------

    private BizPurchase receivedPurchase() {
        BizPurchase entity = awaitingPurchase();
        entity.setConfirmStatus(PurchaseService.CONFIRM_RECEIVED);
        return entity;
    }

    @Test
    void voidDocument_received_decreasesStockPerLine_andRefreshesPrice() {
        when(bizPurchaseMapper.selectById(501L)).thenReturn(receivedPurchase());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizPurchaseMapper.update(any(), any())).thenReturn(1);
        when(bizPurchaseDetailMapper.selectList(any()))
                .thenReturn(List.of(detail(1L, 29L, "钢板", 10, "52.00"),
                        detail(2L, 30L, "螺丝", 100, "0.60")));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);
        // 作废后重算进价：回退到各物料最近一批有效已入库行单价
        when(bizPurchaseMapper.latestValidUnitPrice(anyLong(), any())).thenReturn(new BigDecimal("40.00"));

        service.voidDocument(501L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> stockCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, atLeastOnce()).update(isNull(), stockCaptor.capture());
        List<String> sqlSets = stockCaptor.getAllValues().stream()
                .map(w -> String.valueOf(w.getSqlSet())).toList();
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock - 10")), "钢板应扣回 10");
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock - 100")), "螺丝应扣回 100");
        verify(bizPurchaseMapper, times(2)).latestValidUnitPrice(anyLong(), any()); // 逐商品重算
        verify(messageService).revokeUnreadByBiz("purchase", 501L);
    }

    @Test
    void voidDocument_withActiveReturn_blockedBeforeAnyMutation() {
        when(bizPurchaseMapper.selectById(501L)).thenReturn(receivedPurchase());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        doThrow(BusinessException.validateFail(
                "该进货单关联有未终结的退货单（RET202609001），请先删除或作废退货单后再作废进货单"))
                .when(purchaseReturnService).ensureNoActiveReturn(501L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.voidDocument(501L, null));
        assertTrue(ex.getMessage().contains("请先删除或作废退货单"), ex.getMessage());

        // 守卫在任何写库动作之前抛错：不废单、不碰库存、不撤消息
        verify(bizPurchaseMapper, never()).update(any(), any());
        verify(baseGoodsMapper, never()).update(any(), any());
        verify(messageService, never()).revokeUnreadByBiz(any(), anyLong());
    }

    @Test
    void voidDocument_pending_doesNotTouchStock() {
        BizPurchase pending = new BizPurchase();
        pending.setId(503L);
        pending.setBizStatus(1);
        pending.setConfirmStatus(PurchaseService.CONFIRM_PENDING);
        when(bizPurchaseMapper.selectById(503L)).thenReturn(pending);
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizPurchaseMapper.update(any(), any())).thenReturn(1);

        service.voidDocument(503L, null);

        verify(bizPurchaseDetailMapper, never()).selectList(any());
        verify(baseGoodsMapper, never()).update(any(), any());
        verify(messageService).revokeUnreadByBiz("purchase", 503L);
    }

    // ---------- D111：删除（当天待到货）级联软删明细行 ----------

    @Test
    void delete_pendingSameDay_cascadeDeletesDetails() {
        BizPurchase pending = new BizPurchase();
        pending.setId(501L);
        pending.setBizStatus(1);
        pending.setConfirmStatus(PurchaseService.CONFIRM_PENDING);
        pending.setOperationTime(LocalDateTime.now());
        when(bizPurchaseMapper.selectById(501L)).thenReturn(pending);

        service.delete(501L);

        verify(messageService).revokeUnreadByBiz("purchase", 501L);
        verify(bizPurchaseMapper).deleteById(501L);
        verify(bizPurchaseDetailMapper).delete(any()); // 级联软删，防孤立行
    }

    private static org.mockito.verification.VerificationMode atLeastOnce() {
        return org.mockito.Mockito.atLeastOnce();
    }
}
