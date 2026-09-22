package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.dto.ProductionDraftItemDTO;
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.example.back.dto.PurchaseRequestReceiveDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.PurchaseRequestVO;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizProductionOrderMapper;
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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
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
    @Mock private org.example.back.mapper.BaseSupplierMapper baseSupplierMapper;
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;

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

    // ---------- 用例 3c：D86 幂等守卫口径——仅在途状态(1待采购/2采购中/5待入库确认)阻止再补料，已入库(3)放行 ----------
    @Test
    void createDraft_guardCoversOnlyInFlightStatuses() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        // 在途单查询返回空（已入库单被查询条件过滤掉，不再占用幂等名额）
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());
        // 走到缺料计算即抛"无缺料"——借此确认守卫未拦截，且捕获守卫查询 wrapper
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of());

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.createDraft(dto));
        assertEquals("该生产任务单当前无缺料，无需补料", ex.getMessage());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<LambdaQueryWrapper<BizPurchaseRequest>> captor =
                ArgumentCaptor.forClass(LambdaQueryWrapper.class);
        verify(bizPurchaseRequestMapper).selectList(captor.capture());
        LambdaQueryWrapper<BizPurchaseRequest> guardQuery = captor.getValue();
        String sqlSegment = guardQuery.getSqlSegment();
        assertTrue(sqlSegment.contains("status") && sqlSegment.contains("IN"),
                "守卫查询应按 status IN 在途状态过滤（已入库终态应放行）, 实际: " + sqlSegment);
        assertTrue(guardQuery.getParamNameValuePairs().containsValue(1)
                        && guardQuery.getParamNameValuePairs().containsValue(2)
                        && guardQuery.getParamNameValuePairs().containsValue(5),
                "在途状态参数应含 1/2/5, 实际: " + guardQuery.getParamNameValuePairs());
        assertFalse(guardQuery.getParamNameValuePairs().containsValue(3),
                "已入库(3)不应出现在守卫参数中, 实际: " + guardQuery.getParamNameValuePairs());
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

    // ---------- D120：到货撤回只回本批行、按标题白名单撤到货待办，历史批次不动 ----------
    @Test
    void arriveCancel_resetsOnlyBatchLines_andRevokesArrivedTitle() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5); // AWAITING_CONFIRM
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);
        BizPurchaseRequestDetail batchLine = batchDetail(201L, 2, "B1");
        BizPurchaseRequestDetail historyLine = batchDetail(200L, 3, "B1");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(historyLine, batchLine));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        service.arriveCancel(5L);

        // 只本批行（id=201）被回写一次；历史行(id=200)状态3不匹配，不会被重复回写
        verify(bizPurchaseRequestDetailMapper, times(1)).update(any(), org.mockito.ArgumentMatchers.any());
        // 白名单撤回
        verify(messageService).revokeUnreadByBizAndTitles(
                eq("purchase_request"), eq(5L),
                eq(List.of(MessageService.TITLE_PURCHASE_REQUEST_ARRIVED)));
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    // ---------- D120：入库驳回同样只退本批 ----------
    @Test
    void arriveReject_resetsOnlyBatchLines_andRevokesArrivedTitle() {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(5L);
        request.setStatus(5);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(request);
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(batchDetail(201L, 2, "B1")));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);

        service.arriveReject(5L);

        verify(messageService).revokeUnreadByBizAndTitles(
                eq("purchase_request"), eq(5L),
                eq(List.of(MessageService.TITLE_PURCHASE_REQUEST_ARRIVED)));
        verify(messageService, never()).revokeUnreadByBiz(anyString(), any());
    }

    private BizPurchaseRequestDetail batchDetail(long id, int receiveStatus, String batchNo) {
        BizPurchaseRequestDetail d = new BizPurchaseRequestDetail();
        d.setId(id);
        d.setRequestId(5L);
        d.setGoodsName("物料" + id);
        d.setQuantity(5);
        d.setReceiveStatus(receiveStatus);
        d.setArriveBatchNo(batchNo);
        return d;
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

    // ============================== D120：按行分批到货 ==============================

    private BizPurchaseRequest purchasingHead(long id) {
        BizPurchaseRequest head = new BizPurchaseRequest();
        head.setId(id);
        head.setRequestNo("PR-" + id);
        head.setStatus(PurchaseRequestService.STATUS_PURCHASING);
        head.setSourceType("warehouse");
        return head;
    }

    private BizPurchaseRequestDetail requestDetail(long id, int receiveStatus, String name, int quantity) {
        BizPurchaseRequestDetail d = new BizPurchaseRequestDetail();
        d.setId(id);
        d.setRequestId(5L);
        d.setGoodsId(id + 1000);
        d.setGoodsName(name);
        d.setQuantity(quantity);
        d.setReceiveStatus(receiveStatus);
        return d;
    }

    private static PurchaseRequestReceiveDTO.ReceiveItemDTO arriveItem(Long detailId, String unitPrice) {
        return arriveItem(detailId, unitPrice, 2L);
    }

    private static PurchaseRequestReceiveDTO.ReceiveItemDTO arriveItem(Long detailId, String unitPrice, Long supplierId) {
        PurchaseRequestReceiveDTO.ReceiveItemDTO item = new PurchaseRequestReceiveDTO.ReceiveItemDTO();
        item.setDetailId(detailId);
        item.setUnitPrice(new BigDecimal(unitPrice));
        item.setSupplierId(supplierId);
        return item;
    }

    private static BaseSupplier supplier(long id, int status) {
        BaseSupplier s = new BaseSupplier();
        s.setId(id);
        s.setStatus(status);
        return s;
    }

    private LoginResponse.UserInfoVO purchaseUser() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("采购乙");
        return user;
    }

    @Test
    void arrive_partialLines_setsBatchAndHeadStatus_andNotifies() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        BizPurchaseRequestDetail line1 = requestDetail(101L, 1, "钢板", 19);
        BizPurchaseRequestDetail line2 = requestDetail(102L, 1, "螺丝", 1);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(line1, line2));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(purchaseUser());
        when(baseSupplierMapper.selectById(2L)).thenReturn(supplier(2L, 1));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        // 只勾选 101 一行（模拟 19 个先到，1 个未到）
        dto.setItems(List.of(arriveItem(101L, "50.00")));
        service.arrive(5L, dto);

        // 明细回写：行→待确认(2)、批次 B1、到货量=整行申请量 19（无拆量）
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequestDetail>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(bizPurchaseRequestDetailMapper).update(isNull(), cap.capture());
        String sqlSet = String.valueOf(cap.getValue().getSqlSet());
        // .set 的值在参数 Map 中（sqlSet 里是占位符）
        var params = cap.getValue().getParamNameValuePairs();
        assertTrue(sqlSet.contains("receiveStatus=") && params.containsValue(2), sqlSet + params);
        assertTrue(sqlSet.contains("arriveBatchNo=") && params.containsValue("B1"), sqlSet + params);
        assertTrue(sqlSet.contains("arriveQuantity=") && params.containsValue(19), sqlSet + params);
        // 头 2→5；到货消息带批次与行数
        verify(messageService).sendPurchaseRequestArrivedToWarehouseAdmins(
                eq("PR-5"), eq("采购乙"), eq("B1"), eq(1), eq(5L));
    }

    @Test
    void arrive_rejectsAlreadyReceivedLine() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        // 行已是 已入库(3)
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 3, "钢板", 19)));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00")));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("已入库，不可重复到货"), ex.getMessage());

        verify(bizPurchaseRequestMapper, never()).update(any(), any());
        verify(messageService, never()).sendPurchaseRequestArrivedToWarehouseAdmins(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void arrive_rejectsWhenAnotherBatchAwaiting() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        // 头=2 却存在 待确认行（异常态）→ 防御性拦截
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 2, "钢板", 19)));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00")));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("待入库确认的批次"), ex.getMessage());
    }

    @Test
    void confirmReceive_partial_returnsToPurchasing_andReceiptOnlyForBatch() {
        BizPurchaseRequest head = purchasingHead(5L);
        head.setStatus(PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(head);
        BizPurchaseRequestDetail batchLine = requestDetail(101L, 2, "钢板", 19);
        batchLine.setArriveBatchNo("B1");
        batchLine.setUnitPrice(new BigDecimal("50.00"));
        batchLine.setSupplierId(2L);
        BizPurchaseRequestDetail pendingLine = requestDetail(102L, 1, "螺丝", 1);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(batchLine, pendingLine));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(warehouseUser());

        service.confirmReceive(5L);

        // 进货单只含本批 1 行（数量19），不含未到货行
        ArgumentCaptor<PurchaseSaveDTO> receiptCap = ArgumentCaptor.forClass(PurchaseSaveDTO.class);
        verify(purchaseService).createInternal(receiptCap.capture(), anyLong(), any());
        assertEquals(1, receiptCap.getValue().getLines().size());
        assertEquals(19, receiptCap.getValue().getLines().get(0).getQuantity());
        // 头回 2（部分入库）+ 只按标题撤本批到货待办
        verify(messageService).revokeUnreadByBizAndTitles(
                eq("purchase_request"), eq(5L),
                eq(List.of(MessageService.TITLE_PURCHASE_REQUEST_ARRIVED)));
        verify(messageService, never()).revokeUnreadByBiz(any(), any());
    }

    private LoginResponse.UserInfoVO warehouseUser() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("仓储管理员");
        return user;
    }

    @Test
    void twoBatches_19plus1_eachBatchOneReceipt_andFinalHead3() {
        BizPurchaseRequest head = purchasingHead(5L);
        BizPurchaseRequestDetail line1 = requestDetail(101L, 1, "钢板", 19);
        BizPurchaseRequestDetail line2 = requestDetail(102L, 1, "螺丝", 1);
        List<BizPurchaseRequestDetail> store = new java.util.ArrayList<>(List.of(line1, line2));
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(head);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenAnswer(inv -> new ArrayList<>(store));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(warehouseUser());
        when(baseSupplierMapper.selectById(2L)).thenReturn(supplier(2L, 1));

        // ---- 第 1 批：勾选 101 到货并确认入库 ----
        PurchaseRequestReceiveDTO arrive1 = new PurchaseRequestReceiveDTO();
        arrive1.setItems(List.of(arriveItem(101L, "50.00")));
        service.arrive(5L, arrive1);
        line1.setReceiveStatus(2); line1.setArriveBatchNo("B1");
        line1.setUnitPrice(new BigDecimal("50.00")); line1.setSupplierId(2L); head.setStatus(5); // 模拟DB

        service.confirmReceive(5L);
        line1.setReceiveStatus(3); head.setStatus(2); // 部分入库回到采购中

        // ---- 第 2 批：剩余 102 到货并确认入库 ----
        PurchaseRequestReceiveDTO arrive2 = new PurchaseRequestReceiveDTO();
        arrive2.setItems(List.of(arriveItem(102L, "0.50")));
        service.arrive(5L, arrive2);
        line2.setReceiveStatus(2); line2.setArriveBatchNo("B2");
        line2.setUnitPrice(new BigDecimal("0.50")); line2.setSupplierId(2L); head.setStatus(5);

        service.confirmReceive(5L);
        line2.setReceiveStatus(3); head.setStatus(3);

        // 两批各一张进货单：第1张1行19个，第2张1行1个
        ArgumentCaptor<PurchaseSaveDTO> receipts = ArgumentCaptor.forClass(PurchaseSaveDTO.class);
        verify(purchaseService, times(2)).createInternal(receipts.capture(), anyLong(), any());
        List<PurchaseSaveDTO> all = receipts.getAllValues();
        assertEquals(1, all.get(0).getLines().size());
        assertEquals(19, all.get(0).getLines().get(0).getQuantity());
        assertTrue(all.get(0).getRemark().contains("B1"), all.get(0).getRemark()); // 批次信息写在备注
        assertEquals(1, all.get(1).getLines().size());
        assertEquals(1, all.get(1).getLines().get(0).getQuantity());
        assertTrue(all.get(1).getRemark().contains("B2"), all.get(1).getRemark());
        // 仅终态撤全部未读
        verify(messageService).revokeUnreadByBiz("purchase_request", 5L);
    }

    // ============================== D62：确认入库齐套通知 ==============================

    private BizPurchaseRequest awaitingConfirmRequest(String sourceType) {
        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setId(30L);
        request.setRequestNo("PR-D62-TEST");
        request.setStatus(PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        request.setSourceType(sourceType);
        if (PurchaseRequestService.SOURCE_PRODUCTION.equals(sourceType)) {
            request.setProductionOrderId(7L);
        }
        return request;
    }

    private BizPurchaseRequestDetail arrivedDetail() {
        BizPurchaseRequestDetail detail = new BizPurchaseRequestDetail();
        detail.setId(301L);
        detail.setRequestId(30L);
        detail.setGoodsId(51L);
        detail.setGoodsName("轴承珠");
        detail.setQuantity(10);
        detail.setArriveQuantity(10);
        detail.setUnitPrice(BigDecimal.ONE);
        detail.setSupplierId(2L);
        // D120：本批待入库确认
        detail.setReceiveStatus(PurchaseRequestService.RECEIVE_AWAITING);
        detail.setArriveBatchNo("B1");
        return detail;
    }

    private void stubConfirmReceive(BizPurchaseRequest request, BizPurchaseRequestDetail detail) {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(3L);
        user.setRealName("仓储管理员");
        when(authService.getUserInfo()).thenReturn(user);
        when(bizPurchaseRequestMapper.selectById(30L)).thenReturn(request);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(detail));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
    }

    @Test
    void confirmReceive_notifiesProductionAdminsWhenKitComplete() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PO-D62");
        order.setGoodsName("PTO153");
        order.setQuantity(5);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of());

        service.confirmReceive(30L);

        // D87：先按标题白名单撤齐套类旧通知，再发新（互斥不堆积）
        org.mockito.InOrder inOrder = Mockito.inOrder(messageService);
        inOrder.verify(messageService).revokeUnreadByBizAndTitles("production_order", 7L, MessageService.KIT_FAMILY_TITLES);
        inOrder.verify(messageService).sendKitCompleteToProductionAdmins("PO-D62", "PTO153", 5, "PR-D62-TEST", 7L);
    }

    // D87：仍缺料不再沉默——发「补料部分到货仍缺料」通知（与齐套通知互斥）
    @Test
    void confirmReceive_notifiesKitIncompleteWhenShortageRemains() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setOrderNo("PO-D62");
        order.setGoodsName("PTO153");
        order.setQuantity(5);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);
        KitShortageVO shortage = new KitShortageVO();
        shortage.setGoodsName("电阻10K");
        shortage.setDeficit(BigDecimal.valueOf(2));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(shortage));

        service.confirmReceive(30L);

        org.mockito.InOrder inOrder = Mockito.inOrder(messageService);
        inOrder.verify(messageService).revokeUnreadByBizAndTitles("production_order", 7L, MessageService.KIT_FAMILY_TITLES);
        inOrder.verify(messageService).sendKitIncompleteToProductionAdmins(
                eq("PO-D62"), eq("PTO153"), eq(5), eq("PR-D62-TEST"),
                ArgumentMatchers.argThat(s -> s != null && s.contains("电阻10K") && s.contains("2")), eq(7L));
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void confirmReceive_skipsNotifyForWarehouseSource() {
        BizPurchaseRequest request = awaitingConfirmRequest("warehouse");
        stubConfirmReceive(request, arrivedDetail());

        service.confirmReceive(30L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void confirmReceive_skipsNotifyWhenOrderVoided() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_VOIDED);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);

        service.confirmReceive(30L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void confirmReceive_skipsNotifyWhenOrderTerminated() {
        BizPurchaseRequest request = awaitingConfirmRequest(PurchaseRequestService.SOURCE_PRODUCTION);
        stubConfirmReceive(request, arrivedDetail());

        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        when(bizProductionOrderMapper.selectById(7L)).thenReturn(order);

        service.confirmReceive(30L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(anyString(), anyString(), any(), anyString(), anyLong());
    }

    @Test
    void getById_partialReceive_statusTextDerivesPartial() {
        // D120：「部分入库」由行状态派生（头仍是采购中），且必须在明细装载之后计算
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        BizPurchaseRequestDetail done = requestDetail(101L, PurchaseRequestService.RECEIVE_DONE, "钢板", 19);
        done.setArriveBatchNo("B1");
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(
                done, requestDetail(102L, PurchaseRequestService.RECEIVE_PENDING, "螺丝", 1)));

        PurchaseRequestVO vo = service.getById(5L);

        assertEquals("部分入库", vo.getStatusText());

        // 全部行未入库时不派生，仍是「采购中」
        when(bizPurchaseRequestMapper.selectById(6L)).thenReturn(purchasingHead(6L));
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(
                requestDetail(201L, PurchaseRequestService.RECEIVE_PENDING, "螺母", 2)));
        assertEquals("采购中", service.getById(6L).getStatusText());
    }

    @Test
    void confirmReceive_partial_productionSource_noKitNotify() {
        // D120/review：生产补料单部分入库未齐料——D86 齐料重算/通知必须不触发
        BizPurchaseRequest head = purchasingHead(5L);
        head.setStatus(PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        head.setSourceType(PurchaseRequestService.SOURCE_PRODUCTION);
        head.setProductionOrderId(7L);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(head);
        BizPurchaseRequestDetail batchLine = requestDetail(101L, 2, "钢板", 19);
        batchLine.setArriveBatchNo("B1");
        batchLine.setUnitPrice(new BigDecimal("50.00"));
        batchLine.setSupplierId(2L);
        BizPurchaseRequestDetail pendingLine = requestDetail(102L, 1, "螺丝", 1);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(batchLine, pendingLine));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(warehouseUser());

        service.confirmReceive(5L);

        verify(productionOrderService, never()).computeShortageForOrder(anyLong());
        verify(messageService, never()).sendKitCompleteToProductionAdmins(
                anyString(), anyString(), any(), anyString(), anyLong());
    }

    // ============================== D131：行级供应商 ==============================

    @Test
    void arrive_rejectsMissingSupplier() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 1, "钢板", 19)));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00", null)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("请选择供应商"), ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).update(any(), any());
    }

    @Test
    void arrive_rejectsDefaultSupplierAnchor() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 1, "钢板", 19)));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00", 1L)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("不能选择系统默认供应商"), ex.getMessage());
        verify(baseSupplierMapper, never()).selectById(any());
    }

    @Test
    void arrive_rejectsUnknownSupplier() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 1, "钢板", 19)));
        when(baseSupplierMapper.selectById(99L)).thenReturn(null);

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00", 99L)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("供应商不存在"), ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).update(any(), any());
    }

    @Test
    void arrive_rejectsStoppedSupplier() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        when(bizPurchaseRequestDetailMapper.selectList(any()))
                .thenReturn(List.of(requestDetail(101L, 1, "钢板", 19)));
        when(baseSupplierMapper.selectById(3L)).thenReturn(supplier(3L, 0));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00", 3L)));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.arrive(5L, dto));
        assertTrue(ex.getMessage().contains("供应商已停用"), ex.getMessage());
        verify(bizPurchaseRequestDetailMapper, never()).update(any(), any());
    }

    @Test
    void arrive_persistsLineSupplier_toDetailRow() {
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(purchasingHead(5L));
        BizPurchaseRequestDetail line1 = requestDetail(101L, 1, "钢板", 19);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(line1));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(purchaseUser());
        when(baseSupplierMapper.selectById(9L)).thenReturn(supplier(9L, 1));

        PurchaseRequestReceiveDTO dto = new PurchaseRequestReceiveDTO();
        dto.setItems(List.of(arriveItem(101L, "50.00", 9L)));
        service.arrive(5L, dto);

        // 行级供应商随 receiveStatus/批次一同回写到明细行
        @SuppressWarnings("unchecked")
        ArgumentCaptor<com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<BizPurchaseRequestDetail>> cap =
                ArgumentCaptor.forClass(com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper.class);
        verify(bizPurchaseRequestDetailMapper).update(isNull(), cap.capture());
        String sqlSet = String.valueOf(cap.getValue().getSqlSet());
        assertTrue(sqlSet.contains("supplierId="), sqlSet);
        assertTrue(cap.getValue().getParamNameValuePairs().containsValue(9L), "supplierId=9 应写入明细行: " + sqlSet);
    }

    @Test
    void confirmReceive_copiesLineSupplierToReceiptLines() {
        BizPurchaseRequest head = purchasingHead(5L);
        head.setStatus(PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        when(bizPurchaseRequestMapper.selectById(5L)).thenReturn(head);
        BizPurchaseRequestDetail batchLine = requestDetail(101L, 2, "钢板", 19);
        batchLine.setArriveBatchNo("B1");
        batchLine.setUnitPrice(new BigDecimal("50.00"));
        batchLine.setSupplierId(9L);
        when(bizPurchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(batchLine));
        when(bizPurchaseRequestMapper.update(any(), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(warehouseUser());

        service.confirmReceive(5L);

        // 行级供应商随行复制到进货明细（权威口径，ADR-0018）
        ArgumentCaptor<PurchaseSaveDTO> receiptCap = ArgumentCaptor.forClass(PurchaseSaveDTO.class);
        verify(purchaseService).createInternal(receiptCap.capture(), anyLong(), any());
        assertEquals(9L, receiptCap.getValue().getLines().get(0).getSupplierId());
    }

    @Test
    void receiveItemDTO_hasNoQuantityField_splitByConstruction() {
        // D120/spec：接收 DTO 不含数量字段——「行内拆量」在契约上不可表达，整行按申请量到货
        assertThrows(NoSuchFieldException.class,
                () -> PurchaseRequestReceiveDTO.ReceiveItemDTO.class.getDeclaredField("quantity"));
        // D131/spec：接收 DTO 必含行级供应商字段——「无供应商到货」在契约上不可表达
        assertDoesNotThrow(
                () -> PurchaseRequestReceiveDTO.ReceiveItemDTO.class.getDeclaredField("supplierId"));
    }
}
