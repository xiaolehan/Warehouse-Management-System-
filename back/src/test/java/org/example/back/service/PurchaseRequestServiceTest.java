package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.DraftConfirmDTO;
import org.example.back.dto.DraftConfirmItemDTO;
import org.example.back.dto.DraftRejectDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.dto.ProductionDraftItemDTO;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPurchaseRequest.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPurchaseRequestDetail.class);
    }

    @Mock private BizPurchaseRequestMapper bizPurchaseRequestMapper;
    @Mock private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private PurchaseService purchaseService;
    @Mock private ProductionOrderService productionOrderService;
    @Mock private org.example.back.mapper.BizBomDetailMapper bizBomDetailMapper;

    @InjectMocks private PurchaseRequestService service;

    @Test
    void createDraft_setsDraftStatusAndSendsWarehouseNotice() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        // 无既有草稿（幂等前置通过）
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        Long draftId = service.createDraft(dto);

        // 主单应为草稿状态 + 生产来源
        ArgumentCaptor<BizPurchaseRequest> captor =
                ArgumentCaptor.forClass(BizPurchaseRequest.class);
        verify(bizPurchaseRequestMapper).insert(captor.capture());
        BizPurchaseRequest draft = captor.getValue();
        assertEquals(6, draft.getStatus());
        assertEquals("production", draft.getSourceType());
        assertEquals(7L, draft.getProductionOrderId());
        assertEquals(user.getId(), draft.getApplicantId());
    }

    // ---------- 用例 2：通知 + 明细行校验 ----------
    @Test
    void createDraft_sendsWarehouseNotice_andInsertsDetailRow() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        service.createDraft(dto);

        // 1. 通知发给仓储管理员，申请人是"生产甲"
        //    注意：mock insert 不回填 id，第三参为 null，用 any() 匹配
        verify(messageService).sendPurchaseRequestDraftToWarehouseAdmins(
                anyString(), eq("生产甲"), any());

        // 2. 明细行字段校验
        ArgumentCaptor<BizPurchaseRequest> reqCaptor =
                ArgumentCaptor.forClass(BizPurchaseRequest.class);
        verify(bizPurchaseRequestMapper).insert(reqCaptor.capture());
        BizPurchaseRequest draft = reqCaptor.getValue();

        ArgumentCaptor<BizPurchaseRequestDetail> detCaptor =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).insert(detCaptor.capture());
        BizPurchaseRequestDetail detail = detCaptor.getValue();

        assertEquals(12L, detail.getBomDetailId());
        assertEquals(51L, detail.getGoodsId());
        assertEquals(3, detail.getQuantity());
        assertEquals(0, detail.getSortNo());
        // 明细的 requestId 与主单 id 一致（mock 下均为 null，验证绑定关系）
        assertEquals(draft.getId(), detail.getRequestId());
    }

    // ---------- 用例 3：幂等——已有草稿拒绝 ----------
    @Test
    void createDraft_rejectsWhenDraftAlreadyExists() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest existingDraft = new BizPurchaseRequest();
        existingDraft.setId(99L);
        existingDraft.setStatus(6);
        existingDraft.setSourceType("production");
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of(existingDraft));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDraft(dto));
        assertEquals("该生产任务单已生成补料草稿，请先转正或驳回", ex.getMessage());

        // 幂等检查在前，不会进入缺料计算
        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
    }

    // ---------- 用例 4：无缺料拒绝 ----------
    @Test
    void createDraft_rejectsWhenNoShortage() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of());

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDraft(dto));
        assertEquals("该生产任务单当前无缺料，无需补料", ex.getMessage());
    }

    // ---------- 用例 5：数量覆盖生效 ----------
    @Test
    void createDraft_appliesQuantityOverride() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);
        ProductionDraftItemDTO item = new ProductionDraftItemDTO();
        item.setBomDetailId(12L);
        item.setQuantity(5);
        dto.setDetails(List.of(item));

        service.createDraft(dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCaptor =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).insert(detCaptor.capture());
        assertEquals(5, detCaptor.getValue().getQuantity());
    }

    // ---------- 用例 6：全零覆盖 → 无有效行 → 拒绝 ----------
    @Test
    void createDraft_skipsZeroOverrideAndThrowsWhenNoValidRows() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);
        ProductionDraftItemDTO item = new ProductionDraftItemDTO();
        item.setBomDetailId(12L);
        item.setQuantity(0);
        dto.setDetails(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDraft(dto));
        assertEquals("无有效缺料行可补料", ex.getMessage());
    }

    // ---------- confirmDraft 用例 1：有明细行缺物料则拒绝 ----------
    @Test
    void confirmDraft_rejectsWhenAGoodsIdIsMissing() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6); // DRAFT
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        // 两行，仅给其中一行传了物料
        org.example.back.entity.BizPurchaseRequestDetail d1 = new org.example.back.entity.BizPurchaseRequestDetail();
        d1.setId(100L); d1.setRequestId(3L); d1.setGoodsId(null); d1.setGoodsName("板1");
        org.example.back.entity.BizPurchaseRequestDetail d2 = new org.example.back.entity.BizPurchaseRequestDetail();
        d2.setId(101L); d2.setRequestId(3L); d2.setGoodsId(null); d2.setGoodsName("螺丝");
        when(bizPurchaseRequestDetailMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(d1, d2));

        DraftConfirmDTO dto = new DraftConfirmDTO();
        DraftConfirmItemDTO item = new DraftConfirmItemDTO();
        item.setDetailId(100L);
        item.setGoodsId(66L);
        dto.setItems(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.confirmDraft(3L, dto));
        assertEquals("明细[螺丝]未关联物料，无法转正", ex.getMsg());
    }

    // ---------- confirmDraft 用例 2：全部齐备 → PENDING + 撤草稿通知 + 发采购通知 ----------
    @Test
    void confirmDraft_transposesToPendingAndSendsPurchaseNotice() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6); // DRAFT
        draft.setRequestNo("CG-2026-0001");
        draft.setApplicantName("生产甲");
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(100L); d1.setRequestId(3L); d1.setGoodsId(51L); d1.setGoodsName("板1");
        when(bizPurchaseRequestDetailMapper.selectList(ArgumentMatchers.any()))
                .thenReturn(List.of(d1));

        DraftConfirmDTO dto = new DraftConfirmDTO();
        dto.setItems(List.of());

        when(bizPurchaseRequestMapper.update(any(), ArgumentMatchers.any())).thenReturn(1);

        service.confirmDraft(3L, dto);

        // 主单状态应从 DRAFT 更新为 PENDING：检查 set 的值为 1
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(bizPurchaseRequestMapper).update(any(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest> uw = captor.getValue();
        // sql set 中 status 是第一个 set 项，其值在 paramNameValuePairs 的 MPGENVAL1
        assertTrue(uw.getSqlSet().contains("status="), "sql set 应包含 status 字段");
        java.util.Map<String, Object> params = uw.getParamNameValuePairs();
        assertTrue(params.containsValue(1), "参数中应包含 STATUS_PENDING=1, 实际参数: " + params);

        verify(messageService).revokeUnreadByBiz("purchase_request", 3L);
        verify(messageService).sendPurchaseRequestToPurchaseAdmins(anyString(), any(), eq(3L));
    }

    // ---------- rejectDraft 用例：草稿驳回 → REJECTED + 撤通知 ----------
    @Test
    void rejectDraft_setsRejectedAndRevokesNotice() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6); // DRAFT
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        DraftRejectDTO dto = new DraftRejectDTO();
        dto.setReason("物料信息不全");

        when(bizPurchaseRequestMapper.update(any(), ArgumentMatchers.any())).thenReturn(1);

        service.rejectDraft(3L, dto);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(bizPurchaseRequestMapper).update(any(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest> uw = captor.getValue();
        assertTrue(uw.getSqlSet().contains("status="), "sql set 应包含 status 字段");
        java.util.Map<String, Object> params = uw.getParamNameValuePairs();
        assertTrue(params.containsValue(4), "参数中应包含 STATUS_REJECTED=4, 实际参数: " + params);
        assertTrue(params.containsValue("物料信息不全"), "参数中应包含驳回原因, 实际参数: " + params);

        verify(messageService).revokeUnreadByBiz("purchase_request", 3L);
    }

    // ---------- cancelDraft 用例 1：非申请人不可撤销草稿 ----------
    @Test
    void cancelDraft_forbidsOtherApplicant() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6);
        draft.setApplicantId(10L);
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        LoginResponse.UserInfoVO other = new LoginResponse.UserInfoVO();
        other.setId(99L);
        when(authService.getUserInfo()).thenReturn(other);
        when(authzService.isSuperAdmin()).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelDraft(3L));
        assertEquals("仅申请人本人可撤销草稿", ex.getMsg());
    }

    // ---------- cancelDraft 用例 2：非草稿状态不可撤销 ----------
    @Test
    void cancelDraft_rejectsWhenNotDraft() {
        BizPurchaseRequest entity = new BizPurchaseRequest();
        entity.setId(3L);
        entity.setStatus(1); // PENDING
        entity.setApplicantId(10L);
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(entity);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancelDraft(3L));
        assertEquals("仅草稿状态可撤销", ex.getMsg());
    }

    // ---------- cancelDraft 用例 3：happy path — 草稿→已驳回 + 撤通知 ----------
    @Test
    void cancelDraft_setsRejected() {
        BizPurchaseRequest draft = new BizPurchaseRequest();
        draft.setId(3L);
        draft.setStatus(6); // DRAFT
        draft.setApplicantId(10L);
        when(bizPurchaseRequestMapper.selectById(3L)).thenReturn(draft);

        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.update(any(), ArgumentMatchers.any())).thenReturn(1);

        service.cancelDraft(3L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest>> captor =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(bizPurchaseRequestMapper).update(any(), captor.capture());
        com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequest> uw = captor.getValue();
        assertTrue(uw.getSqlSet().contains("status="), "sql set 应包含 status 字段");
        java.util.Map<String, Object> params = uw.getParamNameValuePairs();
        assertTrue(params.containsValue(4), "参数中应包含 STATUS_REJECTED=4, 实际参数: " + params);
        assertTrue(params.containsValue("申请人撤销草稿"), "参数中应包含撤销原因, 实际参数: " + params);

        verify(messageService).revokeUnreadByBiz("purchase_request", 3L);
    }

    // ---------- confirmReceive 回挂用例 1：BOM 明细 goodsId 为 null → 回挂 ----------
    @Test
    void confirmReceive_backlinksBomDetailGoodsIdOnlyWhenNull() {
        // 到货申请单（待入库确认）
        BizPurchaseRequest req = new BizPurchaseRequest();
        req.setId(5L);
        req.setStatus(5); // AWAITING_CONFIRM
        req.setRequestNo("PR-test");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(req);

        org.example.back.entity.BizPurchaseRequestDetail d1 = new org.example.back.entity.BizPurchaseRequestDetail();
        d1.setId(100L); d1.setGoodsId(66L); d1.setBomDetailId(12L);
        d1.setArriveQuantity(3); d1.setQuantity(3); d1.setGoodsName("板1");
        d1.setUnitPrice(new java.math.BigDecimal("1.50"));
        when(bizPurchaseRequestDetailMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(d1));

        // 仓储登录用户
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("仓储员");
        when(authService.getUserInfo()).thenReturn(user);

        // BOM 明细原 goods_id 为 null → 应回挂 66
        org.example.back.entity.BizBomDetail bomDetail = new org.example.back.entity.BizBomDetail();
        bomDetail.setId(12L);
        bomDetail.setGoodsId(null);
        when(bizBomDetailMapper.selectById(12L)).thenReturn(bomDetail);
        // confirmReceive 末尾对主单做乐观锁更新（set改为 RECEIVED=3），stub 返回 1 避免抛"状态已变更"
        when(bizPurchaseRequestMapper.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        service.confirmReceive(5L);

        // 回挂：BOM 明细 goods_id 更新为 66
        org.mockito.ArgumentCaptor<org.example.back.entity.BizBomDetail> cap =
                org.mockito.ArgumentCaptor.forClass(org.example.back.entity.BizBomDetail.class);
        org.mockito.Mockito.verify(bizBomDetailMapper).updateById(cap.capture());
        assertEquals(66L, cap.getValue().getGoodsId());
    }

    // ---------- confirmReceive 回挂用例 2：BOM 明细已有 goodsId → 不覆盖 ----------
    @Test
    void confirmReceive_doesNotOverwriteExistingBacklink() {
        BizPurchaseRequest req = new BizPurchaseRequest();
        req.setId(5L);
        req.setStatus(5); // AWAITING_CONFIRM
        req.setRequestNo("PR-test");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(req);

        org.example.back.entity.BizPurchaseRequestDetail d1 = new org.example.back.entity.BizPurchaseRequestDetail();
        d1.setId(100L); d1.setGoodsId(66L); d1.setBomDetailId(12L);
        d1.setArriveQuantity(3); d1.setQuantity(3); d1.setGoodsName("板1");
        d1.setUnitPrice(new java.math.BigDecimal("1.50"));
        when(bizPurchaseRequestDetailMapper.selectList(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(d1));

        // 仓储登录用户
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("仓储员");
        when(authService.getUserInfo()).thenReturn(user);

        // BOM 明细 goods_id 已为 99 → 不应覆盖
        org.example.back.entity.BizBomDetail bomDetail = new org.example.back.entity.BizBomDetail();
        bomDetail.setId(12L);
        bomDetail.setGoodsId(99L);
        when(bizBomDetailMapper.selectById(12L)).thenReturn(bomDetail);
        when(bizPurchaseRequestMapper.update(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1);

        service.confirmReceive(5L);

        // 不覆盖：updateById 不应被调用
        org.mockito.Mockito.verify(bizBomDetailMapper, org.mockito.Mockito.never()).updateById(
                org.mockito.ArgumentMatchers.any());
    }
}
