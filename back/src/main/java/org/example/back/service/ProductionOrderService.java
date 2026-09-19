package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionOrderQueryDTO;
import org.example.back.dto.ProductionOrderSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionOrderVO;
import org.example.back.vo.ProductionPickItemVO;
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
import java.util.List;
import java.util.Map;
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
        Page<BizProductionOrder> page = orderMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        // D64 质检进度列表修复：列表行批量填充 qcState（一次 in 查询分组推导），QcView 列表不再恒显"未测"
        Map<Long, QcStateVO> qcStates = qcService.buildStateBatch(page.getRecords());
        // D105：批量判定领料单是否已全额出库，列表齐套口径与详情统一
        Map<Long, Boolean> pickIssuedMap = pickIssuedMap(page.getRecords());
        List<ProductionOrderVO> records = new ArrayList<>(page.getRecords().size());
        for (BizProductionOrder order : page.getRecords()) {
            ProductionOrderVO vo = toVO(order);
            vo.setQcState(qcStates.get(order.getId()));
            applyKitDisplay(vo, order, pickIssuedMap.getOrDefault(order.getId(), false));
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
        // D70：选填关联销售单（须同成品、正常且待出库）；通用备货单留空
        BizSales linkedSales = requireLinkableSalesOrder(dto.getSalesOrderId(), product.getId());

        BizProductionOrder order = new BizProductionOrder();
        order.setOrderNo(CodeGenerator.productionNo());
        order.setGoodsId(product.getId());
        order.setGoodsName(product.getGoodsName());
        order.setUnit(product.getUnit());
        order.setQuantity(dto.getQuantity());
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        order.setKitStatus(kit.kitStatus);
        order.setSource(BizProductionOrder.SOURCE_MANUAL);
        order.setSalesOrderId(linkedSales == null ? null : linkedSales.getId());
        order.setProcessSnapshot(String.join("\n", ProductionStepService.PROCESS_STEPS));
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);
        BizProductionOrder saved = orderMapper.selectById(order.getId());
        // D64：初始化 7 道人工工序实例行
        productionStepService.initStepsForOrder(saved.getId());

        // D60：建单即齐套预警——存在严重缺料/未知物料时通知采购管理员（作废时由 voidOrder 撤未读）
        if (BizProductionOrder.KIT_BLOCK.equals(kit.kitStatus)) {
            messageService.sendKitShortageToPurchaseAdmins(
                    saved.getOrderNo(), product.getGoodsName(), kit.summary(dto.getQuantity()), saved.getId());
        }

        ProductionOrderVO vo = toVO(saved);
        vo.setKitLines(kit.lines);
        return vo;
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
        order.setStatus(BizProductionOrder.STATUS_DONE);
        orderMapper.updateById(order);
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
        KuaiTaoResult result = new KuaiTaoResult();

        // D4X（方案先行）：BOM 明细只要真需求(非参考行、有组件名)就计入齐套；未关联物料的明细行按"缺料待采购"处理(库存0)——不再丢弃。
        // 生产研发先定 BOM 方案，物料可后补建档并回挂 goodsId；回挂并入库存后该行即正常参与齐套。
        LambdaQueryWrapper<BizBomDetail> dw = new LambdaQueryWrapper<>();
        dw.eq(BizBomDetail::getBomId, bom.getId())
                .eq(BizBomDetail::getIsReference, 0)
                .orderByAsc(BizBomDetail::getSortNo);
        List<BizBomDetail> details = bomDetailMapper.selectList(dw).stream()
                .filter(d -> d.getComponentName() != null && !d.getComponentName().trim().isEmpty())
                .toList();
        if (details.isEmpty()) {
            result.kitStatus = BizProductionOrder.KIT_OK;
            return result;
        }

        for (BizBomDetail d : details) {
            String name = d.getComponentName();
            // goodsId 空 = 物料未在仓库建档（方案先行待采购），按库存 0 的严重缺料处理
            BaseGoods g = d.getGoodsId() == null ? null : baseGoodsMapper.selectById(d.getGoodsId());
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
                result.hasShortage = true;
            } else if (stock >= required.intValue()) {
                line.setLineStatus("ok");
                line.setLineStatusText("齐套");
            } else if (stock > 0) {
                line.setLineStatus("partial");
                line.setLineStatusText("部分缺料");
                result.hasShortage = true;
            } else {
                line.setLineStatus("block");
                line.setLineStatusText("严重缺料");
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

    /** D70：校验可关联的销售单——存在、正常（未作废）、待出库、同成品；null 表示不关联（备货单） */
    private BizSales requireLinkableSalesOrder(Long salesOrderId, Long goodsId) {
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
        if (!goodsId.equals(sales.getGoodsId())) {
            throw BusinessException.validateFail("关联销售单的成品与本任务单不一致");
        }
        return sales;
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
                sales.getOperatorId(), sales.getSalesNo(), order.getGoodsName(), sales.getQuantity(), sales.getId());
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
     * D105：统一齐套展示口径——领料已全额出库→「齐套领料」（kitLines 置空，缺口已无意义）；
     * 已完成/作废/报废/终止→不再显示标签；未领料的列表行保持建单快照（详情才实时重算，避免逐行展开 BOM）。
     */
    private void applyKitDisplay(ProductionOrderVO vo, BizProductionOrder order, boolean pickIssued) {
        Integer status = order.getStatus();
        if (status != null && (status == BizProductionOrder.STATUS_PENDING
                || status == BizProductionOrder.STATUS_IN_PROGRESS
                || status == BizProductionOrder.STATUS_AWAIT_QC)) {
            if (pickIssued) {
                vo.setKitStatus(BizProductionOrder.KIT_ISSUED);
                vo.setKitStatusText(kitText(BizProductionOrder.KIT_ISSUED));
                vo.setKitLines(List.of());
            }
        } else {
            vo.setKitStatus(null);
            vo.setKitStatusText("");
        }
    }

    /**
     * D107：列表批量回填待确认入库申请 id（仅待入库行）——一次 in 查询，
     * 供列表行内「提交入库申请/撤销申请」按钮与详情同口径显隐。
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
        Map<Long, Long> pendingByOrder = productionMapper.selectList(w).stream()
                .collect(Collectors.toMap(BizProduction::getProductionOrderId, BizProduction::getId, (a, b) -> a));
        for (ProductionOrderVO vo : records) {
            Long pendingId = pendingByOrder.get(vo.getId());
            if (pendingId != null) {
                vo.setPendingInboundId(pendingId);
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