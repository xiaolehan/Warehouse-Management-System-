package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.example.back.vo.SalesTimelineLineVO;
import org.example.back.vo.SalesTimelineNodeVO;
import org.example.back.vo.SalesTimelineVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 销售单履约时间线（D71/D110 决策⑦）：按明细行逐行聚合 下单→排产→物料→开工→装配→质检→入库→发货 8 节点，
 * 每行推算「预计可交付时间」（仅供参考，非对客承诺）；单行单据观感与旧版一致。
 *
 * 推算口径（D71）：生产手工修正值 > 系统推算；
 * 系统推算 = 缺料时 max(补料采购行预计到货)+工期，齐套/已开工时 (开工时间|当前时间)+工期；
 * BOM 未填工期 → "待生产评估"；未关联生产单 → "待生产排产"。
 *
 * 已知近似：开工/入库节点时间取 最早工序打卡/updateTime 近似（任务单无独立开工/入库时间字段）；
 * 现货判断不预留库存（D72 已确认口径：先出库先赢，不做预留，出库硬校验拦截后退回人工协调）。
 */
@Service
public class SalesTimelineService {

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizProductionOrderMapper productionOrderMapper;

    @Autowired
    private BizBomMapper bomMapper;

    @Autowired
    private BizPurchaseRequestMapper purchaseRequestMapper;

    @Autowired
    private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;

    @Autowired
    private ProductionOrderService productionOrderService;

    @Autowired
    private ProductionStepService productionStepService;

    @Autowired
    private QcService qcService;

    @Autowired
    private AuthzService authzService;

