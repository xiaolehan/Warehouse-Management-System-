package org.example.back.service;

import org.example.back.dto.LoginResponse;
import org.example.back.dto.SalesSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * D69/D70：建单零库存校验（允许超卖）+ 缺货通知生产管理员。
 */
@ExtendWith(MockitoExtension.class)
class SalesServiceTest {

    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizSalesReturnMapper bizSalesReturnMapper;
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizApprovalOrderMapper bizApprovalOrderMapper;
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private SysConfigService sysConfigService;

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
                BizApprovalOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizProductionOrder.class);
    }

    private BaseGoods product(int stock) {
        BaseGoods goods = new BaseGoods();
        goods.setId(29L);
        goods.setType(GoodsService.GOODS_TYPE_PRODUCT);
        goods.setStatus(1);
        goods.setGoodsName("PTO153");
        goods.setStock(stock);
        goods.setSalePrice(new BigDecimal("100.00"));
        return goods;
    }

    private SalesSaveDTO dto(int quantity) {
        SalesSaveDTO dto = new SalesSaveDTO();
        dto.setGoodsId(29L);
        dto.setQuantity(quantity);
        dto.setUnitPrice(new BigDecimal("100.00")); // 与标准售价一致，不触发偏离审批
        return dto;
    }

    private void mockCreatePath(BaseGoods goods) {
        when(baseGoodsMapper.selectById(29L)).thenReturn(goods);
        when(bizPurchaseMapper.latestValidUnitPrice(anyLong(), any())).thenReturn(null);
        when(sysConfigService.getPriceDeviationThreshold()).thenReturn(new BigDecimal("0.05"));
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(7L);
        user.setRealName("销售管理员");
        user.setRole("admin");
        when(authService.getUserInfo()).thenReturn(user);
        when(bizSalesMapper.insert(any(BizSales.class))).thenAnswer(inv -> {
            inv.getArgument(0, BizSales.class).setId(501L);
            return 1;
        });
    }

    @Test
    void create_zeroStockAllowed_andNotifiesProduction() {
        mockCreatePath(product(0));

        service.create(dto(10)); // D69：库存 0 不再拦截建单

        verify(bizSalesMapper).insert(any(BizSales.class));
        // D70：现货不足 → 通知生产管理员待排产（biz 绑定销售单）
        verify(messageService).sendSalesDemandToProductionAdmins(
                anyString(), eq("PTO153"), eq(10), eq(0), any(), eq("销售管理员"), eq(501L));
        // 仓储待确认通知照常
        verify(messageService).sendSalesPendingConfirmToWarehouseAdmins(anyString(), any(), anyString(), eq(501L));
    }

    @Test
    void create_oversellAllowed_andNotifiesWithAvailable() {
        mockCreatePath(product(5));

        service.create(dto(10)); // 库存 5 卖 10：允许（E1 暂定口径，不预留）

        verify(messageService).sendSalesDemandToProductionAdmins(
                anyString(), eq("PTO153"), eq(10), eq(5), any(), anyString(), eq(501L));
    }

    @Test
    void create_sufficientStock_noProductionNotification() {
        mockCreatePath(product(100));

        service.create(dto(10));

        verify(messageService, never()).sendSalesDemandToProductionAdmins(
                anyString(), anyString(), anyInt(), anyInt(), any(), anyString(), anyLong());
        verify(messageService).sendSalesPendingConfirmToWarehouseAdmins(anyString(), any(), anyString(), eq(501L));
    }

    // ---------- D73：销售单删除/作废 → 通知关联未终态生产任务单 ----------

    private BizSales deletableSales() {
        BizSales entity = new BizSales();
        entity.setId(501L);
        entity.setSalesNo("XS260910001");
        entity.setGoodsId(29L);
        entity.setGoodsName("PTO153");
        entity.setQuantity(5);
        entity.setBizStatus(1);
        entity.setConfirmStatus(SalesService.CONFIRM_PENDING);
        entity.setOperationTime(LocalDateTime.now());
        return entity;
    }

    private BizProductionOrder linkedOrder(int status) {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(88L);
        order.setOrderNo("PRO260910001");
        order.setStatus(status);
        return order;
    }

    @Test
    void delete_withUnfinishedLinkedOrder_notifiesProduction() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of(linkedOrder(1)));

        service.delete(501L);

        verify(messageService).sendSalesCancelledToProductionAdmins(
                eq("XS260910001"), eq("PTO153"), eq(5), eq("PRO260910001"), eq(88L), eq("删除"));
    }

    @Test
    void delete_withFinishedLinkedOrder_doesNotNotify() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);
        // SQL 层 in(1,2,3) 过滤，已完工/已作废/已报废/已终止的关联单不会返回
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of());

        service.delete(501L);

        verify(messageService, never()).sendSalesCancelledToProductionAdmins(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void voidDocument_withUnfinishedLinkedOrder_notifiesWith作废() {
        when(bizSalesMapper.selectById(501L)).thenReturn(deletableSales());
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(bizSalesMapper.update(any(), any())).thenReturn(1);
        when(bizProductionOrderMapper.selectList(any())).thenReturn(List.of(linkedOrder(2)));

        service.voidDocument(501L, null);

        verify(messageService).sendSalesCancelledToProductionAdmins(
                eq("XS260910001"), eq("PTO153"), eq(5), eq("PRO260910001"), eq(88L), eq("作废"));
    }
}
