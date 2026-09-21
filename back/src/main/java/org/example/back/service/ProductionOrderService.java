package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionBatchReleaseDTO;
import org.example.back.dto.ProductionOrderQueryDTO;
import org.example.back.dto.ProductionOrderSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionOrderVO;
import org.example.back.vo.ProductionPickItemVO;
import org.example.back.vo.ProductionReleasePreviewVO;
import org.example.back.vo.ProductionReleaseResultVO;
import org.example.back.vo.SalesSourceOptionVO;
import org.example.back.vo.QcStateVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产任务单 + 齐套预警（D42/D43）。
 * 建单选成品×数量 → 展开 BOM 算需求 vs 库存 → ok/partial/block；
 * 开工前校验领料单已全额出库，通过则进入生产中。
 */
@Service
public class ProductionOrderService {

    @Autowired
    private BizProductionOrderMapper orderMapper;

    @Autowired
    private BizProductionMapper productionMapper;

    @Autowired
    private BizProductionQcMapper productionQcMapper;

    @Autowired
    private QcService qcService;

    @Autowired
    private ProductionStepService productionStepService;

    @Autowired
    private BizBomMapper bomMapper;

    @Autowired
    private BizBomDetailMapper bomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizPickListMapper pickListMapper;

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;

    @Autowired
    private org.example.back.mapper.BizPurchaseRequestMapper bizPurchaseRequestMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    // D64：10 道生产工序定稿文案与人工工序实例见 ProductionStepService.PROCESS_STEPS
    //（原 D40 8 道通用装配 SOP 常量已废弃；第 6/8 道由质检驱动、第 10 道由入库驱动）

    // ============================== 权限 ==============================

