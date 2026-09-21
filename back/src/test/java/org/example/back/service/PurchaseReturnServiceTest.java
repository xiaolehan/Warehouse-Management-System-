package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseReturnSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.entity.BizPurchaseReturn;
import org.example.back.entity.BizPurchaseReturnDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseReturnDetailMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D111：进货退货单头行结构（biz_purchase_return / biz_purchase_return_detail）——
 * 多行退货头汇总/重复来源行拦截/跨进货单拦截/行级可退量与超退拦截/
 * 逐行扣库存/作废逐行回补/级联软删/行级已退量累计口径。
 */
@ExtendWith(MockitoExtension.class)
class PurchaseReturnServiceTest {

    @Mock private BizPurchaseReturnMapper bizPurchaseReturnMapper;
    @Mock private BizPurchaseReturnDetailMapper bizPurchaseReturnDetailMapper;
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizPurchaseDetailMapper bizPurchaseDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizApprovalOrderMapper bizApprovalOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;

    @InjectMocks private PurchaseReturnService service;

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
                BizPurchaseReturn.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPurchaseReturnDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizApprovalOrder.class);
    }

    private PurchaseReturnSaveDTO.LineDTO line(long sourceDetailId, int quantity) {
        PurchaseReturnSaveDTO.LineDTO line = new PurchaseReturnSaveDTO.LineDTO();
        line.setSourceDetailId(sourceDetailId);
        line.setQuantity(quantity);
        return line;
    }

    private PurchaseReturnSaveDTO dto(List<PurchaseReturnSaveDTO.LineDTO> lines) {
        PurchaseReturnSaveDTO dto = new PurchaseReturnSaveDTO();
        dto.setLines(lines);
        return dto;
    }

    private BizPurchaseDetail sourceDetail(long id, long purchaseId, long goodsId, String name,
                                           int quantity, String unitPrice) {
        BizPurchaseDetail d = new BizPurchaseDetail();
        d.setId(id);
        d.setPurchaseId(purchaseId);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        d.setUnitPrice(new BigDecimal(unitPrice));
        return d;
    }

    private BizPurchase purchase(long id, int bizStatus) {
        BizPurchase p = new BizPurchase();
        p.setId(id);
        p.setPurchaseNo("JH26091" + String.format("%03d", id));
        p.setBizStatus(bizStatus);
        p.setConfirmStatus(PurchaseService.CONFIRM_RECEIVED);
        return p;
    }

    private BaseGoods goods(long id, String name) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setGoodsName(name);
        g.setType(GoodsService.GOODS_TYPE_MATERIAL);
        g.setStatus(1);
        return g;
    }

    private LoginResponse.UserInfoVO operator() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(7L);
        user.setRealName("采购管理员");
        user.setRole("admin");
        return user;
    }

    private BizPurchaseReturnDetail returnDetail(long id, long returnId, long sourceDetailId, int quantity) {
        BizPurchaseReturnDetail d = new BizPurchaseReturnDetail();
        d.setId(id);
        d.setReturnId(returnId);
        d.setSourceDetailId(sourceDetailId);
        d.setQuantity(quantity);
        return d;
    }

    private BizPurchaseReturn returnHead(long id, int bizStatus, int confirmStatus) {
        BizPurchaseReturn h = new BizPurchaseReturn();
        h.setId(id);
        h.setBizStatus(bizStatus);
        h.setConfirmStatus(confirmStatus);
        return h;
    }

    // ---------- D111：多行退货（同一进货单）头汇总 + 通知仓储 ----------

    @Test
    void create_multiLine_samePurchase_aggregatesHeadTotals_andNotifies() {
        when(bizPurchaseDetailMapper.selectList(any())).thenReturn(List.of(
                sourceDetail(1L, 100L, 29L, "钢板", 10, "50.00"),
                sourceDetail(2L, 100L, 30L, "螺丝", 100, "0.50")));
        when(bizPurchaseMapper.selectById(100L)).thenReturn(purchase(100L, 1));
        // 行级已退量累计：无历史退货
        when(bizPurchaseReturnDetailMapper.selectList(any())).thenReturn(List.of());
        when(baseGoodsMapper.selectBatchIds(anyCollection())).thenReturn(List.of(goods(29L, "钢板"), goods(30L, "螺丝")));
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizPurchaseReturnMapper.insert(any(BizPurchaseReturn.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizPurchaseReturn.class).setId(700L);
            return 1;
        });

        service.create(dto(List.of(line(1L, 10), line(2L, 100))));

        ArgumentCaptor<BizPurchaseReturn> headCaptor = ArgumentCaptor.forClass(BizPurchaseReturn.class);
        verify(bizPurchaseReturnMapper).insert(headCaptor.capture());
        assertEquals(110, headCaptor.getValue().getTotalQuantity()); // 10+100
        assertEquals(0, new BigDecimal("550.00").compareTo(headCaptor.getValue().getTotalAmount())); // 500+50
        assertEquals(PurchaseReturnService.CONFIRM_PENDING, headCaptor.getValue().getConfirmStatus());
        assertEquals(100L, headCaptor.getValue().getSourcePurchaseId());

        verify(bizPurchaseReturnDetailMapper, times(2)).insert(any(BizPurchaseReturnDetail.class));
        verify(baseGoodsMapper, never()).update(any(), any()); // 建单不减库存
        verify(messageService).sendPurchaseReturnPendingConfirmToWarehouseAdmins(
                any(), any(), org.mockito.ArgumentMatchers.eq(700L));
    }

    // ---------- D111：同一来源明细行重复拦截 ----------

    @Test
    void create_duplicateSourceLine_rejected() {
        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(List.of(line(1L, 2), line(1L, 3)))));
        assertTrue(ex.getMessage().contains("只能出现一次"), "实际: " + ex.getMessage());

        verify(bizPurchaseReturnMapper, never()).insert(any(BizPurchaseReturn.class));
    }

    // ---------- D111：一张退货单只能退同一进货单的行 ----------

    @Test
    void create_linesFromDifferentPurchases_rejected() {
        when(bizPurchaseDetailMapper.selectList(any())).thenReturn(List.of(
                sourceDetail(1L, 100L, 29L, "钢板", 10, "50.00"),
                sourceDetail(2L, 200L, 30L, "螺丝", 100, "0.50")));

        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(List.of(line(1L, 5), line(2L, 50)))));
        assertTrue(ex.getMessage().contains("按进货单分别退货"), "实际: " + ex.getMessage());

        verify(bizPurchaseMapper, never()).selectById(org.mockito.ArgumentMatchers.anyLong());
        verify(bizPurchaseReturnMapper, never()).insert(any(BizPurchaseReturn.class));
    }

    // ---------- D111：超退拦截（行级可退量 = 行入库量 − 已有效退货累计） ----------

    @Test
    void create_overReturn_rejected_withReturnableCeiling() {
        when(bizPurchaseDetailMapper.selectList(any()))
                .thenReturn(List.of(sourceDetail(1L, 100L, 29L, "钢板", 5, "50.00")));
        when(bizPurchaseMapper.selectById(100L)).thenReturn(purchase(100L, 1));
        // 该来源行已有一笔已出库退货 3 件（计入已退量）→ 可退 2
        when(bizPurchaseReturnDetailMapper.selectList(any()))
                .thenReturn(List.of(returnDetail(50L, 900L, 1L, 3)));
        when(bizPurchaseReturnMapper.selectBatchIds(anyCollection()))
                .thenReturn(List.of(returnHead(900L, 1, PurchaseReturnService.CONFIRM_AWAITING)));

        org.example.back.common.exception.BusinessException ex =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.example.back.common.exception.BusinessException.class,
                        () -> service.create(dto(List.of(line(1L, 4)))));
        assertTrue(ex.getMessage().contains("最多可退: 2"), "实际: " + ex.getMessage());

        verify(bizPurchaseReturnMapper, never()).insert(any(BizPurchaseReturn.class));
    }

    // ---------- D111：仓储确认出库逐行扣库存 ----------

    private BizPurchaseReturn pendingReturn(long id) {
        BizPurchaseReturn entity = new BizPurchaseReturn();
        entity.setId(id);
        entity.setReturnNo("TH260910001");
        entity.setBizStatus(1);
        entity.setConfirmStatus(PurchaseReturnService.CONFIRM_PENDING);
        return entity;
    }

    @Test
    void confirmOut_decreasesStockPerLine_andRevokes() {
        when(bizPurchaseReturnMapper.selectById(700L)).thenReturn(pendingReturn(700L));
        when(bizApprovalOrderMapper.selectCount(any())).thenReturn(0L);
        when(bizPurchaseReturnDetailMapper.selectList(any())).thenReturn(List.of(
                returnDetail(1L, 700L, 1L, 5),
                returnDetail(2L, 700L, 2L, 3)));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(operator());
        when(bizPurchaseReturnMapper.update(any(), any())).thenReturn(1);

        service.confirmOut(700L);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> stockCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, times(2)).update(isNull(), stockCaptor.capture());
        List<String> sqlSets = stockCaptor.getAllValues().stream().map(w -> String.valueOf(w.getSqlSet())).toList();
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock - 5")), "钢板应扣 5");
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock - 3")), "螺丝应扣 3");
        verify(messageService).revokeUnreadByBiz("purchase_return", 700L);
    }

    // ---------- D111：已出库作废逐行回补库存 ----------

    @Test
    void voidDocument_outConfirmed_restoresStockPerLine_andRevokes() {
        BizPurchaseReturn entity = pendingReturn(700L);
        entity.setConfirmStatus(PurchaseReturnService.CONFIRM_AWAITING);
        when(bizPurchaseReturnMapper.selectById(700L)).thenReturn(entity);
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizPurchaseReturnMapper.update(any(), any())).thenReturn(1);
        when(bizPurchaseReturnDetailMapper.selectList(any())).thenReturn(List.of(
                returnDetail(1L, 700L, 1L, 5),
                returnDetail(2L, 700L, 2L, 3)));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        service.voidDocument(700L, null);

        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BaseGoods>> stockCaptor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(baseGoodsMapper, times(2)).update(isNull(), stockCaptor.capture());
        List<String> sqlSets = stockCaptor.getAllValues().stream().map(w -> String.valueOf(w.getSqlSet())).toList();
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock + 5")), "钢板应补 5");
        assertTrue(sqlSets.stream().anyMatch(s -> s.contains("stock = stock + 3")), "螺丝应补 3");
        verify(messageService).revokeUnreadByBiz("purchase_return", 700L);
    }

    @Test
    void voidDocument_pending_doesNotTouchStock() {
        when(bizPurchaseReturnMapper.selectById(701L)).thenReturn(pendingReturn(701L));
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizPurchaseReturnMapper.update(any(), any())).thenReturn(1);

        service.voidDocument(701L, null);

        verify(baseGoodsMapper, never()).update(any(), any());
        verify(messageService).revokeUnreadByBiz("purchase_return", 701L);
    }

    // ---------- D111：删除（当天待出库）级联软删明细行 ----------

    @Test
    void delete_pendingSameDay_cascadeDeletesDetails() {
        BizPurchaseReturn entity = pendingReturn(700L);
        entity.setOperationTime(LocalDateTime.now());
        when(bizPurchaseReturnMapper.selectById(700L)).thenReturn(entity);

        service.delete(700L);

        verify(messageService).revokeUnreadByBiz("purchase_return", 700L);
        verify(bizPurchaseReturnMapper).deleteById(700L);
        verify(bizPurchaseReturnDetailMapper).delete(any());
    }

    // ---------- D111：行级已退量只计 正常 + 已出库(confirm>=2) 的退货 ----------

    @Test
    void returnedQtyBySourceDetail_onlyCountsNormalAndOutConfirmed() {
        when(bizPurchaseReturnDetailMapper.selectList(any())).thenReturn(List.of(
                returnDetail(101L, 901L, 1L, 2),  // 待出库（未扣库存）→ 不计
                returnDetail(102L, 902L, 1L, 3),  // 已作废 → 不计
                returnDetail(103L, 903L, 1L, 4))); // 正常+已出库 → 计 4
        when(bizPurchaseReturnMapper.selectBatchIds(anyCollection())).thenReturn(List.of(
                returnHead(901L, 1, PurchaseReturnService.CONFIRM_PENDING),
                returnHead(902L, 2, PurchaseReturnService.CONFIRM_COMPLETED),
                returnHead(903L, 1, PurchaseReturnService.CONFIRM_AWAITING)));

        Map<Long, Integer> result = service.returnedQtyBySourceDetail(List.of(1L));

        assertEquals(4, result.get(1L));
    }

    // ---------- D111/review：进货单作废守卫——存在未终结退货单时拦截并给出退货单号 ----------

    @Test
    void ensureNoActiveReturn_found_throwsWithReturnNos() {
        BizPurchaseReturn active = new BizPurchaseReturn();
        active.setId(801L);
        active.setReturnNo("RET202609001");
        active.setBizStatus(1);
        when(bizPurchaseReturnMapper.selectList(any())).thenReturn(List.of(active));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.ensureNoActiveReturn(100L));
        assertTrue(ex.getMessage().contains("RET202609001"), ex.getMessage());
        assertTrue(ex.getMessage().contains("请先删除或作废退货单"), ex.getMessage());
    }

    @Test
    void ensureNoActiveReturn_noneOrNullSource_passes() {
        when(bizPurchaseReturnMapper.selectList(any())).thenReturn(List.of());

        assertDoesNotThrow(() -> service.ensureNoActiveReturn(100L));
        assertDoesNotThrow(() -> service.ensureNoActiveReturn(null));
    }
}
