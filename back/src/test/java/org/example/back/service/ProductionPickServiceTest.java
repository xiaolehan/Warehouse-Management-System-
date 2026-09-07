package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.dto.ProductionReturnItemDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.ProductionPickItemVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionPickServiceTest {

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
    }

    @Mock private BizPickListMapper pickListMapper;
    @Mock private BizPickListDetailMapper pickListDetailMapper;
    @Mock private BizProductionOrderMapper productionOrderMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private ProductionOrderService productionOrderService;

    @InjectMocks private ProductionPickService service;

    @Test
    void createPick_generatesPendingPickWithOrderId() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        when(pickListMapper.selectCount(any())).thenReturn(0L);

        ProductionPickItemVO item = new ProductionPickItemVO();
        item.setGoodsId(50L);
        item.setGoodsName("螺丝");
        item.setQuantity(6);
        when(productionOrderService.computePickItems(7L)).thenReturn(List.of(item));

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        service.createPick(7L);

        ArgumentCaptor<BizPickList> cap = ArgumentCaptor.forClass(BizPickList.class);
        verify(pickListMapper).insert(cap.capture());
        BizPickList pick = cap.getValue();
        assertEquals(7L, pick.getProductionOrderId());
        assertEquals("PICK", pick.getPickType());
        assertEquals(1, pick.getStatus()); // PENDING
        assertEquals(user.getId(), pick.getApplicantId());

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture());
        assertEquals(50L, dcap.getValue().getGoodsId());
        assertEquals(6, dcap.getValue().getQuantity());
    }

    @Test
    void createPick_rejectsWhenAlreadyPicked() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        when(pickListMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPick(7L));
        assertTrue(ex.getMessage().contains("已申请领料"));
    }

    @Test
    void createPick_rejectsWhenNotPending() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createPick(7L));
        assertTrue(ex.getMessage().contains("仅待生产状态可申请领料"));
    }

    @Test
    void listByOrder_requiresProductionMember() {
        // 权限闸门由 requireProductionMember 完成，authzService 为 mock 不会真抛，
        // 这里验证 listByOrder 实际调用了权限闸门和查询。
        BizPickList p1 = new BizPickList();
        p1.setId(1L);
        p1.setPickNo("PK-001");
        p1.setPickType("PICK");
        p1.setStatus(1);
        p1.setProductionOrderId(7L);
        when(pickListMapper.selectList(any())).thenReturn(List.of(p1));

        List<org.example.back.vo.PickListVO> result = service.listByOrder(7L);
        assertEquals(1, result.size());
        assertEquals("PK-001", result.get(0).getPickNo());
        // verify authz gate was invoked
        verify(authzService).requireAnyDeptMemberOrSuperAdmin(anyString(), anyString());
    }

    @Test
    void editableItems_requiresProductionMember() {
        ProductionPickItemVO item = new ProductionPickItemVO();
        item.setGoodsId(50L);
        item.setGoodsName("螺丝");
        item.setQuantity(6);
        when(productionOrderService.computePickItems(7L)).thenReturn(List.of(item));

        List<ProductionPickItemVO> result = service.editableItems(7L);
        assertEquals(1, result.size());
        assertEquals(50L, result.get(0).getGoodsId());
        // verify authz gate was invoked (editable endpoint 必须有生产部权限)
        verify(authzService).requireAnyDeptMemberOrSuperAdmin(anyString(), anyString());
    }

    // ========================== createReturn 测试 ==========================

    @Test
    void createReturn_happyPath_generatesReturnPickWithDetails() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        BaseGoods goods = new BaseGoods();
        goods.setId(50L);
        goods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(goods);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(3);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setRemark("多领退回");
        dto.setItems(List.of(item));

        service.createReturn(7L, dto);

        ArgumentCaptor<BizPickList> cap = ArgumentCaptor.forClass(BizPickList.class);
        verify(pickListMapper).insert(cap.capture());
        BizPickList pick = cap.getValue();
        assertEquals("RETURN", pick.getPickType());
        assertEquals(7L, pick.getProductionOrderId());
        assertEquals(1, pick.getStatus()); // PENDING
        assertEquals(user.getId(), pick.getApplicantId());
        assertTrue(pick.getRemark().contains("退料"));
        assertTrue(pick.getRemark().contains("多领退回"));

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture());
        assertEquals(50L, dcap.getValue().getGoodsId());
        assertEquals("螺丝", dcap.getValue().getGoodsName());
        assertEquals(3, dcap.getValue().getQuantity());

        verify(messageService).sendPickReturnPendingToWarehouseAdmins(anyString(), anyString(), any());
    }

    @Test
    void createReturn_rejectsWhenNotInProgress() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(new ProductionReturnItemDTO()));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(7L, dto));
        assertTrue(ex.getMessage().contains("仅生产中状态可退料"));
    }

    @Test
    void createReturn_rejectsWhenItemsEmpty() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(7L, dto));
        assertTrue(ex.getMessage().contains("请选择退料明细"));
    }

    @Test
    void createReturn_rejectsWhenOrderNotFound() {
        when(productionOrderMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(999L, null));
        assertTrue(ex.getMessage().contains("生产任务单不存在"));
    }
}
