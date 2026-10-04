package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.dto.ProductionReturnItemDTO;
import org.example.back.dto.ProductionTerminateDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.ProductionPickItemVO;
import org.example.back.vo.ProductionReturnableVO;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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

    @Mock private SalesTerminateGuard salesTerminateGuard;
    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private PurchaseRequestService purchaseRequestService;

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
    void createReturn_nullGoodsIdItem_silentlyFiltered() {
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

        ProductionReturnItemDTO bad = new ProductionReturnItemDTO();
        bad.setGoodsId(null); // 脏数据：与 terminate 过滤口径对齐后应被静默过滤
        bad.setQuantity(5);
        ProductionReturnItemDTO good = new ProductionReturnItemDTO();
        good.setGoodsId(50L);
        good.setQuantity(3);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(bad, good));

        service.createReturn(7L, dto);

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture()); // 仅 good 一行
        assertEquals(50L, dcap.getValue().getGoodsId());
    }

    @Test
    void createReturn_allItemsInvalid_throws() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        ProductionReturnItemDTO bad = new ProductionReturnItemDTO();
        bad.setGoodsId(null);
        bad.setQuantity(5);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(bad));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(7L, dto));
        assertTrue(ex.getMessage().contains("无有效退料明细行"));
    }

    @Test
    void createReturn_rejectsWhenOrderNotFound() {
        when(productionOrderMapper.selectById(999L)).thenReturn(null);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createReturn(999L, null));
        assertTrue(ex.getMessage().contains("生产任务单不存在"));
    }

    // ========================== D63 明细快照规格/材质/备注 ==========================

    @Test
    void createPick_snapshotsSpecMaterialRemarkFromMaster() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        when(pickListMapper.selectCount(any())).thenReturn(0L);

        ProductionPickItemVO item = new ProductionPickItemVO();
        item.setGoodsId(50L);
        item.setGoodsName("螺丝");
        item.setQuantity(6);
        when(productionOrderService.computePickItems(7L)).thenReturn(List.of(item));

        BaseGoods goods = new BaseGoods();
        goods.setId(50L);
        goods.setGoodsName("螺丝");
        goods.setSpec("M6×20");
        goods.setMaterial("不锈钢304");
        goods.setDescription("外六角，用于面板固定");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods));

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        service.createPick(7L);

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture());
        assertEquals("M6×20", dcap.getValue().getSpec());
        assertEquals("不锈钢304", dcap.getValue().getMaterial());
        assertEquals("外六角，用于面板固定", dcap.getValue().getRemark());
    }

    @Test
    void createReturn_snapshotsSpecMaterialRemarkFromMaster() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("SC-0001");
        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);

        BaseGoods goods = new BaseGoods();
        goods.setId(50L);
        goods.setGoodsName("螺丝");
        goods.setSpec("M8×30");
        goods.setMaterial("45#钢");
        goods.setDescription("内六角");
        when(baseGoodsMapper.selectById(50L)).thenReturn(goods);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(3);
        ProductionReturnCreateDTO dto = new ProductionReturnCreateDTO();
        dto.setItems(List.of(item));

        service.createReturn(7L, dto);

        ArgumentCaptor<BizPickListDetail> dcap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(dcap.capture());
        assertEquals("M8×30", dcap.getValue().getSpec());
        assertEquals("45#钢", dcap.getValue().getMaterial());
        assertEquals("内六角", dcap.getValue().getRemark());
    }

    // ============================== D73：手动终止 + 已领未退预览 ==============================

    private BizProductionOrder terminatableOrder(int status) {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PRO-X");
        order.setGoodsId(29L);
        order.setGoodsName("PTO153");
        order.setQuantity(2);
        order.setStatus(status);
        order.setRemark("原备注");
        return order;
    }

    private BizPickList pickListOf(long id, String type, int status) {
        BizPickList p = new BizPickList();
        p.setId(id);
        p.setPickNo("PICK-" + id);
        p.setPickType(type);
        p.setStatus(status);
        p.setProductionOrderId(7L);
        return p;
    }

    private BizPickListDetail detailOf(long pickListId, long goodsId, String name, int qty) {
        BizPickListDetail d = new BizPickListDetail();
        d.setPickListId(pickListId);
        d.setGoodsId(goodsId);
        d.setGoodsName(name);
        d.setQuantity(qty);
        return d;
    }

    /** 终止前置：订单存在 + 无待出库领料单 */
    private void mockTerminateBase(BizProductionOrder order) {
        when(productionOrderMapper.selectById(7L)).thenReturn(order);
        when(pickListMapper.selectCount(any())).thenReturn(0L);
    }

    /** 一张已发料 PICK 单含 goods 50 ×qty（已领未退净额=qty）；不含 insertReturnList 的 selectById stub */
    private void mockReturnableData(int qty) {
        when(pickListMapper.selectList(any()))
                .thenReturn(List.of(pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any()))
                .thenReturn(List.of(detailOf(10L, 50L, "螺丝", qty)));
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
    }

    private ProductionTerminateDTO terminateDTO(String reason, long goodsId, int qty) {
        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(goodsId);
        item.setQuantity(qty);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason(reason);
        dto.setItems(List.of(item));
        return dto;
    }

    @Test
    void terminate_inProgressWithItems_terminatesAndCreatesReturnList() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        when(pickListMapper.selectOne(any())).thenReturn(null); // 无进行中退料单
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(g); // insertReturnList 明细快照
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("生产管理员");
        when(authService.getUserInfo()).thenReturn(user);

        // review 修复后：终止走条件更新（仅未完结态可终止）
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        service.terminate(7L, terminateDTO("销售交易单 SAL-1 已取消", 50L, 6));

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> termCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(productionOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), termCap.capture());
        assertTrue(termCap.getValue().getParamNameValuePairs().containsValue(BizProductionOrder.STATUS_TERMINATED),
                "应置已终止状态");
        assertTrue(termCap.getValue().getParamNameValuePairs().containsValue("原备注 | 终止原因: 销售交易单 SAL-1 已取消"),
                "应留痕终止原因");

        ArgumentCaptor<BizPickList> pickCap = ArgumentCaptor.forClass(BizPickList.class);
        verify(pickListMapper).insert(pickCap.capture());
        assertEquals(PickListService.TYPE_RETURN, pickCap.getValue().getPickType());
        assertEquals(PickListService.STATUS_PENDING, pickCap.getValue().getStatus());

        ArgumentCaptor<BizPickListDetail> detCap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(detCap.capture());
        assertEquals(6, detCap.getValue().getQuantity());

        verify(messageService).revokeUnreadByBiz("production_order", 7L);
        verify(messageService).sendPickReturnPendingToWarehouseAdmins(any(), eq("PRO-X"), any());
    }

    @Test
    void terminate_doneStatus_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_DONE));
        assertThrows(BusinessException.class, () -> service.terminate(7L, terminateDTO("x", 50L, 1)));
    }

    @Test
    void terminate_blankReason_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        assertThrows(BusinessException.class, () -> service.terminate(7L, terminateDTO("  ", 50L, 1)));
    }

    @Test
    void terminate_nullDto_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.terminate(7L, null));
        assertEquals("终止原因不能为空", ex.getMessage());
    }

    @Test
    void terminate_pendingPickExists_throws() {
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        when(pickListMapper.selectCount(any())).thenReturn(1L); // 有待出库领料单
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 50L, 1)));
        assertTrue(ex.getMessage().contains("待出库的领料单"));
    }

    @Test
    void terminate_qtyExceedsReturnable_throws() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 50L, 7)));
        assertTrue(ex.getMessage().contains("超过已领未退"));
    }

    @Test
    void terminate_zeroQtyRow_withoutDiffReason_throws() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        // F2/code-review：qty=0 行放行到差异守卫——0 < 已领未退（6）→ 差异备注必填，防静默丢料
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 50L, 0)));
        assertTrue(ex.getMessage().contains("差异备注"), "实际: " + ex.getMessage());
    }

    @Test
    void terminate_zeroQtyRow_withDiffReason_createsZeroQtyReturnRow() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        when(pickListMapper.selectOne(any())).thenReturn(null); // 无进行中退料单
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(g);
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("生产管理员");
        when(authService.getUserInfo()).thenReturn(user);

        // 全部损耗场景：qty=0 + 差异备注 → 0 数量退料行落库，仓储确认时可见
        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(0);
        item.setDiffReason("全部损耗于装配试装");
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("物料全部损耗，零退料终止");
        dto.setItems(List.of(item));

        service.terminate(7L, dto);

        ArgumentCaptor<BizPickListDetail> detCap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(detCap.capture());
        assertEquals(0, detCap.getValue().getQuantity());
        assertEquals("全部损耗于装配试装", detCap.getValue().getDiffReason());
    }

    @Test
    void terminate_junkZeroQtyRow_notInReturnableList_throws() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        // 净额 map 只含净额>0——不在清单内的 qty=0 垃圾行同样拒绝，防脏数据入退料单
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 99L, 0)));
        assertTrue(ex.getMessage().contains("不在该任务单已领未退清单内"), "实际: " + ex.getMessage());
    }

    @Test
    void terminate_noItems_onlyTerminates() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_PENDING));
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        service.terminate(7L, dto);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> termCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(productionOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), termCap.capture());
        assertTrue(termCap.getValue().getParamNameValuePairs().containsValue(BizProductionOrder.STATUS_TERMINATED),
                "应置已终止状态");
        verify(pickListMapper, never()).insert(any());
        verify(messageService).revokeUnreadByBiz("production_order", 7L);
    }

    @Test
    void terminate_revokePurchasesTrue_revokesInFlightRequests() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_PENDING));
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消，补料无需继续");
        dto.setItems(List.of());
        dto.setRevokePurchases(true);

        service.terminate(7L, dto);

        // D114：勾选撤销 → 同事务撤销在途补料申请（不豁免冻结）
        verify(purchaseRequestService).revokeByProductionOrderInternal(7L, true);
        verify(purchaseRequestService, never()).unfreezeInFlightByProductionOrder(any());
    }

    @Test
    void terminate_revokePurchasesFalse_unfreezesInFlightRequests() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_PENDING));
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("非销售关联终止，仍需此批物料");
        dto.setItems(List.of());
        dto.setRevokePurchases(false);

        service.terminate(7L, dto);

        // D114：不勾选 → 豁免销售冻结（freeze_exempt=1，采购可继续），不撤销
        verify(purchaseRequestService).unfreezeInFlightByProductionOrder(7L);
        verify(purchaseRequestService, never()).revokeByProductionOrderInternal(
                any(), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @Test
    void terminate_openReturnExists_skipsAutoCreate() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        when(pickListMapper.selectOne(any()))
                .thenReturn(pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_PENDING));
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);

        service.terminate(7L, terminateDTO("销售单取消", 50L, 6));

        verify(productionOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), any());
        verify(pickListMapper, never()).insert(any());
        verify(messageService).revokeUnreadByBiz("production_order", 7L);
    }

    @Test
    void computeReturnablePreview_netOfIssuedMinusReturned() {
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED),
                pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 10)),  // 第一次：出库明细
                List.of(detailOf(11L, 50L, "螺丝", 4)));  // 第二次：退回明细
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        g.setSpec("M3");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(6, vo.getItems().get(0).getQuantity());
        assertEquals("M3", vo.getItems().get(0).getSpec());
        assertEquals(Boolean.FALSE, vo.getHasOpenReturn());
    }

    @Test
    void computeReturnablePreview_supplyIssuedCountsAsPicked() {
        // SUPPLY（补料）已发料与 PICK 同口径计入「已领」
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_SUPPLY, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 5)));
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(5, vo.getItems().get(0).getQuantity());
    }

    @Test
    void computeReturnablePreview_multiGoods_filtersNonPositive() {
        // 多物料：净额>0 才进清单（goods 51 全退净额 0 被过滤）
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pickListOf(10L, PickListService.TYPE_PICK, PickListService.STATUS_ISSUED),
                pickListOf(11L, PickListService.TYPE_RETURN, PickListService.STATUS_ISSUED)));
        when(pickListDetailMapper.selectList(any())).thenReturn(
                List.of(detailOf(10L, 50L, "螺丝", 10), detailOf(10L, 51L, "螺母", 5)),
                List.of(detailOf(11L, 50L, "螺丝", 4), detailOf(11L, 51L, "螺母", 5)));
        BaseGoods g50 = new BaseGoods();
        g50.setId(50L);
        g50.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(g50));
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(1, vo.getItems().size());
        assertEquals(50L, vo.getItems().get(0).getGoodsId());
        assertEquals(6, vo.getItems().get(0).getQuantity());
    }

    @Test
    void terminate_itemNotInReturnableList_throws() {
        // 退料明细不在已领未退清单内 → 拒（ensureWithinReturnable max==null 分支）
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6); // 仅 goods 50 可退 6
        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.terminate(7L, terminateDTO("x", 99L, 1)));
        assertTrue(ex.getMessage().contains("不在该任务单已领未退清单内"));
    }

    @Test
    void terminate_existingRemark_appendsWithSeparator() {
        // appendRemark：旧备注非空 → "旧 | 终止原因: x"
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS)); // remark="原备注"
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        service.terminate(7L, dto);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> termCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(productionOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), termCap.capture());
        assertTrue(termCap.getValue().getParamNameValuePairs().containsValue("原备注 | 终止原因: 销售单取消"),
                "应追加终止原因");
    }

    @Test
    void terminate_blankRemark_replacesWithReason() {
        // appendRemark：旧备注空 → 仅后缀
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setRemark(null);
        mockTerminateBase(order);
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("销售单取消");
        dto.setItems(List.of());

        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        service.terminate(7L, dto);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper> termCap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(productionOrderMapper).update(org.mockito.ArgumentMatchers.isNull(), termCap.capture());
        assertTrue(termCap.getValue().getParamNameValuePairs().containsValue("终止原因: 销售单取消"),
                "应留痕终止原因");
    }


    // ==================== 需求二：现货履约（Q10a/Q11/Q13/Q15/Q16/Q17） ====================

    private BizSales salesOf(long id, long operatorId, Integer confirmStatus) {
        BizSales s = new BizSales();
        s.setId(id);
        s.setSalesNo("XS-88");
        s.setOperatorId(operatorId);
        s.setConfirmStatus(confirmStatus);
        return s;
    }

    private BizSalesDetail salesLineOf(int quantity) {
        BizSalesDetail line = new BizSalesDetail();
        line.setId(501L);
        line.setSalesId(88L);
        line.setGoodsId(29L);
        line.setQuantity(quantity);
        return line;
    }

    private BaseGoods goodsWithStock(long id, int stock) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setGoodsName("PTO153");
        g.setStock(stock);
        return g;
    }

    private void stubOperator() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("Test Operator");
        when(authService.getUserInfo()).thenReturn(user);
    }

    @Test
    void computeReturnablePreview_fillsSalesStockInfo_whenLinkedAndActive() {
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setSalesOrderId(88L);
        when(productionOrderMapper.selectById(7L)).thenReturn(order);
        when(pickListMapper.selectList(any())).thenReturn(List.of());
        lenient().when(pickListDetailMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(salesTerminateGuard.resolveLinkedLine(order)).thenReturn(salesLineOf(5));
        when(bizSalesMapper.selectById(88L)).thenReturn(salesOf(88L, 9L, SalesService.CONFIRM_PENDING));
        when(baseGoodsMapper.selectById(29L)).thenReturn(goodsWithStock(29L, 10));

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(10, vo.getGoodsStock());
        assertEquals(5, vo.getSalesLineQuantity());
        assertEquals(Boolean.TRUE, vo.getStockSufficient());
        assertEquals("XS-88", vo.getSalesOrderNo());
    }

    @Test
    void computeReturnablePreview_skipsStockInfoForNonSalesOrder() {
        // Q14: stock-fulfillment semantics only apply to sales-linked orders
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        when(pickListMapper.selectList(any())).thenReturn(List.of());
        lenient().when(pickListDetailMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectOne(any())).thenReturn(null);

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(null, vo.getGoodsStock());
        assertEquals(null, vo.getSalesOrderNo());
    }

    @Test
    void computeReturnablePreview_fillsInFlightPurchaseNos() {
        // Q17: in-flight production purchase request nos are surfaced in the preview
        when(productionOrderMapper.selectById(7L)).thenReturn(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        when(pickListMapper.selectList(any())).thenReturn(List.of());
        lenient().when(pickListDetailMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(purchaseRequestService.inFlightProductionRequestNos(7L)).thenReturn(List.of("CGSQ-1"));

        ProductionReturnableVO vo = service.computeReturnablePreview(7L);

        assertEquals(List.of("CGSQ-1"), vo.getInFlightPurchaseNos());
    }

    @Test
    void terminate_sufficientStock_sendsStockNoticeToSalesUser() {
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setSalesOrderId(88L);
        mockTerminateBase(order);
        mockReturnableData(6);
        stubOperator();
        BaseGoods returnedGoods = new BaseGoods();
        returnedGoods.setId(50L);
        returnedGoods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(returnedGoods); // insertReturnList 明细快照
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(salesTerminateGuard.resolveLinkedLine(order)).thenReturn(salesLineOf(5));
        when(bizSalesMapper.selectById(88L)).thenReturn(salesOf(88L, 9L, SalesService.CONFIRM_PENDING));
        when(baseGoodsMapper.selectById(29L)).thenReturn(goodsWithStock(29L, 10));

        service.terminate(7L, terminateDTO("stock ok", 50L, 6));

        verify(messageService).sendProductionTerminatedStockNoticeToSalesUser(9L, "PRO-X", "PTO153", "XS-88", 88L, 10, 5);
        verify(messageService, never()).sendProductionTerminatedReceiptToSalesUser(any(), anyString(), any(), any(), any());
    }

    @Test
    void terminate_insufficientStock_sendsGapNotice() {
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setSalesOrderId(88L);
        mockTerminateBase(order);
        mockReturnableData(6);
        stubOperator();
        BaseGoods returnedGoods = new BaseGoods();
        returnedGoods.setId(50L);
        returnedGoods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(returnedGoods); // insertReturnList 明细快照
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        when(salesTerminateGuard.resolveLinkedLine(order)).thenReturn(salesLineOf(5));
        when(bizSalesMapper.selectById(88L)).thenReturn(salesOf(88L, 9L, SalesService.CONFIRM_PENDING));
        when(baseGoodsMapper.selectById(29L)).thenReturn(goodsWithStock(29L, 3));

        service.terminate(7L, terminateDTO("stock short", 50L, 6));

        verify(messageService).sendProductionTerminatedStockNoticeToSalesUser(9L, "PRO-X", "PTO153", "XS-88", 88L, 3, 5);
    }

    @Test
    void terminate_shippedSales_skipsStockNotice() {
        BizProductionOrder order = terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS);
        order.setSalesOrderId(88L);
        mockTerminateBase(order);
        mockReturnableData(6);
        stubOperator();
        BaseGoods returnedGoods = new BaseGoods();
        returnedGoods.setId(50L);
        returnedGoods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectById(50L)).thenReturn(returnedGoods); // insertReturnList 明细快照
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        lenient().when(salesTerminateGuard.resolveLinkedLine(order)).thenReturn(salesLineOf(5)); // shipped 短路后不消费
        when(bizSalesMapper.selectById(88L)).thenReturn(salesOf(88L, 9L, SalesService.CONFIRM_SHIPPED));

        service.terminate(7L, terminateDTO("shipped", 50L, 6));

        verify(messageService, never()).sendProductionTerminatedStockNoticeToSalesUser(any(), anyString(), any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void terminate_diffRow_withoutDiffReason_rejected() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(4); // < max 6 => diff 2
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("diff case");
        dto.setItems(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.terminate(7L, dto));
        assertTrue(ex.getMessage().contains("差异备注"));
    }

    @Test
    void terminate_diffRow_withDiffReason_persistsDiffReason() {
        mockTerminateBase(terminatableOrder(BizProductionOrder.STATUS_IN_PROGRESS));
        mockReturnableData(6);
        stubOperator();
        when(pickListMapper.selectOne(any())).thenReturn(null);
        when(productionOrderMapper.update(org.mockito.ArgumentMatchers.isNull(), any())).thenReturn(1);
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setGoodsName("PTO153");
        when(baseGoodsMapper.selectById(50L)).thenReturn(g);
        ProductionReturnItemDTO item = new ProductionReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(4); // < max 6 => diff 2
        item.setDiffReason("loss-reason-marker");
        ProductionTerminateDTO dto = withDiffReason(item);
        service.terminate(7L, dto);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<BizPickListDetail> detCap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(pickListDetailMapper).insert(detCap.capture());
        assertEquals("loss-reason-marker", detCap.getValue().getDiffReason());
    }

    private ProductionTerminateDTO withDiffReason(ProductionReturnItemDTO item) {
        ProductionTerminateDTO dto = new ProductionTerminateDTO();
        dto.setReason("diff case");
        dto.setItems(List.of(item));
        return dto;
    }
}
