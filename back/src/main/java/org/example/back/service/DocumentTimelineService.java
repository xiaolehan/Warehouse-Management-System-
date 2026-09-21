package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizPurchaseReturn;
import org.example.back.entity.BizSalesReturn;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.example.back.vo.DocumentTimelineNodeVO;
import org.example.back.vo.DocumentTimelineVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 单据流程时间线（D104）：进货单/采购申请单/进货退货单/销售退货单四类跨部门单据的
 * 「谁在哪一步做了什么」视图。与销售履约时间线（SalesTimelineService）同模式——
 * 按单据字段合成标准流程节点，不读操作日志；操作人有人名字段用人名，
 * 角色锁定的步骤（到货/驳回）用部门名呈现；回退后仅显示当前所处步骤。
 */
@Service
public class DocumentTimelineService {

    /** 审批单状态：待审批 / 处理中（D98 冻结口径） */
    private static final int APPROVAL_PENDING = 1;
    private static final int APPROVAL_PROCESSING = 4;

    @Autowired
    private AuthzService authzService;
    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;
    @Autowired
    private BizPurchaseRequestMapper bizPurchaseRequestMapper;
    @Autowired
    private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;
    @Autowired
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;
    @Autowired
    private BizSalesReturnMapper bizSalesReturnMapper;
    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    // ============================== 进货单：创建(采购) → 到货(采购) → 入库确认(仓储) ==============================

    public DocumentTimelineVO getPurchaseTimeline(Long id) {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅采购/仓储部门可查看进货时间线", AuthzService.DEPT_PURCHASE, AuthzService.DEPT_WAREHOUSE);
        BizPurchase p = bizPurchaseMapper.selectById(id);
        if (p == null) {
            throw BusinessException.notFound("进货单不存在");
        }
        boolean voided = isVoided(p.getBizStatus());
        Integer cs = p.getConfirmStatus();
        List<DocumentTimelineNodeVO> nodes = new ArrayList<>();
        nodes.add(node("created", "创建进货单", "done", p.getOperationTime(),
                "采购管理员 " + p.getOperatorName() + " 创建进货单 " + p.getPurchaseNo()));
        nodes.add(node("arrived", "采购到货", statusOf(voided, cs, 2, 1),
                p.getArriveTime(), "采购部确认到货，待仓储入库"));
        nodes.add(node("received", "仓储入库确认", statusOf(voided, cs, 3, 2),
                p.getConfirmTime(), confirmedDesc(p.getConfirmerName(), "确认入库，库存共 +" + p.getTotalQuantity())));
        appendVoidNodes(nodes, "purchase", id, voided, p.getVoidTime(), p.getVoidReason());
        return vo(p.getPurchaseNo(), nodes);
    }

    // ============================== 采购申请单：提交申请 → 采购认领 → 到货 → 入库确认；驳回为旁支终态 ==============================

