package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.dto.ProductionDraftItemDTO;
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.example.back.entity.BizBomDetail;
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
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock private GoodsService goodsService;
    @Mock private org.example.back.mapper.BizBomDetailMapper bizBomDetailMapper;

    @InjectMocks private PurchaseRequestService service;

    @Test
    void createDraft_setsPendingStatusAndSendsPurchaseNotice() {
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

        // 主单应为待采购状态 + 生产来源 + 通知采购管理员
        ArgumentCaptor<BizPurchaseRequest> captor =
                ArgumentCaptor.forClass(BizPurchaseRequest.class);
        verify(bizPurchaseRequestMapper).insert(captor.capture());
        BizPurchaseRequest draft = captor.getValue();
        assertEquals(1, draft.getStatus());
        assertEquals("production", draft.getSourceType());
        assertEquals(7L, draft.getProductionOrderId());
        assertEquals(user.getId(), draft.getApplicantId());
        verify(messageService).sendPurchaseRequestToPurchaseAdmins(anyString(), eq("生产甲"), any());
    }

    // ---------- 用例 2：通知 + 明细行校验 ----------
    @Test
    void createDraft_sendsPurchaseNotice_andInsertsDetailRow() {
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

        // 1. 通知发给采购管理员，申请人是"生产甲"
        //    注意：mock insert 不回填 id，第三参为 null，用 any() 匹配
        verify(messageService).sendPurchaseRequestToPurchaseAdmins(
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

    // ---------- 用例 3：幂等——已有非终态补料单拒绝 ----------
    @Test
    void createDraft_rejectsWhenNonFinalRequestAlreadyExists() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest existing = new BizPurchaseRequest();
        existing.setId(99L);
        existing.setStatus(1); // PENDING（非终态）
        existing.setSourceType("production");
        existing.setRequestNo("PR-001");
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of(existing));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createDraft(dto));
        assertTrue(ex.getMessage().contains("请勿重复"), "错误消息应包含请勿重复, 实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PR-001"), "错误消息应包含已有单号, 实际: " + ex.getMessage());

        // 幂等检查在前，不会进入缺料计算
        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
    }

    // ---------- 用例 3b：幂等——已驳回的补料单可重新发起 ----------
    @Test
    void createDraft_allowsResubmissionAfterRejected() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        // listNonFinal 返回空（已有补料单是 REJECTED，被过滤掉）
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        // 不应抛异常：已驳回后可重新发起
        assertDoesNotThrow(() -> service.createDraft(dto));
        verify(bizPurchaseRequestMapper).insert(any(BizPurchaseRequest.class));
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

    // ---------- D60/ADR-0002：未知物料行自动建档 + 回绑 BOM 行 + 明细快照 ----------
    @Test
    void createDraft_unknownRow_autoRegistersBindsBomAndSnapshots() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(null); // 未知物料
        line.setGoodsName("新轴承");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);
        ProductionDraftItemDTO item = new ProductionDraftItemDTO();
        item.setBomDetailId(12L);
        item.setNewGoodsName("新轴承");
        item.setSpec(" M8 ");
        item.setMaterial("不锈钢");
        item.setRemark("急件");
        item.setUnit("个");
        dto.setDetails(List.of(item));

        // 自动建档（规格原样传，建档方法内部 trim）→ 新物料 id=88
        when(goodsService.createMaterialFromProduction("新轴承", " M8 ", "不锈钢", "个")).thenReturn(88L);

        // BOM 行原为未绑定 → 提交后回绑
        BizBomDetail bomRow = new BizBomDetail();
        bomRow.setId(12L);
        bomRow.setGoodsId(null);
        bomRow.setComponentName("新轴承");
        when(bizBomDetailMapper.selectById(12L)).thenReturn(bomRow);

        service.createDraft(dto);

        // 明细快照：goodsId=自动建档 id、规格/材质/备注已 trim、isNewMaterial=1、名称取建档名
        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).insert(detCap.capture());
        BizPurchaseRequestDetail detail = detCap.getValue();
        assertEquals(88L, detail.getGoodsId());
        assertEquals("新轴承", detail.getGoodsName());
        assertEquals("M8", detail.getSpec());
        assertEquals("不锈钢", detail.getMaterial());
        assertEquals("急件", detail.getRemark());
        assertEquals(1, detail.getIsNewMaterial());

        // 回绑：BOM 行 goodsId/规格/材质/备注与建档信息对齐
        ArgumentCaptor<BizBomDetail> bomCap = ArgumentCaptor.forClass(BizBomDetail.class);
        verify(bizBomDetailMapper).updateById(bomCap.capture());
        BizBomDetail bound = bomCap.getValue();
        assertEquals(88L, bound.getGoodsId());
        assertEquals("M8", bound.getSpec());
        assertEquals("不锈钢", bound.getMaterial());
        assertEquals("急件", bound.getRemark());
    }

    // ---------- D60/ADR-0002：未知行未填新物料信息 → 整单退回，不建档不发通知 ----------
    @Test
    void createDraft_unknownRowWithoutName_throwsWholeOrder() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(null);
        line.setGoodsName("新轴承");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);
        // 未传明细行（没有 newGoodsName 也没有改绑 goodsId）

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createDraft(dto));
        assertTrue(ex.getMessage().contains("未在仓库建档"), "错误消息应提示建档/改绑, 实际: " + ex.getMessage());
        verify(goodsService, never()).createMaterialFromProduction(any(), any(), any(), any());
        verify(bizPurchaseRequestDetailMapper, never()).insert(any(BizPurchaseRequestDetail.class));
        verify(messageService, never()).sendPurchaseRequestToPurchaseAdmins(any(), any(), any());
    }

    // ---------- D60：已绑定行快照——规格/材质/备注来自 BOM 缺口行，isNewMaterial=0 ----------
    @Test
    void createDraft_boundRowSnapshotsSpecFromLine() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setSpec("M8");
        line.setMaterial("不锈钢");
        line.setRemark("急件");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        service.createDraft(dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).insert(detCap.capture());
        BizPurchaseRequestDetail detail = detCap.getValue();
        assertEquals(51L, detail.getGoodsId());
        assertEquals("板1", detail.getGoodsName());
        assertEquals("M8", detail.getSpec());
        assertEquals("不锈钢", detail.getMaterial());
        assertEquals("急件", detail.getRemark());
        assertEquals(0, detail.getIsNewMaterial());
        verify(goodsService, never()).createMaterialFromProduction(any(), any(), any(), any());
    }

    // ---------- D61：认领按行写预计到货时间+到货备注 ----------
    @Test
    void process_writesPerLineArrivalInfo() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("采购乙");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("production");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        BizPurchaseRequestDetail d2 = new BizPurchaseRequestDetail();
        d2.setId(102L);
        d2.setGoodsName("钢板");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1, d2));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        LocalDateTime t1 = LocalDateTime.of(2026, 9, 15, 0, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 9, 20, 0, 0);
        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(
                processItem(101L, t1, " 厂家A直发 "),
                processItem(102L, t2, null)));

        service.process(5L, dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper, times(2)).updateById(detCap.capture());
        List<BizPurchaseRequestDetail> updated = detCap.getAllValues();
        assertEquals(t1, updated.get(0).getExpectedArrivalTime());
        assertEquals("厂家A直发", updated.get(0).getArrivalRemark(), "备注应 trim");
        assertEquals(t2, updated.get(1).getExpectedArrivalTime());
        assertNull(updated.get(1).getArrivalRemark(), "空备注应落 NULL");
        verify(messageService).revokeUnreadByBiz("purchase_request", 5L);
    }

    private static PurchaseRequestProcessDTO.ProcessItemDTO processItem(Long detailId, LocalDateTime time, String remark) {
        PurchaseRequestProcessDTO.ProcessItemDTO item = new PurchaseRequestProcessDTO.ProcessItemDTO();
        item.setDetailId(detailId);
        item.setExpectedArrivalTime(time);
        item.setArrivalRemark(remark);
        return item;
    }

    // ---------- D61：任一行缺预计到货时间 → 整单退回 ----------
    @Test
    void process_rejectsWhenAnyLineMissingTime() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("warehouse");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        BizPurchaseRequestDetail d2 = new BizPurchaseRequestDetail();
        d2.setId(102L);
        d2.setGoodsName("钢板");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1, d2));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 15, 0, 0), null)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.process(5L, dto));
        assertTrue(ex.getMessage().contains("缺少预计到货时间"), "实际: " + ex.getMessage());
        verify(bizPurchaseRequestMapper, never()).update(any(), any());
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    // ---------- D61：非待采购状态不可认领（回归保护） ----------
    @Test
    void process_rejectsWhenNotPending() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.process(5L, new PurchaseRequestProcessDTO()));
        assertEquals("仅待采购状态可认领", ex.getMessage());
    }

    // ---------- D61：认领通知来源申请人（行级到货摘要） ----------
    @Test
    void process_notifiesSourceApplicantWithArrivalSummary() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("采购乙");
        when(authService.getUserInfo()).thenReturn(user);

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(1);
        request.setRequestNo("PR-1");
        request.setSourceType("production");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 15, 0, 0), null)));

        service.process(5L, dto);

        verify(messageService).sendPurchaseRequestClaimedToSourceApplicant(
                eq("PR-1"), eq("采购乙"), eq("production"),
                Mockito.contains("轴承 2026-09-15"), eq(5L));
    }

    // ---------- D61：修改到货计划（仅采购中） ----------
    @Test
    void updateArrivalPlan_updatesDetailRowsInPurchasingStatus() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        request.setRequestNo("PR-1");
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(101L, LocalDateTime.of(2026, 9, 25, 0, 0), "改发厂家B")));

        service.updateArrivalPlan(5L, dto);

        ArgumentCaptor<BizPurchaseRequestDetail> detCap =
                ArgumentCaptor.forClass(BizPurchaseRequestDetail.class);
        verify(bizPurchaseRequestDetailMapper).updateById(detCap.capture());
        assertEquals(LocalDateTime.of(2026, 9, 25, 0, 0), detCap.getValue().getExpectedArrivalTime());
        assertEquals("改发厂家B", detCap.getValue().getArrivalRemark());
        // 修改不触发任何消息与撤销
        verify(messageService, never()).sendPurchaseRequestClaimedToSourceApplicant(any(), any(), any(), any(), any());
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    @Test
    void updateArrivalPlan_rejectsWhenNotPurchasing() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5); // 待入库确认，锁定
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.updateArrivalPlan(5L, new PurchaseRequestProcessDTO()));
        assertEquals("仅采购中状态可修改到货计划", ex.getMessage());
    }

    // ---------- D61：到货撤回仅撤销仓储部未读消息，保留认领通知 ----------
    @Test
    void arriveCancel_revokesOnlyWarehouseDeptUnread() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5); // AWAITING_CONFIRM
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        service.arriveCancel(5L);

        verify(messageService).revokeUnreadByBizAndDeptCode(
                eq("purchase_request"), eq(5L), eq(AuthzService.DEPT_WAREHOUSE));
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    // ---------- D61：入库驳回仅撤销仓储部未读消息，保留认领通知 ----------
    @Test
    void arriveReject_revokesOnlyWarehouseDeptUnread() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5); // AWAITING_CONFIRM
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        service.arriveReject(5L);

        verify(messageService).revokeUnreadByBizAndDeptCode(
                eq("purchase_request"), eq(5L), eq(AuthzService.DEPT_WAREHOUSE));
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    // ---------- D61：明细ID为空时单独报错 ----------
    @Test
    void updateArrivalPlan_rejectsNullDetailId() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        PurchaseRequestProcessDTO.ProcessItemDTO item = new PurchaseRequestProcessDTO.ProcessItemDTO();
        item.setDetailId(null);
        item.setExpectedArrivalTime(LocalDateTime.of(2026, 9, 25, 0, 0));
        dto.setItems(List.of(item));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateArrivalPlan(5L, dto));
        assertEquals("明细ID不能为空", ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).updateById(any(BizPurchaseRequestDetail.class));
    }

    @Test
    void updateArrivalPlan_rejectsUnknownDetailId() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(2);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);

        BizPurchaseRequestDetail d1 = new BizPurchaseRequestDetail();
        d1.setId(101L);
        d1.setGoodsName("轴承");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(d1));

        PurchaseRequestProcessDTO dto = new PurchaseRequestProcessDTO();
        dto.setItems(List.of(processItem(999L, LocalDateTime.of(2026, 9, 25, 0, 0), null)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.updateArrivalPlan(5L, dto));
        assertTrue(ex.getMessage().contains("缺少预计到货时间"), "未知 detailId 应视为缺行, 实际: " + ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).updateById(any(BizPurchaseRequestDetail.class));
    }
}
