package org.example.back.service;

import org.example.back.entity.BizPickList;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.DocumentTimelineNodeVO;
import org.example.back.vo.DocumentTimelineVO;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * D114：生产任务单全动线时间线——齐套直接生产 / 缺料补料分批到货 / 终止与驳回旁支 / 工序进度计数。
 */
@ExtendWith(MockitoExtension.class)
class ProductionOrderTimelineServiceTest {

    @Mock private AuthzService authzService;
    @Mock private BizProductionOrderMapper productionOrderMapper;
    @Mock private org.example.back.mapper.BizSalesMapper bizSalesMapper;
    @Mock private BizPurchaseRequestMapper purchaseRequestMapper;
    @Mock private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;
    @Mock private BizPickListMapper pickListMapper;
    @Mock private BizProductionMapper productionMapper;
    @Mock private ProductionStepService productionStepService;
    @Mock private QcService qcService;

    @InjectMocks private ProductionOrderTimelineService service;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"),
                BizProductionOrder.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"),
                BizPurchaseRequest.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"),
                BizPurchaseRequestDetail.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"),
                BizPickList.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new Configuration(), "test"),
                BizProduction.class);
    }

    // ---------- 构造工具 ----------

    private BizProductionOrder order(int status, String remark) {
        BizProductionOrder o = new BizProductionOrder();
        o.setId(88L);
        o.setOrderNo("PRO260921001");
        o.setGoodsName("PTO153");
        o.setQuantity(5);
        o.setStatus(status);
        o.setRemark(remark);
        o.setCreateTime(LocalDateTime.of(2026, 9, 20, 9, 0));
        o.setUpdateTime(LocalDateTime.of(2026, 9, 21, 10, 0));
        return o;
    }

    private ProductionStepVO step(String type, boolean done, int hour) {
        ProductionStepVO s = new ProductionStepVO();
        s.setType(type);
        s.setDone(done);
        if (done) {
            s.setOperateTime(LocalDateTime.of(2026, 9, 20, hour, 0));
        }
        return s;
    }

    private void mockSteps(int total, int manualDone) {
        List<ProductionStepVO> steps = new ArrayList<>();
        for (int i = 0; i < total; i++) {
            steps.add(step(i < 7 ? "manual" : "qc", i < manualDone, 10 + i));
        }
        when(productionStepService.listSteps(any())).thenReturn(steps);
    }

    /** 仿 QcService.buildStateFor 真实语义：未测点 passed=false/status=untested */
    private QcStateVO qcState(Boolean firstPassed, Boolean finalPassed) {
        QcStateVO qc = new QcStateVO();
        qc.setFirstPassed(firstPassed);
        qc.setFinalPassed(finalPassed);
        qc.setFirstStatus(firstPassed == null ? "untested" : (firstPassed ? "ok" : "ng"));
        qc.setFinalStatus(finalPassed == null ? "untested" : (finalPassed ? "ok" : "ng"));
        qc.setFirstStatusText(firstPassed == null ? "未测" : (firstPassed ? "合格" : "NG-待处置"));
        qc.setFinalStatusText(finalPassed == null ? "未测" : (finalPassed ? "合格" : "NG-待处置"));
        return qc;
    }

    private BizPurchaseRequest pr(int status, String applicant, String purchaser) {
        BizPurchaseRequest r = new BizPurchaseRequest();
        r.setId(66L);
        r.setRequestNo("PR260921001");
        r.setProductionOrderId(88L);
        r.setStatus(status);
        r.setApplicantName(applicant);
        r.setOperatorName(purchaser);
        r.setCreateTime(LocalDateTime.of(2026, 9, 20, 9, 30));
        r.setOperationTime(LocalDateTime.of(2026, 9, 20, 10, 0));
        return r;
    }

    private BizPurchaseRequestDetail prLine(String batchNo, Integer receiveStatus) {
        BizPurchaseRequestDetail d = new BizPurchaseRequestDetail();
        d.setRequestId(66L);
        d.setArriveBatchNo(batchNo);
        d.setReceiveStatus(receiveStatus);
        if (batchNo != null) {
            d.setArriveBatchTime(LocalDateTime.of(2026, 9, 20, 12, 0));
            if (receiveStatus != null && receiveStatus == PurchaseRequestService.RECEIVE_DONE) {
                d.setReceiveBatchTime(LocalDateTime.of(2026, 9, 20, 14, 0));
            }
        }
        return d;
    }

    private BizPickList pick(String pickType, int status, String applicant, String issuer) {
        BizPickList p = new BizPickList();
        p.setId(77L);
        p.setPickNo("PICK260921001");
        p.setProductionOrderId(88L);
        p.setPickType(pickType);
        p.setStatus(status);
        p.setApplicantName(applicant);
        p.setOperatorName(issuer);
        p.setOperationTime(LocalDateTime.of(2026, 9, 20, 15, 0));
        // 真实数据：驳回（4）虽 >= 已发料（2）但从未发料，confirmTime 为空
        p.setConfirmTime(status >= PickListService.STATUS_ISSUED && status != PickListService.STATUS_REJECTED
                ? LocalDateTime.of(2026, 9, 20, 16, 0) : null);
        p.setUpdateTime(LocalDateTime.of(2026, 9, 20, 17, 0));
        return p;
    }

    private BizProduction inbound(int confirmStatus, String confirmer) {
        BizProduction in = new BizProduction();
        in.setId(99L);
        in.setProductionNo("IN260921001");
        in.setProductionOrderId(88L);
        in.setBizStatus(1);
        in.setQuantity(5);
        in.setOperatorName("生产员");
        in.setOperationTime(LocalDateTime.of(2026, 9, 21, 9, 0));
        in.setConfirmStatus(confirmStatus);
        in.setConfirmerName(confirmer);
        in.setConfirmTime(confirmStatus == BizProduction.CONFIRM_CONFIRMED
                ? LocalDateTime.of(2026, 9, 21, 10, 0) : null);
        in.setUpdateTime(LocalDateTime.of(2026, 9, 21, 10, 0));
        return in;
    }

    private DocumentTimelineNodeVO byKey(DocumentTimelineVO timeline, String key) {
        return timeline.getNodes().stream().filter(n -> key.equals(n.getKey()))
                .findFirst().orElseThrow(() -> new AssertionError("缺少节点 " + key));
    }

    private boolean hasKey(DocumentTimelineVO timeline, String key) {
        return timeline.getNodes().stream().anyMatch(n -> key.equals(n.getKey()));
    }

    // ---------- 场景 A：齐套直接生产全链（无补料段） ----------

    @Test
    void kitCompleteOrder_fullChainWithoutSupplySegment() {
        when(productionOrderMapper.selectById(88L)).thenReturn(order(BizProductionOrder.STATUS_DONE, null));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pick(PickListService.TYPE_PICK, PickListService.STATUS_DONE, "张三", "李四")));
        mockSteps(10, 10);
        when(qcService.buildState(any())).thenReturn(qcState(true, true));
        when(productionMapper.selectList(any())).thenReturn(List.of(
                inbound(BizProduction.CONFIRM_CONFIRMED, "王五")));

        DocumentTimelineVO timeline = service.getTimeline(88L);

        assertEquals("PRO260921001", timeline.getDocNo());
        List<String> keys = timeline.getNodes().stream().map(DocumentTimelineNodeVO::getKey).toList();
        assertEquals(List.of("released", "pick_77", "pick_77_issue", "pick_77_receive",
                "started", "producing", "complete_report", "inbound_99", "inbound_99_confirm"), keys);

        DocumentTimelineNodeVO released = byKey(timeline, "released");
        assertEquals("done", released.getStatus());
        assertTrue(released.getDescription().contains("PRO260921001"));

        DocumentTimelineNodeVO receive = byKey(timeline, "pick_77_receive");
        assertEquals("done", receive.getStatus());
        assertTrue(receive.getDescription().contains("张三"));
        assertTrue(byKey(timeline, "pick_77_issue").getDescription().contains("李四"));

        DocumentTimelineNodeVO confirm = byKey(timeline, "inbound_99_confirm");
        assertTrue(confirm.getDescription().contains("王五"));
        assertTrue(confirm.getDescription().contains("+5"));
        assertFalse(hasKey(timeline, "supply_66"));      // 无补料段
        assertFalse(hasKey(timeline, "voided"));          // 无终态节点
    }

    // ---------- 场景 B：缺料→补料分批到货→领料→生产中（3/10）→待确认入库 ----------

    @Test
    void shortageWithBatchedSupply_andInProgressProgress() {
        when(productionOrderMapper.selectById(88L))
                .thenReturn(order(BizProductionOrder.STATUS_IN_PROGRESS, null));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of(
                pr(PurchaseRequestService.STATUS_PURCHASING, "生产员", "采购员")));
        // B1 批 2 行已入库 + 1 行未到货（无批次号）
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(
                prLine("B1", PurchaseRequestService.RECEIVE_DONE),
                prLine("B1", PurchaseRequestService.RECEIVE_DONE),
                prLine(null, PurchaseRequestService.RECEIVE_PENDING)));
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pick(PickListService.TYPE_PICK, PickListService.STATUS_DONE, "张三", "李四")));
        mockSteps(10, 3);
        when(qcService.buildState(any())).thenReturn(qcState(null, null)); // 未到质检
        when(productionMapper.selectList(any())).thenReturn(List.of());

        DocumentTimelineVO timeline = service.getTimeline(88L);

        // 补料段：申请 done → 认领 done → B1 到货/入库 done → 继续到货 current
        assertEquals("done", byKey(timeline, "supply_66").getStatus());
        assertEquals("done", byKey(timeline, "supply_66_claim").getStatus());
        assertEquals("done", byKey(timeline, "supply_66_B1_arrive").getStatus());
        assertEquals("done", byKey(timeline, "supply_66_B1_receive").getStatus());
        assertEquals("current", byKey(timeline, "supply_66_next").getStatus());
        assertTrue(byKey(timeline, "supply_66_B1_receive").getDescription().contains("多行进货单"));

        // 领料段：申请/发料/确认收货全 done（开工前提，真实链路顺序）
        assertEquals("done", byKey(timeline, "pick_77").getStatus());
        assertEquals("done", byKey(timeline, "pick_77_issue").getStatus());
        assertEquals("done", byKey(timeline, "pick_77_receive").getStatus());

        // 开工 done（取最早打卡时间）、生产中 current 3/10
        DocumentTimelineNodeVO started = byKey(timeline, "started");
        assertEquals("done", started.getStatus());
        assertEquals(LocalDateTime.of(2026, 9, 20, 10, 0), started.getTime());
        DocumentTimelineNodeVO producing = byKey(timeline, "producing");
        assertEquals("current", producing.getStatus());
        assertTrue(producing.getDescription().contains("3/10"));

        // 未完工未终态 → 完成节点 pending 占位
        assertEquals("pending", byKey(timeline, "inbound_confirm").getStatus());
        assertFalse(hasKey(timeline, "qc_rework"));
        assertFalse(hasKey(timeline, "voided"));
    }

    // ---------- 场景 C：终止单 + 补料驳回/领料驳回/入库驳回旁支 ----------

    @Test
    void terminatedOrder_withRejectionBranches() {
        when(productionOrderMapper.selectById(88L))
                .thenReturn(order(BizProductionOrder.STATUS_TERMINATED, "进度落后 | 终止原因: 客户取消订单"));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of(
                pr(PurchaseRequestService.STATUS_REJECTED, "生产员", null)));
        BizPickList rejected = pick(PickListService.TYPE_PICK, PickListService.STATUS_REJECTED, "张三", "李四");
        rejected.setRejectReason("物料规格不符");
        when(pickListMapper.selectList(any())).thenReturn(List.of(rejected));
        mockSteps(10, 3);
        QcStateVO ng = qcState(false, null);
        when(qcService.buildState(any())).thenReturn(ng);
        BizProduction rejectedInbound = inbound(BizProduction.CONFIRM_REJECTED, null);
        rejectedInbound.setRejectReason("数量与任务单不符");
        when(productionMapper.selectList(any())).thenReturn(List.of(rejectedInbound));

        DocumentTimelineVO timeline = service.getTimeline(88L);

        // 终态红节点带原因（remark 留痕解析）
        DocumentTimelineNodeVO voided = byKey(timeline, "voided");
        assertEquals("danger", voided.getStatus());
        assertEquals("已终止", voided.getTitle());
        assertEquals("原因：客户取消订单", voided.getDescription());

        // 补料申请驳回旁支
        assertEquals("danger", byKey(timeline, "supply_66_reject").getStatus());
        // 领料驳回：申请节点 danger + 驳回旁支节点，且无确认收货节点
        assertEquals("danger", byKey(timeline, "pick_77").getStatus());
        assertEquals("pending", byKey(timeline, "pick_77_issue").getStatus());
        assertTrue(byKey(timeline, "pick_77_reject").getDescription().contains("物料规格不符"));
        assertFalse(hasKey(timeline, "pick_77_receive"));

        // 质检 NG → 返工旁支
        assertEquals("danger", byKey(timeline, "qc_rework").getStatus());

        // 入库申请驳回旁支带原因
        assertTrue(byKey(timeline, "inbound_99").getDescription().contains("数量与任务单不符"));

        // 终止单未走完生产：生产中节点不显示「全部工序完成」
        assertFalse(byKey(timeline, "producing").getDescription().contains("全部"));
        assertFalse(hasKey(timeline, "inbound_confirm")); // 终态无 pending 占位
    }

    // ---------- 场景 D：待入库但入库申请被驳回后未重提 → 当前位置提示 ----------

    @Test
    void awaitingQcAfterReject_showsResubmitCurrentNode() {
        when(productionOrderMapper.selectById(88L)).thenReturn(order(BizProductionOrder.STATUS_AWAIT_QC, null));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectList(any())).thenReturn(List.of());
        mockSteps(10, 10);
        when(qcService.buildState(any())).thenReturn(qcState(true, true));
        BizProduction rejectedInbound = inbound(BizProduction.CONFIRM_REJECTED, null);
        rejectedInbound.setRejectReason("包装破损");
        when(productionMapper.selectList(any())).thenReturn(List.of(rejectedInbound));

        DocumentTimelineVO timeline = service.getTimeline(88L);

        assertTrue(byKey(timeline, "inbound_99").getDescription().contains("包装破损"));
        DocumentTimelineNodeVO resubmit = byKey(timeline, "inbound_next");
        assertEquals("current", resubmit.getStatus()); // 待入库且无待确认申请 → 当前位置=重新提交
        assertEquals("done", byKey(timeline, "producing").getStatus());
        assertTrue(byKey(timeline, "producing").getDescription().contains("全部 10 道工序完成"));
    }

    // ---------- 场景 E：缺料→补料分批→领料→打卡→两段式入库 全链（规格要求的完整链路） ----------

    @Test
    void fullChain_shortageToTwoPhaseInbound() {
        when(productionOrderMapper.selectById(88L)).thenReturn(order(BizProductionOrder.STATUS_AWAIT_QC, null));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of(
                pr(PurchaseRequestService.STATUS_AWAITING_CONFIRM, "生产员", "采购员")));
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(List.of(
                prLine("B1", PurchaseRequestService.RECEIVE_DONE)));
        when(pickListMapper.selectList(any())).thenReturn(List.of(
                pick(PickListService.TYPE_PICK, PickListService.STATUS_DONE, "张三", "李四")));
        mockSteps(10, 10);
        when(qcService.buildState(any())).thenReturn(qcState(true, true));
        when(productionMapper.selectList(any())).thenReturn(List.of(
                inbound(BizProduction.CONFIRM_PENDING, "生产员")));

        DocumentTimelineVO timeline = service.getTimeline(88L);

        assertEquals(List.of("released", "supply_66", "supply_66_claim", "supply_66_B1_arrive",
                "supply_66_B1_receive", "pick_77", "pick_77_issue", "pick_77_receive",
                "started", "producing", "complete_report", "inbound_99"),
                timeline.getNodes().stream().map(DocumentTimelineNodeVO::getKey).toList());
        // 两段式第一段：入库申请为当前节点，待仓储确认
        assertEquals("current", byKey(timeline, "inbound_99").getStatus());
        assertTrue(byKey(timeline, "inbound_99").getDescription().contains("待仓储管理员确认"));
        assertEquals("done", byKey(timeline, "supply_66_B1_receive").getStatus());
        assertEquals("done", byKey(timeline, "pick_77_receive").getStatus());
        assertEquals("done", byKey(timeline, "producing").getStatus());
        assertEquals("done", byKey(timeline, "complete_report").getStatus());
    }

    // ---------- 场景 F：报废单终态原因取质检 NG 记录 ----------

    @Test
    void scrappedOrder_terminalReasonFromLatestNgRecord() {
        when(productionOrderMapper.selectById(88L)).thenReturn(order(BizProductionOrder.STATUS_SCRAPPED, null));
        when(purchaseRequestMapper.selectList(any())).thenReturn(List.of());
        when(pickListMapper.selectList(any())).thenReturn(List.of());
        mockSteps(10, 5);
        QcStateVO scrapped = qcState(false, null);
        scrapped.setFirstStatus("scrap"); // 报废处置后的测点状态，不算返工旁支
        scrapped.setFirstStatusText("已报废");
        when(qcService.buildState(any())).thenReturn(scrapped);
        when(qcService.latestNgReason(88L)).thenReturn("尺寸超差");
        when(productionMapper.selectList(any())).thenReturn(List.of());

        DocumentTimelineVO timeline = service.getTimeline(88L);

        DocumentTimelineNodeVO voided = byKey(timeline, "voided");
        assertEquals("已报废", voided.getTitle());
        assertEquals("原因：尺寸超差", voided.getDescription());
        assertFalse(hasKey(timeline, "qc_rework")); // 报废测点不出返工旁支，终态节点已呈现
    }
}