    public DocumentTimelineVO getPurchaseRequestTimeline(Long id) {
        authzService.requireAnyDeptAdminOrSuperAdmin(
                "仅仓储/采购管理员可查看采购申请时间线", AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PURCHASE);
        BizPurchaseRequest r = bizPurchaseRequestMapper.selectById(id);
        if (r == null) {
            throw BusinessException.notFound("采购申请单不存在");
        }
        int st = r.getStatus() == null ? 0 : r.getStatus();
        boolean rejected = st == PurchaseRequestService.STATUS_REJECTED;
        List<DocumentTimelineNodeVO> nodes = new ArrayList<>();
        String source = PurchaseRequestService.SOURCE_PRODUCTION.equals(r.getSourceType())
                ? "生产补料申请（申请人 " + r.getApplicantName() + "）"
                : "仓储 " + r.getApplicantName() + " 发起采购申请";
        nodes.add(node("submitted", "提交申请", "done", r.getCreateTime(),
                source + " " + r.getRequestNo()));
        boolean claimed = st >= PurchaseRequestService.STATUS_PURCHASING && st <= PurchaseRequestService.STATUS_RECEIVED;
        nodes.add(node("claimed", "采购认领",
                claimed ? "done" : (st == PurchaseRequestService.STATUS_PENDING ? "current" : "pending"),
                claimed ? r.getOperationTime() : null,
                r.getOperatorName() == null ? "采购认领后进入采购中"
                        : "采购管理员 " + r.getOperatorName() + " 认领，进入采购中"));

        // D120：到货/入库按批重复（第 N 批到货 → 第 N 批入库确认），批信息取明细行
        List<BizPurchaseRequestDetail> details = bizPurchaseRequestDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseRequestDetail>()
                        .eq(BizPurchaseRequestDetail::getRequestId, id)
                        .isNotNull(BizPurchaseRequestDetail::getArriveBatchNo));
        Map<String, List<BizPurchaseRequestDetail>> batches = details.stream()
                .collect(Collectors.groupingBy(BizPurchaseRequestDetail::getArriveBatchNo, LinkedHashMap::new, Collectors.toList()));
        List<String> orderedBatches = batches.keySet().stream()
                .sorted(Comparator.comparing((String b) -> batches.get(b).get(0).getArriveBatchTime(),
                        Comparator.nullsLast(Comparator.naturalOrder()))
                        // 同毫秒提交的兜底：按批次号数值比较（B2 < B10），不用字典序
                        .thenComparing(b -> batchSeq(b)))
                .toList();
        for (String batchNo : orderedBatches) {
            List<BizPurchaseRequestDetail> batchLines = batches.get(batchNo);
            BizPurchaseRequestDetail first = batchLines.get(0);
            // D120 前提：同批行状态始终整批一致迁移（确认/驳回/撤回都按批整组处理），
            // 故节点状态取批内首行即可代表整批
            int lineStatus = first.getReceiveStatus() == null ? 0 : first.getReceiveStatus();
            String goodsSummary = batchLines.stream()
                    .map(d -> (d.getGoodsName() == null ? "-" : d.getGoodsName()) + "×" + d.getQuantity())
                    .collect(Collectors.joining("、"));
            // 到货节点：本批待确认→current；已入库→done
            nodes.add(node("arrived_" + batchNo, "第 " + batchNo + " 批到货",
                    lineStatus == PurchaseRequestService.RECEIVE_AWAITING ? "current" : "done",
                    first.getArriveBatchTime(),
                    "本批 " + batchLines.size() + " 行：" + goodsSummary + "，待仓储确认入库"));
            // 入库节点：本批待确认→current；已入库→done
            nodes.add(node("received_" + batchNo, "第 " + batchNo + " 批入库确认",
                    lineStatus == PurchaseRequestService.RECEIVE_AWAITING ? "current"
                            : (lineStatus == PurchaseRequestService.RECEIVE_DONE ? "done" : "pending"),
                    first.getReceiveBatchTime(),
                    lineStatus == PurchaseRequestService.RECEIVE_DONE
                            ? confirmedDesc(r.getConfirmerName(),
                                    "确认入库，本批生成一张多行进货单（" + batchLines.size() + " 行）")
                            : "仓储确认后本批生成一张多行进货单"));
        }

        // 未全部入库：补一个「继续到货」节点指示当前所处位置（待采购时为 pending，采购中/部分入库为 current；
        // 待入库确认 5 时本批节点已在上方以 current 呈现，不重复追加）
        if ((st == PurchaseRequestService.STATUS_PURCHASING || st == PurchaseRequestService.STATUS_PENDING) && !rejected) {
            long remaining = 0;
            if (st == PurchaseRequestService.STATUS_PURCHASING) {
                // 批次明细外的未到货行
                remaining = bizPurchaseRequestDetailMapper.selectCount(
                        new LambdaQueryWrapper<BizPurchaseRequestDetail>()
                                .eq(BizPurchaseRequestDetail::getRequestId, id)
                                .ne(BizPurchaseRequestDetail::getReceiveStatus, PurchaseRequestService.RECEIVE_DONE));
            }
            boolean partial = !orderedBatches.isEmpty();
            nodes.add(node("arrived_next", partial ? "继续到货（剩余 " + remaining + " 行）" : "采购到货",
                    st == PurchaseRequestService.STATUS_PURCHASING ? "current" : "pending",
                    null, partial ? "勾选剩余未到货行提交下一批" : "采购勾选到货行后提交"));
            nodes.add(node("received_next", "仓储入库确认", "pending",
                    null, "每批确认入库生成一张多行进货单"));
        }

