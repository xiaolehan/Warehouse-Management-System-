package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.DocumentTimelineNodeVO;
import org.example.back.vo.DocumentTimelineVO;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 生产任务单全动线时间线（D114）：下达 → 补料（认领→按批到货→入库，D120 语义）→ 领料（申请→发料→确认收货）→
 * 开工 → 生产中（X/N 道工序）→ 完工报工 → 入库申请（驳回旁支带原因，D107 两段式）→ 仓储确认入库·完成。
 * 与单据时间线（DocumentTimelineService）同模式——按单据字段合成，不读操作日志；
 * 有则显、无则隐（无补料申请不出补料段）；进行中 current、已完成 done、未到 pending、终态/驳回 danger（红节点）。
 * 人名有则用人名，角色锁定的步骤（下达/发料/确认入库）用角色名呈现（D104 口径）。
 */
@Service
public class ProductionOrderTimelineService {

    @Autowired
    private AuthzService authzService;
    @Autowired
    private BizProductionOrderMapper productionOrderMapper;
    @Autowired
    private BizSalesMapper bizSalesMapper;
    @Autowired
    private BizPurchaseRequestMapper purchaseRequestMapper;
    @Autowired
    private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;
    @Autowired
    private BizPickListMapper pickListMapper;
    @Autowired
    private BizProductionMapper productionMapper;
    @Autowired
    private ProductionStepService productionStepService;
    @Autowired
    private QcService qcService;

    public DocumentTimelineVO getTimeline(Long id) {
        // 与任务单详情同读权限口径（getById：生产研发部成员 + 超管）
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可查看生产任务单时间线", AuthzService.DEPT_PRODUCTION);
        BizProductionOrder order = productionOrderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        int status = order.getStatus() == null ? 0 : order.getStatus();
        boolean finished = status == BizProductionOrder.STATUS_DONE;
        boolean terminal = !finished && !BizProductionOrder.UNFINISHED_STATUSES.contains(status);

        List<ProductionStepVO> steps = productionStepService.listSteps(order);
        long stepDone = steps == null ? 0 : steps.stream().filter(s -> Boolean.TRUE.equals(s.getDone())).count();
        int stepTotal = steps == null ? 0 : steps.size();

        List<DocumentTimelineNodeVO> nodes = new ArrayList<>();
        buildReleasedNode(nodes, order);
        buildSupplySegment(nodes, order);
        buildPickSegment(nodes, order);
        buildProduceSegment(nodes, order, status, steps, stepDone, stepTotal);
        buildInboundSegment(nodes, order, status, finished, terminal);
        appendTerminalNode(nodes, order, status, terminal);
        return vo(order.getOrderNo(), nodes);
    }

    // ============================== 1 下达 ==============================

    private void buildReleasedNode(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order) {
        String desc = "生产管理员下达生产任务单 " + order.getOrderNo() + "（" + order.getQuantity() + " 件）";
        if (order.getSalesOrderId() != null) {
            BizSales sales = bizSalesMapper.selectById(order.getSalesOrderId());
            desc += sales == null ? "，关联销售单已删除" : "，关联销售单 " + sales.getSalesNo();
        }
        nodes.add(node("released", "下达", "done", order.getCreateTime(), desc));
    }

    // ============================== 2 补料段（生产补料采购申请，含 D120 按批到货） ==============================