    public SalesTimelineVO getTimeline(Long salesId) {
        // 与销售单读权限一致：销售/仓储部门成员 + 超管
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅销售/仓储部门可查看履约时间线", AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
        BizSales sales = bizSalesMapper.selectById(salesId);
        if (sales == null) {
            throw BusinessException.notFound("销售单不存在");
        }
        List<BizSalesDetail> details = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, salesId)
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId));
        if (details.isEmpty()) {
            throw BusinessException.validateFail("销售单缺少明细行，无法生成履约时间线");
        }
        boolean shipped = sales.getConfirmStatus() != null && sales.getConfirmStatus() == SalesService.CONFIRM_SHIPPED;

        // review 补强：行商品与关联任务单一次批量预取，避免逐行 N+1 查询
        List<Long> goodsIds = details.stream().map(BizSalesDetail::getGoodsId).filter(Objects::nonNull).distinct().toList();
        Map<Long, BaseGoods> goodsMap = goodsIds.isEmpty() ? Map.of() : baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        Map<Long, BizProductionOrder> linkedOrderByGoods = productionOrderMapper.selectList(
                        new LambdaQueryWrapper<BizProductionOrder>()
                                .eq(BizProductionOrder::getSalesOrderId, salesId)
                                .orderByDesc(BizProductionOrder::getId)).stream()
                .collect(Collectors.toMap(BizProductionOrder::getGoodsId, o -> o, (a, b) -> a)); // 同成品多张任务单取最新

        SalesTimelineVO vo = new SalesTimelineVO();
        vo.setSalesOrderId(sales.getId());
        vo.setSalesNo(sales.getSalesNo());
        List<SalesTimelineLineVO> lines = new ArrayList<>();
        for (BizSalesDetail detail : details) {
            lines.add(buildLine(sales, detail, shipped,
                    goodsMap.get(detail.getGoodsId()), linkedOrderByGoods.get(detail.getGoodsId())));
        }
        vo.setLines(lines);
        return vo;
    }

    /** D110 决策⑦：单明细行一根时间线（节点结构与旧版单行完全一致） */
    private SalesTimelineLineVO buildLine(BizSales sales, BizSalesDetail detail, boolean shipped,
                                          BaseGoods goods, BizProductionOrder order) {
        int stock = goods == null || goods.getStock() == null ? 0 : goods.getStock();

        SalesTimelineLineVO line = new SalesTimelineLineVO();
        line.setSalesDetailId(detail.getId());
        line.setGoodsName(detail.getGoodsName());
        line.setQuantity(detail.getQuantity());
        line.setStock(stock);

        // 关联生产任务单：据「同一成品一单只允许一行」由 (头单, 成品) 唯一解析；取最新一张（含已作废，作废单单独呈现）
        List<SalesTimelineNodeVO> nodes = new ArrayList<>();
        nodes.add(node("order_placed", "下单", "done", sales.getOperationTime(),
                "销售单 " + sales.getSalesNo()));

        if (order == null) {
            buildNoOrderNodes(line, nodes, stock, shipped, sales.getConfirmTime());
        } else {
            buildOrderNodes(line, nodes, sales, order, stock, shipped);
        }
        line.setNodes(nodes);
        return line;
    }

    // ============================== 无关联生产单 ==============================

    private void buildNoOrderNodes(SalesTimelineLineVO line, List<SalesTimelineNodeVO> nodes,
                                   int stock, boolean shipped, LocalDateTime shippedTime) {
        int need = line.getQuantity() == null ? 0 : line.getQuantity();
        if (stock >= need) {
            // 现货充足：仅 下单→发货 两节点（D71）
            if (shipped) {
                nodes.add(node("shipped", "发货", "done", shippedTime, "仓储已确认出库"));
                line.setEstimatedDeliveryText("已发货");
            } else {
                nodes.add(node("shipped", "发货", "pending", null, "现货充足，待仓储确认出库"));
                line.setEstimatedDeliveryText("现货充足，立即可发");
            }
            line.setEstimatedSource("none");
        } else {
            nodes.add(node("scheduled", "生产排产", "pending", null, "现货不足（需" + need + "/现存" + stock + "），待生产排产"));
            nodes.add(node("shipped", "发货", "pending", null, null));
            line.setEstimatedDeliveryText("待生产排产");
            line.setEstimatedSource("none");
        }
    }

    // ============================== 有关联生产单（8 节点） ==============================

    private void buildOrderNodes(SalesTimelineLineVO line, List<SalesTimelineNodeVO> nodes,
                                 BizSales sales, BizProductionOrder order, int stock, boolean shipped) {
        // 关联单已作废/已终止 → 排产回退为待排产（D73：关联保留，时间线如实反映）
        if (order.getStatus() != null && (order.getStatus() == BizProductionOrder.STATUS_VOIDED
                || order.getStatus() == BizProductionOrder.STATUS_TERMINATED)) {
            nodes.add(node("scheduled", "生产排产", "pending", null,
                    "关联生产任务单 " + order.getOrderNo() + " " + statusText(order.getStatus()) + "，待重新排产"));
            nodes.add(node("shipped", "发货", "pending", null, null));
            line.setEstimatedDeliveryText("待生产排产");
            line.setEstimatedSource("none");
            return;
        }

        Integer status = order.getStatus();
        boolean started = status != null && status >= BizProductionOrder.STATUS_IN_PROGRESS;
        boolean done = status != null && status == BizProductionOrder.STATUS_DONE;

        List<ProductionStepVO> steps = productionStepService.listSteps(order);
        LocalDateTime startTime = resolveStartTime(order, steps);

        // 2 生产排产
        nodes.add(node("scheduled", "生产排产", "done", order.getCreateTime(),
                "生产任务单 " + order.getOrderNo() + "（" + statusText(status) + "）"));

        // 3 物料准备：开工后物料已领用，不再重算缺口（开工后已发料会显示为假缺口）
        if (started) {
            nodes.add(node("materials", "物料准备", "done", startTime, "物料已领用出库"));
        } else {
            List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(order.getId());
            if (shortage.isEmpty()) {
                nodes.add(node("materials", "物料准备", "done", null, "物料齐套，待申请领料"));
            } else {
                LocalDateTime maxArrival = findMaxExpectedArrival(order.getId());
                String desc = maxArrival == null
                        ? "缺料 " + shortage.size() + " 项，待采购认领填写预计到货时间"
                        : "缺料 " + shortage.size() + " 项，补料采购中，最晚预计到货 " + maxArrival.format(DATE_FMT);
                nodes.add(node("materials", "物料准备", "current", null, desc));
            }
        }

        // 4 开工
        nodes.add(started
                ? node("started", "开工", "done", startTime, null)
                : node("started", "开工", "pending", null, "待领料出库后开工"));

        // 5 装配进度（7 道人工工序打卡）
        long stepDone = steps == null ? 0 : steps.stream()
                .filter(s -> "manual".equals(s.getType()) && Boolean.TRUE.equals(s.getDone())).count();
        String assemblyDesc = "装配工序 " + stepDone + "/7";
        nodes.add(stepDone >= 7
                ? node("assembly", "装配进度", "done", null, assemblyDesc)
                : node("assembly", "装配进度", started ? "current" : "pending", null, assemblyDesc));

        // 6 质检（首测/成品测）
        QcStateVO qc = qcService.buildState(order);
        boolean qcPassed = Boolean.TRUE.equals(qc.getPassed());
        String qcDesc = "首测 " + safe(qc.getFirstStatusText()) + " / 成品测 " + safe(qc.getFinalStatusText());
        LocalDateTime qcTime = qcPassed && qc.getRecords() != null
                ? qc.getRecords().stream().map(QcStateVO.QcRecordVO::getCreateTime)
                        .filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null)
                : null;
        nodes.add(qcPassed
                ? node("qc", "质检", "done", qcTime, qcDesc)
                : node("qc", "质检", started ? "current" : "pending", null, qcDesc));

        // 7 成品入库（任务单无独立入库时间字段，以 updateTime 近似）
        nodes.add(done
                ? node("inbound", "成品入库", "done", order.getUpdateTime(), "已入库 " + order.getQuantity() + " 件")
                : node("inbound", "成品入库", "pending", null, null));

        // 8 发货
        nodes.add(shipped
                ? node("shipped", "发货", "done", sales.getConfirmTime(), "仓储已确认出库")
                : node("shipped", "发货", "pending", null, null));

        fillEstimated(line, order, stock, shipped, done, started, startTime);
    }

    // ============================== 预计可交付时间推算 ==============================

    private void fillEstimated(SalesTimelineLineVO line, BizProductionOrder order,
                               int stock, boolean shipped, boolean done, boolean started, LocalDateTime startTime) {
        if (shipped) {
            line.setEstimatedDeliveryText("已发货");
            line.setEstimatedSource("none");
            return;
        }
        // 生产手工修正值优先于一切推算（D71）
        if (order.getExpectedCompletionTime() != null) {
            line.setEstimatedDeliveryTime(order.getExpectedCompletionTime());
            line.setEstimatedDeliveryText("预计 " + order.getExpectedCompletionTime().format(DATE_FMT) + " 可交付（生产确认）");
            line.setEstimatedSource("manual");
            return;
        }
        int need = line.getQuantity() == null ? 0 : line.getQuantity();
        if (done || stock >= need) {
            line.setEstimatedDeliveryText("现货已备，立即可发");
            line.setEstimatedSource("none");
            return;
        }
        Integer leadDays = resolveLeadDays(order.getGoodsId());
        if (leadDays == null) {
            line.setEstimatedDeliveryText("待生产评估（新品未填标准工期）");
            line.setEstimatedSource("none");
            return;
        }
        if (!started) {
            List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(order.getId());
            if (!shortage.isEmpty()) {
                LocalDateTime maxArrival = findMaxExpectedArrival(order.getId());
                if (maxArrival == null) {
                    line.setEstimatedDeliveryText("缺料待采购确认到货时间");
                    line.setEstimatedSource("none");
                    return;
                }
                LocalDateTime est = maxArrival.plusDays(leadDays);
                line.setEstimatedDeliveryTime(est);
                line.setEstimatedDeliveryText("预计 " + est.format(DATE_FMT) + " 可交付（系统推算）");
                line.setEstimatedSource("system");
                return;
            }
        }
        LocalDateTime base = started ? (startTime == null ? LocalDateTime.now() : startTime) : LocalDateTime.now();
        LocalDateTime est = base.plusDays(leadDays);
        line.setEstimatedDeliveryTime(est);
        line.setEstimatedDeliveryText("预计 " + est.format(DATE_FMT) + " 可交付（系统推算）");
        line.setEstimatedSource("system");
    }

    // ============================== 私有工具 ==============================

    /** 关联生产任务单：(头单, 成品) 解析（D110 行锚定；一成品一行约束保证唯一），取最新一张 */
    /** 缺料时最晚预计到货：该生产单的生产补料采购申请（未入库/未驳回）明细行 expected_arrival_time 最大值 */
    private LocalDateTime findMaxExpectedArrival(Long productionOrderId) {
        LambdaQueryWrapper<BizPurchaseRequest> rw = new LambdaQueryWrapper<>();
        rw.eq(BizPurchaseRequest::getProductionOrderId, productionOrderId)
                .in(BizPurchaseRequest::getStatus,
                        PurchaseRequestService.STATUS_PENDING,
                        PurchaseRequestService.STATUS_PURCHASING,
                        PurchaseRequestService.STATUS_AWAITING_CONFIRM);
        List<BizPurchaseRequest> requests = purchaseRequestMapper.selectList(rw);
        if (requests.isEmpty()) {
            return null;
        }
        List<Long> requestIds = requests.stream().map(BizPurchaseRequest::getId).toList();
        LambdaQueryWrapper<BizPurchaseRequestDetail> dw = new LambdaQueryWrapper<>();
        dw.in(BizPurchaseRequestDetail::getRequestId, requestIds)
                .isNotNull(BizPurchaseRequestDetail::getExpectedArrivalTime);
        return purchaseRequestDetailMapper.selectList(dw).stream()
                .map(BizPurchaseRequestDetail::getExpectedArrivalTime)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    private Integer resolveLeadDays(Long goodsId) {
        LambdaQueryWrapper<BizBom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizBom::getGoodsId, goodsId).last("LIMIT 1");
        BizBom bom = bomMapper.selectOne(wrapper);
        return bom == null ? null : bom.getLeadDays();
    }

    /** 开工时间近似：最早人工工序打卡时间；无打卡则 updateTime 兜底（任务单无独立开工时间字段） */
    private LocalDateTime resolveStartTime(BizProductionOrder order, List<ProductionStepVO> steps) {
        if (steps != null) {
            LocalDateTime earliest = steps.stream()
                    .filter(s -> "manual".equals(s.getType()) && Boolean.TRUE.equals(s.getDone()))
                    .map(ProductionStepVO::getOperateTime)
                    .filter(Objects::nonNull)
                    .min(Comparator.naturalOrder())
                    .orElse(null);
            if (earliest != null) {
                return earliest;
            }
        }
        return order.getUpdateTime();
    }

    private SalesTimelineNodeVO node(String key, String title, String status, LocalDateTime time, String description) {
        SalesTimelineNodeVO node = new SalesTimelineNodeVO();
        node.setKey(key);
        node.setTitle(title);
        node.setStatus(status);
        node.setTime(time);
        node.setDescription(description);
        return node;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "";
        }
        return switch (status) {
            case BizProductionOrder.STATUS_PENDING -> "待生产";
            case BizProductionOrder.STATUS_IN_PROGRESS -> "生产中";
            case BizProductionOrder.STATUS_AWAIT_QC -> "待入库";
            case BizProductionOrder.STATUS_DONE -> "已完成";
            case BizProductionOrder.STATUS_VOIDED -> "已作废";
            case BizProductionOrder.STATUS_SCRAPPED -> "已报废";
            case BizProductionOrder.STATUS_TERMINATED -> "已终止";
            default -> "";
        };
    }

    private String safe(String text) {
        return text == null ? "未测" : text;
    }
}