        if (rejected) {
            // 驳回仅发生在待采购状态（申请人重新调整后重提），节点时间取 updateTime 近似
            nodes.add(node("rejected", "已驳回", "done", r.getUpdateTime(),
                "驳回原因：" + (r.getRejectReason() == null ? "—" : r.getRejectReason())));
        }
        return vo(r.getRequestNo(), nodes);
    }

    // ============================== 进货退货单：创建(采购) → 出库确认(仓储) → 退货完成(采购) ==============================

    public DocumentTimelineVO getPurchaseReturnTimeline(Long id) {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅采购/仓储部门可查看退货时间线", AuthzService.DEPT_PURCHASE, AuthzService.DEPT_WAREHOUSE);
        BizPurchaseReturn r = bizPurchaseReturnMapper.selectById(id);
        if (r == null) {
            throw BusinessException.notFound("退货单不存在");
        }
        boolean voided = isVoided(r.getBizStatus());
        Integer cs = r.getConfirmStatus();
        List<DocumentTimelineNodeVO> nodes = new ArrayList<>();
        nodes.add(node("created", "创建退货单", "done", r.getOperationTime(),
                "采购管理员 " + r.getOperatorName() + " 创建退货单 " + r.getReturnNo()
                        + "（来源进货单 " + r.getSourcePurchaseNo() + "）"));
        nodes.add(node("confirmedOut", "仓储出库确认", statusOf(voided, cs, 2, 1),
                r.getConfirmTime(), confirmedDesc(r.getConfirmerName(), "确认出库，库存共 -" + r.getTotalQuantity())));
        nodes.add(node("completed", "退货完成确认", statusOf(voided, cs, 3, 2),
                r.getCompleteTime(),
                r.getCompleterName() == null ? "采购管理员确认退货完成"
                        : "采购管理员 " + r.getCompleterName() + " 确认退货完成"));
        appendVoidNodes(nodes, "purchase_return", id, voided, r.getVoidTime(), r.getVoidReason());
        return vo(r.getReturnNo(), nodes);
    }

    // ============================== 销售退货单：创建(销售) → 入库确认(仓储) ==============================

    public DocumentTimelineVO getSalesReturnTimeline(Long id) {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅销售/仓储部门可查看退货时间线", AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
        BizSalesReturn r = bizSalesReturnMapper.selectById(id);
        if (r == null) {
            throw BusinessException.notFound("退货单不存在");
        }
        boolean voided = isVoided(r.getBizStatus());
        Integer cs = r.getConfirmStatus();
        List<DocumentTimelineNodeVO> nodes = new ArrayList<>();
        nodes.add(node("created", "创建退货单", "done", r.getOperationTime(),
                "销售管理员 " + r.getOperatorName() + " 创建退货单 " + r.getReturnNo()
                        + (r.getSourceSalesNo() == null ? "" : "（来源销售单 " + r.getSourceSalesNo() + "）")));
        nodes.add(node("confirmedIn", "仓储入库确认", statusOf(voided, cs, 2, 1),
                r.getConfirmTime(), confirmedDesc(r.getConfirmerName(), "确认入库，库存 +" + r.getTotalQuantity() + " 件")));
        appendVoidNodes(nodes, "sales_return", id, voided, r.getVoidTime(), r.getVoidReason());
        return vo(r.getReturnNo(), nodes);
    }

    // ============================== 私有助手 ==============================

    private boolean isVoided(Integer bizStatus) {
        return bizStatus != null && bizStatus == 2;
    }

    /**
     * 单调推进的 confirmStatus 节点状态：≥doneAt 即 done；未作废且停在 currentAt 为 current；
     * 作废单据流程已终止，未到步骤一律 pending（不再有「进行中」）。
     */
    private String statusOf(boolean voided, Integer cs, int doneAt, int currentAt) {
        if (cs == null) {
            return "pending";
        }
        if (cs >= doneAt) {
            return "done";
        }
        if (!voided && cs == currentAt) {
            return "current";
        }
        return "pending";
    }

    /** 确认类节点描述：有确认人人名用「角色+人名」，否则仅角色（角色锁定的步骤以部门呈现「谁」） */
    private String confirmedDesc(String confirmerName, String action) {
        return confirmerName == null ? "仓储管理员" + action : "仓储管理员 " + confirmerName + " " + action;
    }

    /** 作废节点（D103 站内信化口径呼应）：未作废时若存在待审批/处理中的作废申请则显示「作废审批中」 */
    private void appendVoidNodes(List<DocumentTimelineNodeVO> nodes, String bizType, Long bizId,
                                 boolean voided, LocalDateTime voidTime, String voidReason) {
        if (!voided) {
            LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
            wrapper.eq(BizApprovalOrder::getBizType, bizType)
                    .eq(BizApprovalOrder::getBizId, bizId)
                    .in(BizApprovalOrder::getStatus, APPROVAL_PENDING, APPROVAL_PROCESSING)
                    .in(BizApprovalOrder::getRequestAction, "void", "void_red");
            Long count = bizApprovalOrderMapper.selectCount(wrapper);
            if (count != null && count > 0) {
                nodes.add(node("void_pending", "作废审批中", "current", null,
                        "作废申请待仓储管理员审批，期间单据处理已冻结"));
            }
            return;
        }
        nodes.add(node("voided", "已作废", "done", voidTime,
                voidReason == null || voidReason.isBlank() ? "单据已作废留痕" : "作废原因：" + voidReason));
    }

    /** 批次号 B 后的数值序（解析失败按 0），用于同毫秒批次的稳定排序 */
    private int batchSeq(String batchNo) {
        try {
            return Integer.parseInt(batchNo.substring(1));
        } catch (NumberFormatException | IndexOutOfBoundsException e) {
            return 0;
        }
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
