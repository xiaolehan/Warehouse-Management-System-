package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PickListServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPickList.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPickListDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BaseGoods.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizProductionOrder.class);
    }

    @Mock private BizPickListMapper bizPickListMapper;
    @Mock private BizPickListDetailMapper bizPickListDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;

    @InjectMocks private PickListService service;

    private LoginResponse.UserInfoVO mockWarehouseUser() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("仓储员");
        when(authService.getUserInfo()).thenReturn(user);
        return user;
    }

    // ========================== issue - 生产领料成功 ==========================

    @Test
    void issue_productionPick_decreasesStockAndSendsProductionNotice() {
        // 待发料的生产领料单（productionOrderId 非空）
        BizPickList pick = new BizPickList();
        pick.setId(1L);
        pick.setPickNo("PK-001");
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(7L);
        when(bizPickListMapper.selectById(1L)).thenReturn(pick);

        BizPickListDetail detail = new BizPickListDetail();
        detail.setId(10L);
        detail.setPickListId(1L);
        detail.setGoodsId(50L);
        detail.setGoodsName("螺丝");
        detail.setQuantity(6);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(detail));

        // 库存充足 → decreaseStock 更新成功（返回 1 行）
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        // 状态更新成功
        when(bizPickListMapper.update(any(), any())).thenReturn(1);

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-001");
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);

        mockWarehouseUser();
        service.issue(1L);

        // 1. 库存被扣减：baseGoodsMapper.update 被调用（decreaseStock 内部）
        verify(baseGoodsMapper).update(eq(null), any());

        // 2. 状态变为已发料
        verify(bizPickListMapper).update(eq(null), any());

        // 3. 撤销之前的缺料未读消息
        verify(messageService).revokeUnreadByBiz("pick_list", 1L);

        // 4. 生产来源 → 通知生产端可开工（NOT 销售）
        verify(messageService).sendPickIssuedToProductionAdmins(eq("PK-001"), eq("SC-001"), eq(1L));
        verify(messageService, never()).sendPickListFailureToSalesAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== issue - 销售领料成功 ==========================

    @Test
    void issue_salesPick_sendsNoProductionNotice() {
        // 待发料的销售领料单（productionOrderId 为 null）
        BizPickList pick = new BizPickList();
        pick.setId(2L);
        pick.setPickNo("PK-002");
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(null);
        pick.setSourceSalesId(99L);
        when(bizPickListMapper.selectById(2L)).thenReturn(pick);

        BizPickListDetail detail = new BizPickListDetail();
        detail.setId(20L);
        detail.setPickListId(2L);
        detail.setGoodsId(50L);
        detail.setGoodsName("螺丝");
        detail.setQuantity(3);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(detail));

        // 库存充足
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        // 状态更新成功
        when(bizPickListMapper.update(any(), any())).thenReturn(1);

        mockWarehouseUser();
        service.issue(2L);

        // 库存扣减 + 状态更新
        verify(baseGoodsMapper).update(eq(null), any());
        verify(bizPickListMapper).update(eq(null), any());

        // 销售来源 → 撤销未读，不发送生产通知，也不发送销售失败通知
        verify(messageService).revokeUnreadByBiz("pick_list", 2L);
        verify(messageService, never()).sendPickIssuedToProductionAdmins(anyString(), anyString(), anyLong());
        verify(messageService, never()).sendPickListFailureToSalesAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== issue - 缺料失败（生产来源） ==========================

    @Test
    void issue_stockInsufficient_productionSource_routesFailureToProduction() {
        BizPickList pick = new BizPickList();
        pick.setId(3L);
        pick.setPickNo("PK-003");
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(7L);
        when(bizPickListMapper.selectById(3L)).thenReturn(pick);

        BizPickListDetail detail = new BizPickListDetail();
        detail.setId(30L);
        detail.setPickListId(3L);
        detail.setGoodsId(51L);
        detail.setGoodsName("板1");
        detail.setQuantity(100);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(detail));

        // 库存不足 → decreaseStock 更新 0 行 → 抛 stockInsufficient
        when(baseGoodsMapper.update(any(), any())).thenReturn(0);

        // 去重检查：尚无未读
        when(messageService.hasUnreadBizMessage("pick_list", 3L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issue(3L));
        assertTrue(ex.getMessage().contains("库存不足") || ex.getMessage().contains("板1"),
                "错误应包含库存不足信息，实际: " + ex.getMessage());

        // 失败通知发给生产端而非销售端
        verify(messageService).sendPickIssueFailedToProductionAdmins(eq("PK-003"), anyString(), eq(3L));
        verify(messageService, never()).sendPickListFailureToSalesAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== issue - 缺料失败（销售来源） ==========================

    @Test
    void issue_stockInsufficient_salesSource_routesFailureToSales() {
        BizPickList pick = new BizPickList();
        pick.setId(4L);
        pick.setPickNo("PK-004");
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(null);
        pick.setSourceSalesId(99L);
        when(bizPickListMapper.selectById(4L)).thenReturn(pick);

        BizPickListDetail detail = new BizPickListDetail();
        detail.setId(40L);
        detail.setPickListId(4L);
        detail.setGoodsId(51L);
        detail.setGoodsName("板1");
        detail.setQuantity(100);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(detail));

        // 库存不足
        when(baseGoodsMapper.update(any(), any())).thenReturn(0);

        when(messageService.hasUnreadBizMessage("pick_list", 4L)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issue(4L));
        assertTrue(ex.getMessage().contains("库存不足") || ex.getMessage().contains("板1"));

        // 销售来源 → 失败通知发给销售端
        verify(messageService).sendPickListFailureToSalesAdmins(eq("PK-004"), anyString(), eq(4L));
        verify(messageService, never()).sendPickIssueFailedToProductionAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== issue - 状态非待发料拒绝 ==========================

    @Test
    void issue_rejectsWhenNotPending() {
        BizPickList pick = new BizPickList();
        pick.setId(5L);
        pick.setStatus(PickListService.STATUS_ISSUED);
        when(bizPickListMapper.selectById(5L)).thenReturn(pick);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issue(5L));
        assertTrue(ex.getMessage().contains("仅待发料状态可发料"));
    }

    // ========================== issue - 明细为空拒绝 ==========================

    @Test
    void issue_rejectsWhenDetailsEmpty() {
        BizPickList pick = new BizPickList();
        pick.setId(6L);
        pick.setStatus(PickListService.STATUS_PENDING);
        when(bizPickListMapper.selectById(6L)).thenReturn(pick);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.issue(6L));
        assertTrue(ex.getMessage().contains("明细为空"));
    }

    // ========================== issue - 退料回流入库 ==========================

    @Test
    void issue_returnPick_increasesStock() {
        BizPickList pick = new BizPickList();
        pick.setId(7L);
        pick.setPickNo("PK-007");
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(7L);
        when(bizPickListMapper.selectById(7L)).thenReturn(pick);

        BizPickListDetail detail = new BizPickListDetail();
        detail.setId(70L);
        detail.setPickListId(7L);
        detail.setGoodsId(50L);
        detail.setGoodsName("螺丝");
        detail.setQuantity(3);
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(detail));

        // 回流入库成功（increaseStock 调 update）
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);
        when(bizPickListMapper.update(any(), any())).thenReturn(1);

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-001");
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);

        mockWarehouseUser();
        service.issue(7L);

        // 库存增加：baseGoodsMapper.update 被调用
        verify(baseGoodsMapper).update(eq(null), any());
        // 状态更新为已发料
        verify(bizPickListMapper).update(eq(null), any());
        // 撤销未读 + 生产通知
        verify(messageService).revokeUnreadByBiz("pick_list", 7L);
        verify(messageService).sendPickIssuedToProductionAdmins(eq("PK-007"), eq("SC-001"), eq(7L));
    }

    // ========================== reject - 生产来源路由到生产端 ==========================

    @Test
    void reject_productionSource_routesToProduction() {
        BizPickList pick = new BizPickList();
        pick.setId(8L);
        pick.setPickNo("PK-008");
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(7L);
        when(bizPickListMapper.selectById(8L)).thenReturn(pick);
        when(bizPickListMapper.update(any(), any())).thenReturn(1);

        org.example.back.dto.PickListRejectDTO dto = new org.example.back.dto.PickListRejectDTO();
        dto.setReason("物料规格不符");

        service.reject(8L, dto);

        // 1. 撤销未读的待出库通知
        verify(messageService).revokeUnreadByBiz("pick_list", 8L);
        // 2. 驳回通知发给生产端
        verify(messageService).sendPickIssueFailedToProductionAdmins(eq("PK-008"), anyString(), eq(8L));
        verify(messageService, never()).sendPickListFailureToSalesAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== reject - 销售来源路由到销售端 ==========================

    @Test
    void reject_salesSource_routesToSales() {
        BizPickList pick = new BizPickList();
        pick.setId(9L);
        pick.setPickNo("PK-009");
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(null);
        when(bizPickListMapper.selectById(9L)).thenReturn(pick);
        when(bizPickListMapper.update(any(), any())).thenReturn(1);

        org.example.back.dto.PickListRejectDTO dto = new org.example.back.dto.PickListRejectDTO();
        dto.setReason("库存不足");

        service.reject(9L, dto);

        // 1. 撤销未读
        verify(messageService).revokeUnreadByBiz("pick_list", 9L);
        // 2. 驳回通知发给销售端
        verify(messageService).sendPickListFailureToSalesAdmins(eq("PK-009"), anyString(), eq(9L));
        verify(messageService, never()).sendPickIssueFailedToProductionAdmins(anyString(), anyString(), anyLong());
    }

    // ========================== reject - 状态校验 ==========================

    @Test
    void reject_rejectsWhenNotPending() {
        BizPickList pick = new BizPickList();
        pick.setId(10L);
        pick.setStatus(PickListService.STATUS_ISSUED);
        when(bizPickListMapper.selectById(10L)).thenReturn(pick);

        org.example.back.dto.PickListRejectDTO dto = new org.example.back.dto.PickListRejectDTO();
        dto.setReason("test");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.reject(10L, dto));
        assertTrue(ex.getMessage().contains("仅待发料状态可驳回"));
    }
}