    private void buildSupplySegment(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order) {
        List<BizPurchaseRequest> requests = purchaseRequestMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseRequest>()
                        .eq(BizPurchaseRequest::getProductionOrderId, order.getId())
                        .orderByAsc(BizPurchaseRequest::getId));
        if (requests.isEmpty()) {
            return; // 齐套直接生产：不出补料段
        }
        boolean multiRequest = requests.size() > 1;
        for (BizPurchaseRequest request : requests) {
            appendSupplyNodes(nodes, request, multiRequest);
        }
    }

    private void appendSupplyNodes(List<DocumentTimelineNodeVO> nodes, BizPurchaseRequest request, boolean multiRequest) {
        String prNo = request.getRequestNo();
        int st = request.getStatus() == null ? 0 : request.getStatus();
        boolean rejected = st == PurchaseRequestService.STATUS_REJECTED;
        nodes.add(node("supply_" + request.getId(), "补料申请（" + prNo + "）", "done", request.getCreateTime(),
                "申请人 " + safeName(request.getApplicantName()) + " 提交生产补料采购申请"));

        boolean claimed = st == PurchaseRequestService.STATUS_PURCHASING
                || st == PurchaseRequestService.STATUS_RECEIVED
                || st == PurchaseRequestService.STATUS_AWAITING_CONFIRM;
        if (!rejected) { // 驳回单从未进入认领环节，不出认领节点（有则显无则隐）
            nodes.add(node("supply_" + request.getId() + "_claim", "补料采购认领",
                    claimed ? "done" : "current",
                    claimed ? request.getOperationTime() : null,
                    request.getOperatorName() == null ? "采购认领后进入采购中"
                            : "采购管理员 " + request.getOperatorName() + " 认领，进入采购中"));
        }

        // D120：到货/入库按批重复，批信息取明细行（同批行状态整批一致，取首行代表整批）
        List<BizPurchaseRequestDetail> batchLines = purchaseRequestDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseRequestDetail>()
                        .eq(BizPurchaseRequestDetail::getRequestId, request.getId())
                        .isNotNull(BizPurchaseRequestDetail::getArriveBatchNo));
        Map<String, List<BizPurchaseRequestDetail>> batches = batchLines.stream()
                .filter(line -> line.getArriveBatchNo() != null) // SQL 已 isNotNull，此处防 groupingBy 空键
                .collect(Collectors.groupingBy(BizPurchaseRequestDetail::getArriveBatchNo,
                        LinkedHashMap::new, Collectors.toList()));
        List<String> orderedBatches = batches.keySet().stream()
                .sorted(Comparator.comparing((String b) -> batches.get(b).get(0).getArriveBatchTime(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(ProductionOrderTimelineService::batchSeq))
                .toList();
        for (String batchNo : orderedBatches) {
            BizPurchaseRequestDetail first = batches.get(batchNo).get(0);
            int lineStatus = first.getReceiveStatus() == null ? 0 : first.getReceiveStatus();
            String batchSuffix = multiRequest ? "（" + prNo + "）" : "";
            nodes.add(node("supply_" + request.getId() + "_" + batchNo + "_arrive", "第 " + batchNo + " 批到货" + batchSuffix,
                    lineStatus == PurchaseRequestService.RECEIVE_AWAITING ? "current" : "done",
                    first.getArriveBatchTime(),
                    "本批 " + batches.get(batchNo).size() + " 行，待仓储确认入库"));
            nodes.add(node("supply_" + request.getId() + "_" + batchNo + "_receive",
                    "第 " + batchNo + " 批入库确认" + batchSuffix,
                    lineStatus == PurchaseRequestService.RECEIVE_DONE ? "done"
                            : (lineStatus == PurchaseRequestService.RECEIVE_AWAITING ? "current" : "pending"),
                    first.getReceiveBatchTime(),
                    lineStatus == PurchaseRequestService.RECEIVE_DONE
                            ? "仓储管理员 " + safeName(request.getConfirmerName()) + " 确认入库，生成一张多行进货单"
                            : "仓储确认后本批生成一张多行进货单"));
        }

        if (!rejected && (st == PurchaseRequestService.STATUS_PENDING || st == PurchaseRequestService.STATUS_PURCHASING)) {
            boolean partial = !orderedBatches.isEmpty();
            nodes.add(node("supply_" + request.getId() + "_next",
                    partial ? "补料继续到货（剩余未到货行）" : "补料采购到货",
                    st == PurchaseRequestService.STATUS_PURCHASING ? "current" : "pending",
                    null, "剩余行到齐后提交下一批"));
        }
        if (rejected) {
            nodes.add(node("supply_" + request.getId() + "_reject", "补料申请被驳回", "danger", request.getUpdateTime(),
                    "驳回原因：" + (request.getRejectReason() == null ? "—" : request.getRejectReason())));
        }
    }

    // ============================== 3 领料段（领料/补料领料出库单） ==============================

    private void buildPickSegment(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order) {
        List<BizPickList> picks = pickListMapper.selectList(new LambdaQueryWrapper<BizPickList>()
                .eq(BizPickList::getProductionOrderId, order.getId())
                .in(BizPickList::getPickType, PickListService.TYPE_PICK, PickListService.TYPE_SUPPLY)
                .orderByAsc(BizPickList::getId));
        for (BizPickList pick : picks) {
            String label = PickListService.TYPE_SUPPLY.equals(pick.getPickType()) ? "补料领料" : "领料";
            int st = pick.getStatus() == null ? 0 : pick.getStatus();
            // 注意 STATUS_REJECTED=4 > STATUS_ISSUED=2，驳回不能按 >= 误判为已发料
            boolean rejectedPick = st == PickListService.STATUS_REJECTED;
            nodes.add(node("pick_" + pick.getId(), label + "申请（" + pick.getPickNo() + "）",
                    rejectedPick ? "danger" : "done", pick.getOperationTime(),
                    rejectedPick
                            ? "申请人 " + safeName(pick.getApplicantName()) + " 提交的领料申请被仓储驳回"
                            : "申请人 " + safeName(pick.getApplicantName()) + " 提交" + label + "申请"));
            nodes.add(node("pick_" + pick.getId() + "_issue", "仓储发料",
                    !rejectedPick && st >= PickListService.STATUS_ISSUED ? "done" : "pending",
                    !rejectedPick && st >= PickListService.STATUS_ISSUED ? pick.getConfirmTime() : null,
                    st >= PickListService.STATUS_ISSUED
                            ? "仓储管理员 " + safeName(pick.getOperatorName()) + " 确认出库发料"
                            : "待仓储确认出库"));
            if (rejectedPick) {
                nodes.add(node("pick_" + pick.getId() + "_reject", "领料申请被驳回", "danger", pick.getUpdateTime(),
                        "驳回原因：" + (pick.getRejectReason() == null ? "—" : pick.getRejectReason())));
                continue; // 驳回单无后续收货
            }
            nodes.add(node("pick_" + pick.getId() + "_receive", "生产确认收货",
                    st == PickListService.STATUS_DONE ? "done" : (st == PickListService.STATUS_ISSUED ? "current" : "pending"),
                    st == PickListService.STATUS_DONE ? pick.getUpdateTime() : null,
                    st == PickListService.STATUS_DONE
                            ? "申请人 " + safeName(pick.getApplicantName()) + " 确认收货，可开工"
                            : "发料后由申请人确认收货"));
        }
    }

    // ============================== 4 开工 / 生产中 / 完工报工 ==============================

    private void buildProduceSegment(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order,
                                     int status, List<ProductionStepVO> steps, long stepDone, int stepTotal) {
        boolean started = status >= BizProductionOrder.STATUS_IN_PROGRESS;
        LocalDateTime firstPunch = earliestPunch(steps);
        LocalDateTime lastPunch = latestPunch(steps);

        nodes.add(node("started", "开工",
                started ? "done" : "pending",
                started ? firstPunch : null,
                started ? null : "待领料出库后开工"));

        String producingDesc = "已完成 " + stepDone + "/" + stepTotal + " 道工序";
        boolean produceFinished = status == BizProductionOrder.STATUS_AWAIT_QC || status == BizProductionOrder.STATUS_DONE;
        if (produceFinished) {
            nodes.add(node("producing", "生产中", "done", lastPunch, "全部 " + stepTotal + " 道工序完成"));
        } else if (status == BizProductionOrder.STATUS_IN_PROGRESS) {
            nodes.add(node("producing", "生产中", "current", null, producingDesc));
        } else {
            nodes.add(node("producing", "生产中", "pending", null, producingDesc));
        }

        // 质检 NG → 返工旁支（仅真实 NG 记录；未测不算——buildState 对未测点返回 passed=false/status=untested）
        QcStateVO qc = qcService.buildState(order);
        if (isNgPoint(qc.getFirstStatus()) || isNgPoint(qc.getFinalStatus())) {
            String qcDesc = "首测 " + safe(qc.getFirstStatusText()) + " / 成品测 " + safe(qc.getFinalStatusText()) + "，返工后重测";
            nodes.add(node("qc_rework", "质检不合格 → 返工", "danger", lastPunch, qcDesc));
        }

        nodes.add(node("complete_report", "完工报工",
                produceFinished ? "done" : "pending",
                produceFinished ? order.getUpdateTime() : null,
                produceFinished ? "全部工序完成，进入成品入库环节" : null));
    }

    // ============================== 5 入库申请段（D107 两段式） ==============================

    private void buildInboundSegment(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order,
                                     int status, boolean finished, boolean terminal) {
        List<BizProduction> inbounds = productionMapper.selectList(new LambdaQueryWrapper<BizProduction>()
                .eq(BizProduction::getProductionOrderId, order.getId())
                .eq(BizProduction::getBizStatus, 1)
                .orderByAsc(BizProduction::getId));
        for (BizProduction inbound : inbounds) {
            int cs = inbound.getConfirmStatus() == null ? 0 : inbound.getConfirmStatus();
            if (cs == BizProduction.CONFIRM_PENDING) {
                nodes.add(node("inbound_" + inbound.getId(), "提交入库申请（" + inbound.getProductionNo() + "）",
                        "current", inbound.getOperationTime(),
                        "申请人 " + safeName(inbound.getOperatorName()) + " 提交成品入库申请，待仓储管理员确认"));
            } else if (cs == BizProduction.CONFIRM_CONFIRMED) {
                nodes.add(node("inbound_" + inbound.getId(), "提交入库申请（" + inbound.getProductionNo() + "）",
                        "done", inbound.getOperationTime(),
                        "申请人 " + safeName(inbound.getOperatorName()) + " 提交成品入库申请"));
                nodes.add(node("inbound_" + inbound.getId() + "_confirm", "仓储确认入库·完成", "done",
                        inbound.getConfirmTime(),
                        "仓储管理员 " + safeName(inbound.getConfirmerName()) + " 确认入库 +" + inbound.getQuantity()
                                + " 件，任务单完成"));
            } else {
                nodes.add(node("inbound_" + inbound.getId(), "入库申请被驳回（" + inbound.getProductionNo() + "）",
                        "danger", inbound.getUpdateTime(),
                        "驳回原因：" + (inbound.getRejectReason() == null ? "—" : inbound.getRejectReason())
                                + "，请核对后重新提交"));
            }
        }
        // 待入库但暂无待确认申请（驳回后未重提）：补「提交入库申请」当前位置节点
        if (status == BizProductionOrder.STATUS_AWAIT_QC && inbounds.stream().noneMatch(i ->
                i.getConfirmStatus() != null && i.getConfirmStatus() == BizProduction.CONFIRM_PENDING)) {
            nodes.add(node("inbound_next", "提交入库申请", "current", null, "质检通过后提交成品入库申请"));
        }
        // 未到入库环节：补 pending 的完成节点占位（待入库时由上方申请/确认节点呈现当前位置）
        if (!finished && status != BizProductionOrder.STATUS_AWAIT_QC && !terminal) {
            nodes.add(node("inbound_confirm", "仓储确认入库·完成", "pending", null, null));
        }
    }

    // ============================== 6 终态（作废/报废/终止） ==============================

    private void appendTerminalNode(List<DocumentTimelineNodeVO> nodes, BizProductionOrder order,
                                    int status, boolean terminal) {
        if (!terminal) {
            return;
        }
        String title = switch (status) {
            case BizProductionOrder.STATUS_VOIDED -> "已作废";
            case BizProductionOrder.STATUS_SCRAPPED -> "已报废";
            case BizProductionOrder.STATUS_TERMINATED -> "已终止";
            default -> "已终态";
        };
        String reason = extractRemarkReason(order.getRemark());
        if (reason == null && status == BizProductionOrder.STATUS_SCRAPPED) {
            // 报废处置无独立原因留痕，取最近一条质检 NG 记录的不合格原因
            reason = qcService.latestNgReason(order.getId());
        }
        nodes.add(node("voided", title, "danger", order.getUpdateTime(),
                reason == null ? "单据终态留痕" : "原因：" + reason));
    }

    // ============================== 私有工具 ==============================

    /** 作废/终止原因取自 remark 留痕（写入方用 BizProductionOrder.REMARK_*_REASON_MARKER 标记，取最后一个标记的值） */
    private String extractRemarkReason(String remark) {
        if (remark == null || remark.isBlank()) {
            return null;
        }
        String reason = null;
        for (String marker : new String[]{BizProductionOrder.REMARK_VOID_REASON_MARKER,
                BizProductionOrder.REMARK_TERMINATE_REASON_MARKER}) {
            int idx = remark.lastIndexOf(marker);
            if (idx >= 0) {
                String tail = remark.substring(idx + marker.length()).trim();
                int end = tail.indexOf("|");
                reason = end >= 0 ? tail.substring(0, end).trim() : tail;
            }
        }
        return (reason == null || reason.isEmpty()) ? null : reason;
    }

    /** 开工时刻取最早的人工工序打卡（ qc 打卡不计入——开工只看动手时间） */
    private LocalDateTime earliestPunch(List<ProductionStepVO> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(s -> "manual".equals(s.getType()) && Boolean.TRUE.equals(s.getDone()))
                .map(ProductionStepVO::getOperateTime)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 完工/最近活动时刻取全部类型（含 qc）打卡的最大值——报工关注最后一次作业 */
    private LocalDateTime latestPunch(List<ProductionStepVO> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .filter(s -> Boolean.TRUE.equals(s.getDone()))
                .map(ProductionStepVO::getOperateTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** 批次号 B 后的数值序（解析失败按 0），同毫秒批次稳定排序 */
    private static int batchSeq(String batchNo) {
        try {
            return Integer.parseInt(batchNo.substring(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 0;
        }
    }

    private String safeName(String name) {
        return name == null || name.isBlank() ? "管理员" : name;
    }

    private String safe(String text) {
        return text == null ? "未测" : text;
    }

    /** 质检测点 NG：未处置（ng）或返工处置中（rework）；untested/ok 不算，scrap 由终态节点呈现 */
    private boolean isNgPoint(String qcStatus) {
        return "ng".equals(qcStatus) || "rework".equals(qcStatus);
    }

    private DocumentTimelineNodeVO node(String key, String title, String status,
                                        LocalDateTime time, String description) {
        DocumentTimelineNodeVO vo = new DocumentTimelineNodeVO();
        vo.setKey(key);
        vo.setTitle(title);
        vo.setStatus(status);
        vo.setTime(time);
        vo.setDescription(description);
        return vo;
    }

    private DocumentTimelineVO vo(String docNo, List<DocumentTimelineNodeVO> nodes) {
        DocumentTimelineVO vo = new DocumentTimelineVO();
        vo.setDocNo(docNo);
        vo.setNodes(nodes);
        return vo;
    }
}