    private void requireOrderReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可访问生产任务单",
                AuthzService.DEPT_PRODUCTION
        );
    }

    private void requireOrderWriteAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION,
                "仅生产研发部管理员可下达/作废生产任务单"
        );
    }

    private void requireOrderExecuteAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部成员可执行生产任务单",
                AuthzService.DEPT_PRODUCTION
        );
    }

    // ============================== 查询 ==============================

    public PageResult<ProductionOrderVO> page(ProductionOrderQueryDTO queryDTO) {
        requireOrderReadAccess();
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getOrderNo()), BizProductionOrder::getOrderNo, queryDTO.getOrderNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizProductionOrder::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getStatus() != null, BizProductionOrder::getStatus, queryDTO.getStatus())
                .orderByDesc(BizProductionOrder::getId);
        // D113：按关联销售单号筛选——看全「这张销售单的所有任务单」
        if (StringUtils.hasText(queryDTO.getSalesNo())) {
            List<Long> salesIds = bizSalesMapper.selectList(new LambdaQueryWrapper<BizSales>()
                            .like(BizSales::getSalesNo, queryDTO.getSalesNo())).stream()
                    .map(BizSales::getId).toList();
            if (salesIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, queryDTO.getPageNum(), queryDTO.getPageSize(), 0L);
            }
            wrapper.in(BizProductionOrder::getSalesOrderId, salesIds);
        }
        Page<BizProductionOrder> page = orderMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        // D64 质检进度列表修复：列表行批量填充 qcState（一次 in 查询分组推导），QcView 列表不再恒显"未测"
        Map<Long, QcStateVO> qcStates = qcService.buildStateBatch(page.getRecords());
        // D105：批量判定领料单是否已全额出库，列表齐套口径与详情统一
        Map<Long, Boolean> pickIssuedMap = pickIssuedMap(page.getRecords());
        // D116：未开工且未全额发料的订单批量实时重算齐套（补料入库后列表立即反映）
        List<BizProductionOrder> pendingForRealtime = page.getRecords().stream()
                .filter(o -> Integer.valueOf(BizProductionOrder.STATUS_PENDING).equals(o.getStatus())
                        && !pickIssuedMap.getOrDefault(o.getId(), false))
                .toList();
        Map<Long, KuaiTaoResult> realtimeKitMap = computeKitBatchForPendingOrders(pendingForRealtime);
        List<ProductionOrderVO> records = new ArrayList<>(page.getRecords().size());
        for (BizProductionOrder order : page.getRecords()) {
            ProductionOrderVO vo = toVO(order);
            vo.setQcState(qcStates.get(order.getId()));
            applyKitDisplay(vo, order, pickIssuedMap.getOrDefault(order.getId(), false),
                    realtimeKitMap.get(order.getId()));
            records.add(vo);
        }
        // D107：列表批量回填待确认入库申请 id（行内「撤销申请」入口据此显隐，与详情同口径）
        fillPendingInboundIdBatch(records, page.getRecords());
        fillSalesOrderNoBatch(records);
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ProductionOrderVO getById(Long id) {
        requireOrderReadAccess();
        BizProductionOrder order = requireOrder(id);
        ProductionOrderVO vo = toVO(order);
        // D105：统一齐套口径——领料单已全额出库（待生产/生产中/待入库）显示「齐套领料」，
        // 不再按仓库当前库存重算（物料已发到生产现场，仓库库存被扣减不代表缺料）
        Integer orderStatus = order.getStatus();
        boolean pickIssued = isPickAllIssued(id);
        if (orderStatus != null && (orderStatus == BizProductionOrder.STATUS_PENDING
                || orderStatus == BizProductionOrder.STATUS_IN_PROGRESS
                || orderStatus == BizProductionOrder.STATUS_AWAIT_QC)) {
            if (pickIssued) {
                vo.setKitStatus(BizProductionOrder.KIT_ISSUED);
                vo.setKitStatusText(kitText(BizProductionOrder.KIT_ISSUED));
                vo.setKitLines(List.of());
            } else if (orderStatus != BizProductionOrder.STATUS_AWAIT_QC) {
                // 未领料（或领料被驳回）维持实时重算，反映补料进度
                KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
                vo.setKitLines(kit.lines);
                vo.setKitStatus(kit.kitStatus);
                vo.setKitStatusText(kitText(kit.kitStatus));
            }
        } else {
            // 已完成/作废/报废/终止：齐套标签失去意义，不再显示
            vo.setKitStatus(null);
            vo.setKitStatusText("");
        }
        // D107：待确认入库申请 / 最近一次驳回原因（前端据此显示"待仓储确认"或"重新提交"提示）
        fillInboundApplicationInfo(vo, order);
        // 生产/待入库/已报废/已终止阶段展示质检状态（终止冻结但记录保留可见）
        if (order.getStatus() == BizProductionOrder.STATUS_IN_PROGRESS
                || order.getStatus() == BizProductionOrder.STATUS_AWAIT_QC
                || order.getStatus() == BizProductionOrder.STATUS_SCRAPPED
                || order.getStatus() == BizProductionOrder.STATUS_TERMINATED) {
            vo.setQcState(qcService.buildState(order));
        }
        // D64：10 道工序行（人工行读步骤实例，第 6/8/10 道实时推导；历史单无实例返回 null，前端回落快照文字）
        vo.setStepList(productionStepService.listSteps(order));
        // D87：在途补料单号（补料弹窗"已有在途补料单"提示用；同一生产单至多一张，D86 守卫保证）
        vo.setInFlightRequestNo(findInFlightDraftRequestNo(order.getId()));
        fillSalesOrderNoBatch(List.of(vo));
        return vo;
    }

    /** D87：在途补料单号——生产来源 + 在途状态（1待采购/2采购中/5待入库确认），取最新一张；无则 null。 */
    private String findInFlightDraftRequestNo(Long orderId) {
        LambdaQueryWrapper<org.example.back.entity.BizPurchaseRequest> w = new LambdaQueryWrapper<>();
        w.eq(org.example.back.entity.BizPurchaseRequest::getProductionOrderId, orderId)
                .eq(org.example.back.entity.BizPurchaseRequest::getSourceType, PurchaseRequestService.SOURCE_PRODUCTION)
                .in(org.example.back.entity.BizPurchaseRequest::getStatus, PurchaseRequestService.IN_FLIGHT_STATUSES)
                .orderByDesc(org.example.back.entity.BizPurchaseRequest::getId);
        List<org.example.back.entity.BizPurchaseRequest> inFlight = bizPurchaseRequestMapper.selectList(w);
        return inFlight.isEmpty() ? null : inFlight.get(0).getRequestNo();
    }

    // ============================== 建单（含齐套预警） ==============================

    @Transactional(rollbackFor = Exception.class)
    public ProductionOrderVO create(ProductionOrderSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderWriteAccess();
        BaseGoods product = requireProduct(dto.getGoodsId());
        KuaiTaoResult kit = computeKit(dto.getGoodsId(), dto.getQuantity());
        // D70/D110：选填关联销售单（须同成品、正常且待出库，且单内有该成品明细行）；通用备货单留空
        BizSalesDetail linkedDetail = requireLinkableSalesOrder(dto.getSalesOrderId(), product.getId());

        BizProductionOrder saved = insertOrder(product, dto.getQuantity(), kit, linkedDetail, dto.getRemark());

        ProductionOrderVO vo = toVO(saved);
        vo.setKitLines(kit.lines);
        return vo;
    }

    /** D113：落单主体（建单号/写锚点/初始化工序/齐套预警）——单条建单与按销售单批量下达共用 */
    private BizProductionOrder insertOrder(BaseGoods product, int quantity, KuaiTaoResult kit,
                                           BizSalesDetail linkedDetail, String remark) {
        BizProductionOrder order = new BizProductionOrder();
        order.setOrderNo(CodeGenerator.productionNo());
        order.setGoodsId(product.getId());
        order.setGoodsName(product.getGoodsName());
        order.setUnit(product.getUnit());
        order.setQuantity(quantity);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        order.setKitStatus(kit.kitStatus);
        order.setSource(BizProductionOrder.SOURCE_MANUAL);
        order.setSalesOrderId(linkedDetail == null ? null : linkedDetail.getSalesId());
        order.setSalesDetailId(linkedDetail == null ? null : linkedDetail.getId());
        order.setProcessSnapshot(String.join("\n", ProductionStepService.PROCESS_STEPS));
        order.setRemark(remark);
        orderMapper.insert(order);
        BizProductionOrder saved = orderMapper.selectById(order.getId());
        // D64：初始化 7 道人工工序实例行
        productionStepService.initStepsForOrder(saved.getId());

        // D60：建单即齐套预警——存在严重缺料/未知物料时通知采购管理员（作废时由 voidOrder 撤未读）
        if (BizProductionOrder.KIT_BLOCK.equals(kit.kitStatus)) {
            messageService.sendKitShortageToPurchaseAdmins(
                    saved.getOrderNo(), product.getGoodsName(), kit.summary(quantity), saved.getId());
        }
        return saved;
    }

    // ============================== D113：按销售单批量下达 ==============================

    /**
     * 批量下达候选销售单：正常（未作废）且待出库，不限成品（多成品单场景）。
     */
    public List<SalesSourceOptionVO> batchReleaseSalesOptions() {
        requireOrderReadAccess();
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSales::getBizStatus, 1)
                .eq(BizSales::getConfirmStatus, SalesService.CONFIRM_PENDING)
                .orderByDesc(BizSales::getOperationTime)
                .orderByDesc(BizSales::getId)
                .last("LIMIT 50");
        List<BizSales> salesList = bizSalesMapper.selectList(wrapper);
        return salesList.stream().map(s -> {
            SalesSourceOptionVO vo = new SalesSourceOptionVO();
            vo.setId(s.getId());
            vo.setSalesNo(s.getSalesNo());
            vo.setCustomerName(s.getCustomerName());
            vo.setOperationTime(s.getOperationTime());
            return vo;
        }).toList();
    }

    /**
     * 批量下达预览：列出销售单全部明细行，标注 BOM 有无、当前库存、在途任务单与已生产量。
     */
    public ProductionReleasePreviewVO releasePreview(Long salesOrderId) {
        requireOrderReadAccess();
        BizSales sales = requireReleaseableSales(salesOrderId);
        ProductionReleasePreviewVO vo = new ProductionReleasePreviewVO();
        vo.setSalesId(sales.getId());
        vo.setSalesNo(sales.getSalesNo());
        vo.setCustomerName(sales.getCustomerName());
        List<BizSalesDetail> lines = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, salesOrderId)
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId));
        Map<Long, List<BizProductionOrder>> anchored = anchoredOrdersByDetail(
                lines.stream().map(BizSalesDetail::getId).toList());
        List<ProductionReleasePreviewVO.PreviewLineVO> previewLines = new ArrayList<>();
        for (BizSalesDetail line : lines) {
            ProductionReleasePreviewVO.PreviewLineVO pl = new ProductionReleasePreviewVO.PreviewLineVO();
            pl.setSalesDetailId(line.getId());
            pl.setGoodsId(line.getGoodsId());
            pl.setGoodsName(line.getGoodsName());
            pl.setQuantity(line.getQuantity());
            BaseGoods product = baseGoodsMapper.selectById(line.getGoodsId());
            pl.setStock(product == null ? null : product.getStock());
            boolean hasBom = bomMapper.selectOne(new LambdaQueryWrapper<BizBom>()
                    .eq(BizBom::getGoodsId, line.getGoodsId())) != null;
            pl.setHasBom(hasBom);
            List<BizProductionOrder> orders = anchored.getOrDefault(line.getId(), List.of());
            pl.setInFlightOrderNo(inFlightOrderNos(orders));
            pl.setDoneQuantity(doneQuantity(orders));
            previewLines.add(pl);
        }
        vo.setLines(previewLines);
        return vo;
    }

    /**
     * D113：按销售单批量下达——逐行独立校验、无 BOM/已有在途单等行跳过不阻断其他行。
     * 校验全部发生在该行任何写库之前（失败行不产生半成品数据），通过行复用单条建单逻辑
     * （insertOrder：齐套快照/工序初始化/缺料预警逐单正常触发）。
     */
    @Transactional(rollbackFor = Exception.class)
    public List<ProductionReleaseResultVO> batchRelease(ProductionBatchReleaseDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderWriteAccess();
        BizSales sales = requireReleaseableSales(dto.getSalesOrderId());

        List<BizSalesDetail> salesLines = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, dto.getSalesOrderId()));
        Map<Long, BizSalesDetail> lineMap = salesLines.stream()
                .collect(Collectors.toMap(BizSalesDetail::getId, Function.identity()));
        Map<Long, List<BizProductionOrder>> anchored = anchoredOrdersByDetail(
                dto.getItems().stream().map(ProductionBatchReleaseDTO.BatchItemDTO::getSalesDetailId).toList());

        List<ProductionReleaseResultVO> results = new ArrayList<>();
        Set<Long> seenDetailIds = new HashSet<>();
        for (ProductionBatchReleaseDTO.BatchItemDTO item : dto.getItems()) {
            ProductionReleaseResultVO result = new ProductionReleaseResultVO();
            result.setSalesDetailId(item.getSalesDetailId());
            result.setQuantity(item.getQuantity());
            BizSalesDetail line = lineMap.get(item.getSalesDetailId());
            if (line == null) {
                result.setSuccess(false);
                result.setSkipReason("明细行不归属该销售单");
                results.add(result);
                continue;
            }
            result.setGoodsName(line.getGoodsName());
            List<BizProductionOrder> lineOrders = anchored.getOrDefault(line.getId(), List.of());
            String inFlightNos = inFlightOrderNos(lineOrders);
            if (!inFlightNos.isEmpty()) {
                result.setSuccess(false);
                result.setSkipReason("已有在途任务单（" + inFlightNos + "）");
                results.add(result);
                continue;
            }
            int doneQty = doneQuantity(lineOrders);
            if (doneQty >= line.getQuantity()) {
                result.setSuccess(false);
                result.setSkipReason("该行已生产入库数量已满足订单需求（已完成 " + doneQty + "）");
                results.add(result);
                continue;
            }
            if (!seenDetailIds.add(line.getId())) {
                result.setSuccess(false);
                result.setSkipReason("与本次提交的其他行重复");
                results.add(result);
                continue;
            }
            BaseGoods product;
            KuaiTaoResult kit;
            try {
                product = requireProduct(line.getGoodsId());
                kit = computeKit(line.getGoodsId(), item.getQuantity());
            } catch (BusinessException ex) {
                // 无 BOM/成品停用等行：预校验失败即跳过并说明，不阻断其他行（该行尚未写库）
                result.setSuccess(false);
                result.setSkipReason(ex.getMessage());
                results.add(result);
                continue;
            }
            // 校验通过才落单；insertOrder 内若再抛异常即整批回滚——绝不出现「结果标跳过却留下半成品单」
            BizProductionOrder saved = insertOrder(product, item.getQuantity(), kit, line, null);
            result.setSuccess(true);
            result.setOrderId(saved.getId());
            result.setOrderNo(saved.getOrderNo());
            result.setKitStatus(kit.kitStatus);
            results.add(result);
        }
        return results;
    }

    /** 可下达的销售单：存在、正常（未作废）且待出库（与单条关联口径一致） */
    private BizSales requireReleaseableSales(Long salesOrderId) {
        BizSales sales = bizSalesMapper.selectById(salesOrderId);
        if (sales == null) {
            throw BusinessException.notFound("销售单不存在");
        }
        if (sales.getBizStatus() == null || sales.getBizStatus() != 1) {
            throw BusinessException.validateFail("关联销售单已作废，无法下达");
        }
        if (sales.getConfirmStatus() == null || sales.getConfirmStatus() != SalesService.CONFIRM_PENDING) {
            throw BusinessException.validateFail("关联销售单已确认出库，无需排产");
        }
        return sales;
    }

    /** 行的在途任务单单号串（按 UNFINISHED_STATUSES 过滤，无则在途为空串） */
    private String inFlightOrderNos(List<BizProductionOrder> orders) {
        return orders.stream()
                .filter(o -> BizProductionOrder.UNFINISHED_STATUSES.contains(o.getStatus()))
                .map(BizProductionOrder::getOrderNo)
                .collect(Collectors.joining("、"));
    }

    /** 行已完成生产任务单的数量合计（D113 已生产满足口径：DONE 单计划量，本系统入库不可部分量） */
    private int doneQuantity(List<BizProductionOrder> orders) {
        return orders.stream()
                .filter(o -> Integer.valueOf(BizProductionOrder.STATUS_DONE).equals(o.getStatus()))
                .mapToInt(BizProductionOrder::getQuantity)
                .sum();
    }

    /** 一次查出全部锚定行任务单（在途+已完成），按 sales_detail_id 分组（批量下达/预览共用，防 N+1） */
    private Map<Long, List<BizProductionOrder>> anchoredOrdersByDetail(List<Long> salesDetailIds) {
        if (salesDetailIds == null || salesDetailIds.isEmpty()) {
            return Map.of();
        }
        List<Integer> trackedStatuses = new ArrayList<>(BizProductionOrder.UNFINISHED_STATUSES);
        trackedStatuses.add(BizProductionOrder.STATUS_DONE);
        List<BizProductionOrder> orders = orderMapper.selectList(new LambdaQueryWrapper<BizProductionOrder>()
                .in(BizProductionOrder::getSalesDetailId, salesDetailIds)
                .in(BizProductionOrder::getStatus, trackedStatuses));
        return orders.stream().collect(Collectors.groupingBy(BizProductionOrder::getSalesDetailId));
    }


    /**
     * 开工：校验该生产单领料单已全额出库后，进入生产中（D59）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        ensureStatus(order, BizProductionOrder.STATUS_PENDING, "仅待生产状态可开工");

        // D59：开工前置 = 该生产单已申请领料且领料单已全额出库
        if (!isPickAllIssued(id)) {
            throw BusinessException.validateFail("该生产任务单领料单尚未全额出库，请先申请领料并由仓储确认出库后开工");
        }

        order.setStatus(BizProductionOrder.STATUS_IN_PROGRESS);
        orderMapper.updateById(order);
    }

    /**
     * 完工：生产中 → 已完成（质检流程于阶段12在中间插入待质检）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        if (order.getStatus() != BizProductionOrder.STATUS_IN_PROGRESS) {
            throw BusinessException.validateFail("仅生产中状态可完工");
        }
        // D40：首测与成品测均合格后才允许完工/入库
        QcStateVO qc = qcService.buildState(order);
        if (Boolean.TRUE.equals(qc.getScrapped())) {
            throw BusinessException.validateFail("该订单已报废，无法完工");
        }
        if (!Boolean.TRUE.equals(qc.getPassed())) {
            throw BusinessException.validateFail("首测与成品测均合格后，才能完工入库");
        }
        order.setStatus(BizProductionOrder.STATUS_DONE);
        orderMapper.updateById(order);
    }

    /**
     * 生产入库（阶段13；D107 改两段式）：质检合格进入待入库后，生产端点击「提交入库申请」
     * → 生成一笔待仓储确认的入库记录（不加库存），由仓储管理员确认后才增加库存并完成任务单。
     * 取代生产端管理员在仓储自由建"生产入库"单的方式，改由生产订单驱动、质检前置把关。
     */
    @Transactional(rollbackFor = Exception.class)
    public void receipt(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        if (order.getStatus() != BizProductionOrder.STATUS_AWAIT_QC) {
            throw BusinessException.validateFail("仅待入库状态可提交成品入库申请，请先完成质检");
        }
        // 质检前置校验：首测+成品测最新均 OK 且未报废
        qcService.ensurePassedForReceipt(id);
        // D107：同一生产单至多一笔待确认入库申请
        if (findPendingInbound(id) != null) {
            throw BusinessException.validateFail("该生产任务单已有待仓储确认的入库申请，如需调整请先撤销再重新提交");
        }

        BaseGoods product = requireGoodsNoStatus(order.getGoodsId());
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizProduction in = new BizProduction();
        in.setProductionNo(CodeGenerator.productionNo());
        in.setProductionOrderId(order.getId());
        in.setGoodsId(product.getId());
        in.setGoodsName(product.getGoodsName());
        in.setQuantity(order.getQuantity());
        in.setOperatorId(user.getId());
        in.setOperatorName(user.getRealName());
        in.setOperationTime(LocalDateTime.now());
        in.setBizStatus(1);
        in.setConfirmStatus(BizProduction.CONFIRM_PENDING);
        in.setRemark("生产任务单 " + order.getOrderNo() + " 完工入库申请");
        try {
            productionMapper.insert(in);
        } catch (DuplicateKeyException e) {
            // uk_production_pending_order 并发兜底：两个提交事务同时穿过上面的先查后插
            throw BusinessException.validateFail("该生产任务单已有待仓储确认的入库申请，请勿重复提交");
        }

        // D107：通知仓储管理员确认入库（D21 范式绑 biz_type=production，确认/驳回/撤销时撤未读）
        messageService.sendProductionInboundPendingToWarehouseAdmins(
                in.getProductionNo(), order.getOrderNo(), product.getGoodsName(), order.getQuantity(), in.getId());
    }

    /**
     * D107：生产端撤销入库申请（仓储确认/驳回前可撤）——逻辑删除待确认记录并撤仓储待办消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelReceipt(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderExecuteAccess();
        requireOrder(id);
        BizProduction pending = findPendingInbound(id);
        if (pending == null) {
            throw BusinessException.validateFail("该生产任务单没有待确认的入库申请可撤销");
        }
        // review 修复：逻辑删必须带 confirm_status=1 条件——否则与仓储「确认入库」并发时，
        // deleteById 会把已承载库存增加的已确认行软删，形成无单据可追溯的孤儿库存
        LambdaUpdateWrapper<BizProduction> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizProduction::getId, pending.getId())
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .eq(BizProduction::getBizStatus, 1)
                .set(BizProduction::getIsDeleted, 1);
        int rows = productionMapper.update(null, uw);
        if (rows != 1) {
            throw BusinessException.validateFail("入库申请已被仓储确认或驳回，无法撤销");
        }
        messageService.revokeUnreadByBiz("production", pending.getId());
    }

    /**
     * D107/review：任务单离开待入库态（生产终止 / 质检返工 / 质检报废）时自动关闭待确认入库申请——
     * 置「已驳回」+系统关闭原因，撤仓储待办并回执提交人。
     * 防终态/返工单仍挂确认待办：仓储一旦确认就会凭空加库存并把非待入库订单复活为已完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void closePendingInboundApplication(Long orderId, String reason) {
        BizProduction pending = findPendingInbound(orderId);
        if (pending == null) {
            return;
        }
        LambdaUpdateWrapper<BizProduction> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizProduction::getId, pending.getId())
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .set(BizProduction::getConfirmStatus, BizProduction.CONFIRM_REJECTED)
                .set(BizProduction::getConfirmerName, "系统")
                .set(BizProduction::getConfirmTime, LocalDateTime.now())
                .set(BizProduction::getRejectReason, reason);
        if (productionMapper.update(null, uw) != 1) {
            return;
        }
        // 撤仓储待办 + 回执提交人（回执留痕，不随流程撤回）
        messageService.revokeUnreadByBiz("production", pending.getId());
        messageService.sendProductionInboundRejectedToUser(
                pending.getOperatorId(), pending.getProductionNo(), reason, pending.getId());
    }

    /**
     * D107：仓储确认入库后的任务单收尾（同事务内由 ProductionService.confirmInbound 调用）——
     * 转已完成 + 撤任务单未读待办 + 通知关联销售"可发货"。
     */
    @Transactional(rollbackFor = Exception.class)
    public void finalizeAfterInboundConfirmed(Long orderId) {
        BizProductionOrder order = requireOrder(orderId);
        // review 修复：防御性状态守卫——只有待入库单可被确认入库收尾，
        // 防止申请提交后任务单被终止/返工/报废，仓储确认时把非待入库单复活为已完成
        if (order.getStatus() == null || order.getStatus() != BizProductionOrder.STATUS_AWAIT_QC) {
            throw BusinessException.validateFail(
                    "生产任务单当前为「" + statusText(order.getStatus()) + "」状态，不能确认入库，请驳回该入库申请");
        }
        // review 补强：条件更新兜底并发（读守卫与写之间任务单可能被终止/完工）——只有仍处待入库态才转已完成
        LambdaUpdateWrapper<BizProductionOrder> doneWrapper = new LambdaUpdateWrapper<>();
        doneWrapper.eq(BizProductionOrder::getId, orderId)
                .eq(BizProductionOrder::getStatus, BizProductionOrder.STATUS_AWAIT_QC)
                .set(BizProductionOrder::getStatus, BizProductionOrder.STATUS_DONE);
        if (orderMapper.update(null, doneWrapper) != 1) {
            throw BusinessException.validateFail(
                    "生产任务单已被处理（当前为「" + statusText(requireOrder(orderId).getStatus()) + "」），不能确认入库，请刷新后重试");
        }
        // D73：订单终态（已完成）——撤销该单未读待办（含"关联销售单已取消"等绑 production_order 的消息）
        messageService.revokeUnreadByBiz("production_order", orderId);
        // D70：关联销售单仍待出库 → 通知建单销售本人"可发货"（biz 绑定销售单，出库/作废撤未读）
        notifySalesReadyToShipIfLinked(order);
    }

    /**
     * D71：生产手工修正预计完工时间（仅未完结单；留痕走 Controller 层 @AuditLog）。
     * 手工值优先于系统推算；传 null 视为清除手工值（恢复系统推算）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateExpectedCompletion(Long id, LocalDateTime expectedCompletionTime) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        if (order.getStatus() == null || !BizProductionOrder.UNFINISHED_STATUSES.contains(order.getStatus())) {
            throw BusinessException.validateFail("仅未完结（待生产/生产中/待入库）的生产任务单可修正预计完工时间");
        }
        order.setExpectedCompletionTime(expectedCompletionTime);
        orderMapper.updateById(order);
    }

    /**
     * 作废：待生产/生产中可作废；撤销未读采购预警，避免悬挂通知。
     */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long id, String reason) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireOrderWriteAccess();
        BizProductionOrder order = requireOrder(id);
        if (order.getStatus() != BizProductionOrder.STATUS_PENDING
                && order.getStatus() != BizProductionOrder.STATUS_IN_PROGRESS) {
            throw BusinessException.validateFail("仅待生产或生产中状态可作废");
        }
        messageService.revokeUnreadByBiz("production_order", id);
        order.setStatus(BizProductionOrder.STATUS_VOIDED);
        order.setRemark(patchRemark(order.getRemark(), reason));
        orderMapper.updateById(order);
    }

    // ============================== 私有：齐套展开 ==============================

    private static class KuaiTaoResult {
        String kitStatus = BizProductionOrder.KIT_OK;
        boolean hasShortage = false;
        final List<KitShortageVO> lines = new ArrayList<>();

        String summary(int quantity) {
            return lines.stream()
                    .filter(l -> !"ok".equals(l.getLineStatus()))
                    .map(l -> {
                        // D60：缺口明细带规格防同名歧义，未知物料行加【新物料】标注
                        String specPart = StringUtils.hasText(l.getSpec()) ? "（" + l.getSpec() + "）" : "";
                        String newPart = "unknown".equals(l.getLineStatus()) ? "【新物料】" : "";
                        return l.getGoodsName() + specPart + newPart
                                + " 需" + l.getRequired().stripTrailingZeros().toPlainString()
                                + " 库" + l.getStock() + " 缺" + l.getDeficit().stripTrailingZeros().toPlainString();
                    })
                    .collect(Collectors.joining("；"));
        }
    }

    /** 展开成品 BOM×数量：算每项物料的 需求 vs 库存 与匹配等级 */
    private KuaiTaoResult computeKit(Long goodsId, int quantity) {
        BizBom bom = requireBomOfProduct(goodsId);
        List<BizBomDetail> details = loadRealBomDetails(bom.getId());
        return assembleKit(details, quantity, loadGoodsForDetails(details));
    }

    // D4X（方案先行）：BOM 明细只要真需求(非参考行、有组件名)就计入齐套；未关联物料的明细行按"缺料待采购"处理(库存0)——不再丢弃。
    // 生产研发先定 BOM 方案，物料可后补建档并回挂 goodsId；回挂并入库存后该行即正常参与齐套。
    /** 取 BOM 真实需求明细（非参考行、有组件名），按 sortNo 排序。 */
    private List<BizBomDetail> loadRealBomDetails(Long bomId) {
        LambdaQueryWrapper<BizBomDetail> dw = new LambdaQueryWrapper<>();
        dw.eq(BizBomDetail::getBomId, bomId)
                .eq(BizBomDetail::getIsReference, 0)
                .orderByAsc(BizBomDetail::getSortNo);
        return bomDetailMapper.selectList(dw).stream()
                .filter(d -> d.getComponentName() != null && !d.getComponentName().trim().isEmpty())
                .toList();
    }

    /** 批量预取明细涉及的物料（一次 selectBatchIds，替代逐行 selectById）。 */
    private Map<Long, BaseGoods> loadGoodsForDetails(List<BizBomDetail> details) {
        List<Long> goodsIds = details.stream()
                .map(BizBomDetail::getGoodsId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        return baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
    }

    /** 逐行构建齐套行 + 汇总等级（block > partial > ok）。 */
    private KuaiTaoResult assembleKit(List<BizBomDetail> details, int quantity, Map<Long, BaseGoods> goodsMap) {
        KuaiTaoResult result = new KuaiTaoResult();
        if (details.isEmpty()) {
            result.kitStatus = BizProductionOrder.KIT_OK;
            return result;
        }
        for (BizBomDetail d : details) {
            KitShortageVO line = buildKitLine(d, quantity, goodsMap);
            if (!"ok".equals(line.getLineStatus())) {
                result.hasShortage = true;
            }
            result.lines.add(line);
        }
        // 汇总等级：有严重缺/未知物料 → block；否则有部分缺 → partial
        boolean anyBlock = result.lines.stream()
                .anyMatch(l -> "block".equals(l.getLineStatus()) || "unknown".equals(l.getLineStatus()));
        if (anyBlock) {
            result.kitStatus = BizProductionOrder.KIT_BLOCK;
        } else if (result.hasShortage) {
            result.kitStatus = BizProductionOrder.KIT_PARTIAL;
        }
        return result;
    }

    /** 构建单行：需求=单台用量×生产数量；goodsId 空=未知物料，按库存 0 处理。 */
    private KitShortageVO buildKitLine(BizBomDetail d, int quantity, Map<Long, BaseGoods> goodsMap) {
        String name = d.getComponentName();
        // goodsId 空 = 物料未在仓库建档（方案先行待采购），按库存 0 的严重缺料处理
        BaseGoods g = d.getGoodsId() == null ? null : goodsMap.get(d.getGoodsId());
        BigDecimal usage = d.getQuantity() == null ? BigDecimal.ONE : d.getQuantity();
        BigDecimal required = usage.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_UP);
        int stock = (g == null || g.getStock() == null) ? 0 : g.getStock();

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(d.getId());
        line.setSpec(d.getSpec());
        line.setMaterial(d.getMaterial());
        line.setRemark(d.getRemark());
        if (g != null) {
            line.setGoodsId(g.getId());
            line.setGoodsName(g.getGoodsName());
            line.setUnit(g.getUnit());
        } else {
            line.setGoodsId(null);
            line.setGoodsName(name);
            line.setUnit(null);
        }
        line.setUnitUsage(usage);
        line.setRequired(required);
        line.setStock(stock);
        line.setDeficit(required.subtract(BigDecimal.valueOf(stock)).setScale(4, RoundingMode.HALF_UP));

        if (g == null) {
            // D60：未绑定物料=未知物料（首次出现，不应从已有物料下拉就近选）
            line.setLineStatus("unknown");
            line.setLineStatusText("未知物料");
        } else if (stock >= required.intValue()) {
            line.setLineStatus("ok");
            line.setLineStatusText("齐套");
        } else if (stock > 0) {
            line.setLineStatus("partial");
            line.setLineStatusText("部分缺料");
        } else {
            line.setLineStatus("block");
            line.setLineStatusText("严重缺料");
        }
        return line;
    }

    /**
     * D116：状态 1（未开工）订单的列表批量实时齐套——BOM/明细/库存全部批量预取，
     * 避免逐单 computeKit 的 N+1。返回 orderId→实时结果；成品 BOM 已不存在
     * （如事后删除）的订单不在返回中，调用方回退建单快照。
     */
    private Map<Long, KuaiTaoResult> computeKitBatchForPendingOrders(List<BizProductionOrder> orders) {
        if (orders.isEmpty()) {
            return Map.of();
        }
        // 成品 → BOM（与 requireBomOfProduct 同口径：@TableLogic 过滤已删；异常多 BOM 取一）
        List<Long> productIds = orders.stream().map(BizProductionOrder::getGoodsId).distinct().toList();
        LambdaQueryWrapper<BizBom> bw = new LambdaQueryWrapper<>();
        bw.in(BizBom::getGoodsId, productIds);
        Map<Long, BizBom> bomByGoods = new HashMap<>();
        for (BizBom b : bomMapper.selectList(bw)) {
            bomByGoods.putIfAbsent(b.getGoodsId(), b);
        }
        if (bomByGoods.isEmpty()) {
            return Map.of();
        }
        // BOM → 真实需求明细，按 bomId 分组
        List<Long> bomIds = bomByGoods.values().stream().map(BizBom::getId).toList();
        LambdaQueryWrapper<BizBomDetail> dw = new LambdaQueryWrapper<>();
        dw.in(BizBomDetail::getBomId, bomIds)
                .eq(BizBomDetail::getIsReference, 0)
                .orderByAsc(BizBomDetail::getSortNo);
        Map<Long, List<BizBomDetail>> detailsByBom = bomDetailMapper.selectList(dw).stream()
                .filter(d -> d.getComponentName() != null && !d.getComponentName().trim().isEmpty())
                .collect(Collectors.groupingBy(BizBomDetail::getBomId));
        // 明细涉及物料一次批量查
        List<Long> gIds = detailsByBom.values().stream().flatMap(List::stream)
                .map(BizBomDetail::getGoodsId).filter(Objects::nonNull).distinct().toList();
        Map<Long, BaseGoods> goodsMap = gIds.isEmpty() ? Map.of()
                : baseGoodsMapper.selectBatchIds(gIds).stream()
                        .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        // 逐单装配；BOM 缺失的单跳过
        Map<Long, KuaiTaoResult> result = new HashMap<>();
        for (BizProductionOrder o : orders) {
            BizBom bom = bomByGoods.get(o.getGoodsId());
            if (bom == null) {
                continue;
            }
            List<BizBomDetail> dList = detailsByBom.getOrDefault(bom.getId(), List.of());
            result.put(o.getId(), assembleKit(dList, o.getQuantity(), goodsMap));
        }
        return result;
    }

    /**
     * 供采购申请草稿：返回该任务单存在缺口(deficit>0)的物料行，含 bomDetailId 供回挂定位。
     */
    public List<KitShortageVO> computeShortageForOrder(Long productionOrderId) {
        BizProductionOrder order = requireOrder(productionOrderId);
        KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
        return kit.lines.stream()
                .filter(l -> l.getDeficit() != null && l.getDeficit().compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    /**
     * 生产申请领料：返回该生产单 BOM 展开后全部可领物料(需求数量锁定)，
     * goodsId 为空或库存不足的行抛错。数量由后端按 BOM×生产数量计算并向上取整。
     */
    public List<ProductionPickItemVO> computePickItems(Long productionOrderId) {
        BizProductionOrder order = requireOrder(productionOrderId);
        KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
        List<ProductionPickItemVO> items = new ArrayList<>();
        for (KitShortageVO line : kit.lines) {
            if (line.getGoodsId() == null) {
                throw BusinessException.validateFail("物料[" + line.getGoodsName() + "]未在仓库建档，无法申请领料");
            }
            int requiredInt = line.getRequired().setScale(0, RoundingMode.UP).intValue();
            if (line.getStock() == null || line.getStock() < requiredInt) {
                throw BusinessException.validateFail("物料[" + line.getGoodsName() + "]库存不足（需" + requiredInt + "，现" + line.getStock() + "），请补料后再领");
            }
            ProductionPickItemVO item = new ProductionPickItemVO();
            item.setGoodsId(line.getGoodsId());
            item.setGoodsName(line.getGoodsName());
            item.setQuantity(requiredInt);
            items.add(item);
        }
        if (items.isEmpty()) {
            throw BusinessException.validateFail("该生产单无可领物料（BOM 为空或全部为参考行）");
        }
        return items;
    }

    // ============================== 私有：校验与工具 ==============================

    /**
     * D70/D110：校验可关联的销售单——存在、正常（未作废）、待出库，且存在该成品的明细行
     * （「同一成品一单只允许一行」约束保证 (头单,成品) 唯一解析出行）；null 表示不关联（备货单）。
     * 返回解析出的销售明细行（create 据此写 sales_detail_id 锚定）。
     */
    private BizSalesDetail requireLinkableSalesOrder(Long salesOrderId, Long goodsId) {
        if (salesOrderId == null) {
            return null;
        }
        BizSales sales = bizSalesMapper.selectById(salesOrderId);
        if (sales == null) {
            throw BusinessException.validateFail("关联销售单不存在");
        }
        if (sales.getBizStatus() == null || sales.getBizStatus() != 1) {
            throw BusinessException.validateFail("关联销售单已作废，无法关联");
        }
        if (sales.getConfirmStatus() == null || sales.getConfirmStatus() != SalesService.CONFIRM_PENDING) {
            throw BusinessException.validateFail("关联销售单已确认出库，无需排产");
        }
        List<BizSalesDetail> lines = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, salesOrderId)
                .eq(BizSalesDetail::getGoodsId, goodsId)
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId)
                .last("LIMIT 1"));
        if (lines.isEmpty()) {
            throw BusinessException.validateFail("关联销售单中没有本任务单成品的明细行，无法关联");
        }
        return lines.get(0);
    }

    /** D70：生产入库后，若关联销售单仍正常且待出库 → 通知建单销售本人 */
    private void notifySalesReadyToShipIfLinked(BizProductionOrder order) {
        if (order.getSalesOrderId() == null) {
            return;
        }
        BizSales sales = bizSalesMapper.selectById(order.getSalesOrderId());
        if (sales == null || sales.getBizStatus() == null || sales.getBizStatus() != 1
                || sales.getConfirmStatus() == null || sales.getConfirmStatus() != SalesService.CONFIRM_PENDING) {
            return;
        }
        messageService.sendSalesReadyToShipToUser(
                sales.getOperatorId(), sales.getSalesNo(), order.getGoodsName() + "×" + order.getQuantity(), sales.getId());
    }

    /** D70/D73：批量填充关联销售单号；销售单已作废→"单号（已作废）"、已删除→"已取消的销售单" */
    private void fillSalesOrderNoBatch(List<ProductionOrderVO> records) {
        List<Long> salesIds = records.stream().map(ProductionOrderVO::getSalesOrderId)
                .filter(java.util.Objects::nonNull).distinct().toList();
        if (salesIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BizSales::getId, salesIds);
        Map<Long, BizSales> salesMap = bizSalesMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(BizSales::getId, s -> s));
        for (ProductionOrderVO vo : records) {
            if (vo.getSalesOrderId() == null) {
                continue;
            }
            BizSales sales = salesMap.get(vo.getSalesOrderId());
            if (sales == null) {
                vo.setSalesOrderNo("已取消的销售单");
            } else if (sales.getBizStatus() == null || sales.getBizStatus() != 1) {
                vo.setSalesOrderNo(sales.getSalesNo() + "（已作废）");
            } else {
                vo.setSalesOrderNo(sales.getSalesNo());
            }
        }
    }

    private BaseGoods requireProduct(Long goodsId) {
        BaseGoods g = baseGoodsMapper.selectById(goodsId);
        if (g == null) {
            throw BusinessException.validateFail("成品不存在");
        }
        if (!GoodsService.GOODS_TYPE_PRODUCT.equalsIgnoreCase(g.getType())) {
            throw BusinessException.validateFail("生产任务单只能下达给成品（type=product），所选货品不是成品");
        }
        if (g.getStatus() != null && g.getStatus() != 1) {
            throw BusinessException.validateFail("成品已停用，无法下达生产任务单");
        }
        return g;
    }

    private BizBom requireBomOfProduct(Long goodsId) {
        LambdaQueryWrapper<BizBom> bw = new LambdaQueryWrapper<>();
        bw.eq(BizBom::getGoodsId, goodsId);
        BizBom bom = bomMapper.selectOne(bw);
        if (bom == null) {
            throw BusinessException.validateFail("该成品尚未建立 BOM，无法下达生产任务单");
        }
        return bom;
    }

    private BizProductionOrder requireOrder(Long id) {
        BizProductionOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        return order;
    }

    private void ensureStatus(BizProductionOrder order, int expected, String msg) {
        if (order.getStatus() == null || order.getStatus() != expected) {
            throw BusinessException.validateFail(msg);
        }
    }

    private BaseGoods requireGoodsNoStatus(Long id) {
        BaseGoods g = baseGoodsMapper.selectById(id);
        if (g == null) {
            throw BusinessException.validateFail("物料不存在");
        }
        return g;
    }

    /**
     * 开工前置校验：该生产单存在领料单且已全额出库/完成。
     * 用自有 pickListMapper 查询，避免反向依赖 ProductionPickService（防循环依赖）。
     */
    private boolean isPickAllIssued(Long productionOrderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        // D105/review：只看发料类单据（领料/补料）；退料单（含待发料/已驳回/已回流）一律不参与齐套判定，
        // 否则开工后的退料会让已齐套单回落实时重算显示假缺料、退料被驳回则终生假缺料
        w.eq(BizPickList::getProductionOrderId, productionOrderId)
                .in(BizPickList::getPickType, PickListService.TYPE_PICK, PickListService.TYPE_SUPPLY);
        List<BizPickList> picks = pickListMapper.selectList(w);
        if (picks.isEmpty()) {
            return false;
        }
        return picks.stream().allMatch(p ->
                PickListService.STATUS_ISSUED == p.getStatus()
                        || PickListService.STATUS_DONE == p.getStatus());
    }

    /**
     * D105：批量判定各生产单领料单是否已全额出库（与 isPickAllIssued 同口径：
     * 存在领料单且全部已发料/已完成），供列表统一齐套展示口径。
     */
    private Map<Long, Boolean> pickIssuedMap(List<BizProductionOrder> orders) {
        List<Long> ids = orders.stream().map(BizProductionOrder::getId).toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        // D105/review：与 isPickAllIssued 同口径，退料单（RETURN）不参与
        w.in(BizPickList::getProductionOrderId, ids)
                .in(BizPickList::getPickType, PickListService.TYPE_PICK, PickListService.TYPE_SUPPLY);
        Map<Long, List<BizPickList>> byOrder = pickListMapper.selectList(w).stream()
                .collect(Collectors.groupingBy(BizPickList::getProductionOrderId));
        Map<Long, Boolean> result = new java.util.HashMap<>();
        for (Long id : ids) {
            List<BizPickList> picks = byOrder.get(id);
            result.put(id, picks != null && !picks.isEmpty() && picks.stream().allMatch(p ->
                    PickListService.STATUS_ISSUED == p.getStatus()
                            || PickListService.STATUS_DONE == p.getStatus()));
        }
        return result;
    }

    /**
     * D105/D116：列表齐套展示口径——领料已全额出库→「齐套领料」（kitLines 置空）；
     * 未开工（状态 1）且未发料 → 用实时结果覆盖建单快照（D116）；
     * 状态 2/3 或实时结果缺失（BOM 已删等）→ 保持快照；≥4 终态隐藏标签。
     */
    private void applyKitDisplay(ProductionOrderVO vo, BizProductionOrder order, boolean pickIssued,
                                 KuaiTaoResult realtimeKit) {
        Integer status = order.getStatus();
        if (status != null && (status == BizProductionOrder.STATUS_PENDING
                || status == BizProductionOrder.STATUS_IN_PROGRESS
                || status == BizProductionOrder.STATUS_AWAIT_QC)) {
            if (pickIssued) {
                vo.setKitStatus(BizProductionOrder.KIT_ISSUED);
                vo.setKitStatusText(kitText(BizProductionOrder.KIT_ISSUED));
                vo.setKitLines(List.of());
            } else if (status == BizProductionOrder.STATUS_PENDING && realtimeKit != null) {
                vo.setKitStatus(realtimeKit.kitStatus);
                vo.setKitStatusText(kitText(realtimeKit.kitStatus));
                vo.setKitLines(realtimeKit.lines);
            }
        } else {
            vo.setKitStatus(null);
            vo.setKitStatusText("");
        }
    }

    /**
     * D107：列表批量回填待确认入库申请 id/单号（仅待入库行）——一次 in 查询，
     * 供列表行内「提交入库申请/撤销申请」按钮与详情同口径显隐；
     * 撤销确认弹窗文案需要 pendingInboundNo（review 补强：原先只填 id，弹窗单号显示 undefined）。
     */
    private void fillPendingInboundIdBatch(List<ProductionOrderVO> records, List<BizProductionOrder> orders) {
        List<Long> awaitIds = orders.stream()
                .filter(o -> Integer.valueOf(BizProductionOrder.STATUS_AWAIT_QC).equals(o.getStatus()))
                .map(BizProductionOrder::getId).toList();
        if (awaitIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizProduction> w = new LambdaQueryWrapper<>();
        w.in(BizProduction::getProductionOrderId, awaitIds)
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .eq(BizProduction::getBizStatus, 1);
        Map<Long, BizProduction> pendingByOrder = productionMapper.selectList(w).stream()
                .collect(Collectors.toMap(BizProduction::getProductionOrderId, p -> p, (a, b) -> a));
        for (ProductionOrderVO vo : records) {
            BizProduction pending = pendingByOrder.get(vo.getId());
            if (pending != null) {
                vo.setPendingInboundId(pending.getId());
                vo.setPendingInboundNo(pending.getProductionNo());
            }
        }
    }

    /** D107：该生产单当前待仓储确认的入库申请（receipt 守卫保证至多一笔）；无则 null */
    private BizProduction findPendingInbound(Long orderId) {
        LambdaQueryWrapper<BizProduction> w = new LambdaQueryWrapper<>();
        w.eq(BizProduction::getProductionOrderId, orderId)
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .eq(BizProduction::getBizStatus, 1);
        List<BizProduction> list = productionMapper.selectList(w);
        return list.isEmpty() ? null : list.get(0);
    }

    /** D107：填充入库申请状态——待确认单信息（待仓储确认）或最近一笔被驳回的申请原因（提示重新提交） */
    private void fillInboundApplicationInfo(ProductionOrderVO vo, BizProductionOrder order) {
        if (order.getStatus() == null || order.getStatus() != BizProductionOrder.STATUS_AWAIT_QC) {
            return;
        }
        BizProduction pending = findPendingInbound(order.getId());
        if (pending != null) {
            vo.setPendingInboundId(pending.getId());
            vo.setPendingInboundNo(pending.getProductionNo());
            vo.setPendingInboundTime(pending.getOperationTime());
            vo.setPendingInboundOperator(pending.getOperatorName());
            return;
        }
        LambdaQueryWrapper<BizProduction> w = new LambdaQueryWrapper<>();
        w.eq(BizProduction::getProductionOrderId, order.getId())
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_REJECTED)
                .orderByDesc(BizProduction::getId)
                .last("LIMIT 1");
        BizProduction rejected = productionMapper.selectOne(w);
        if (rejected != null) {
            vo.setLastInboundRejectNo(rejected.getProductionNo());
            vo.setLastInboundRejectReason(rejected.getRejectReason());
        }
    }

    private void increaseStock(BaseGoods goods, int qty, String msg) {
        if (goods.getStock() == null) {
            goods.setStock(0);
        }
        goods.setStock(goods.getStock() + qty);
        baseGoodsMapper.updateById(goods);
    }

    private String patchRemark(String oldRemark, String reason) {
        String reasonText = StringUtils.hasText(reason) ? reason : "无";
        if (!StringUtils.hasText(oldRemark)) {
            return "作废原因: " + reasonText;
        }
        return oldRemark + " | 作废原因: " + reasonText;
    }

    // ============================== VO ==============================

    private ProductionOrderVO toVO(BizProductionOrder order) {
        ProductionOrderVO vo = new ProductionOrderVO();
        BeanUtils.copyProperties(order, vo);
        vo.setStatusText(statusText(order.getStatus()));
        vo.setKitStatusText(kitText(order.getKitStatus()));
        vo.setProcessList(StringUtils.hasText(order.getProcessSnapshot())
                ? List.of(order.getProcessSnapshot().split("\n"))
                : List.of());
        return vo;
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

    private String kitText(String kit) {
        if (!StringUtils.hasText(kit)) {
            return "";
        }
        return switch (kit) {
            case BizProductionOrder.KIT_OK -> "齐套";
            case BizProductionOrder.KIT_ISSUED -> "齐套领料";
            case BizProductionOrder.KIT_PARTIAL -> "部分缺料";
            case BizProductionOrder.KIT_BLOCK -> "严重缺料";
            default -> kit;
        };
    }
}