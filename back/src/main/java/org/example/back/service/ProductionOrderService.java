package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
        List<ProductionOrderVO> records = new ArrayList<>(page.getRecords().size());
        for (BizProductionOrder order : page.getRecords()) {
            ProductionOrderVO vo = toVO(order);
            vo.setQcState(qcStates.get(order.getId()));
            records.add(vo);
        }
        fillSalesOrderNoBatch(records);
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ProductionOrderVO getById(Long id) {
        requireOrderReadAccess();
        BizProductionOrder order = requireOrder(id);
        ProductionOrderVO vo = toVO(order);
        // 待生产/生产中实时重算齐套（反映当前库存，开工后已发料部分会显示为缺口）
        if (order.getStatus() == BizProductionOrder.STATUS_PENDING
                || order.getStatus() == BizProductionOrder.STATUS_IN_PROGRESS) {
            KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
            vo.setKitLines(kit.lines);
            vo.setKitStatus(kit.kitStatus);
            vo.setKitStatusText(kitText(kit.kitStatus));
        }
        // 生产/待入库/已报废阶段展示质检状态
        if (order.getStatus() == BizProductionOrder.STATUS_IN_PROGRESS
                || order.getStatus() == BizProductionOrder.STATUS_AWAIT_QC
                || order.getStatus() == BizProductionOrder.STATUS_SCRAPPED) {
            vo.setQcState(qcService.buildState(order));
        }
        // D64：10 道工序行（人工行读步骤实例，第 6/8/10 道实时推导；历史单无实例返回 null，前端回落快照文字）
        vo.setStepList(productionStepService.listSteps(order));
        fillSalesOrderNoBatch(List.of(vo));
        return vo;
    }

    // ============================== 建单（含齐套预警） ==============================

    @Transactional(rollbackFor = Exception.class)
    public ProductionOrderVO create(ProductionOrderSaveDTO dto) {
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
     * 生产入库（阶段13）：质检合格进入待入库后，生产端点击入库 → 成品库存增加 + 记录生产入库交易 + 订单已完成。
     * 取代生产端管理员在仓储自由建"生产入库"单的方式，改由生产订单驱动、质检前置把关。
     */
    @Transactional(rollbackFor = Exception.class)
    public void receipt(Long id) {
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        if (order.getStatus() != BizProductionOrder.STATUS_AWAIT_QC) {
            throw BusinessException.validateFail("仅待入库状态可生产入库，请先完成质检");
        }
        // 质检前置校验：首测+成品测最新均 OK 且未报废
        qcService.ensurePassedForReceipt(id);

        BaseGoods product = requireGoodsNoStatus(order.getGoodsId());
        increaseStock(product, order.getQuantity(), "生产入库[" + order.getOrderNo() + "]成品库存不足");

        // 记录一笔生产入库交易（审计；仓储端"生产入库"列表可见）
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizProduction in = new BizProduction();
        in.setProductionNo(CodeGenerator.productionNo());
        in.setGoodsId(product.getId());
        in.setGoodsName(product.getGoodsName());
        in.setQuantity(order.getQuantity());
        in.setOperatorId(user.getId());
        in.setOperatorName(user.getRealName());
        in.setOperationTime(LocalDateTime.now());
        in.setBizStatus(1);
        in.setRemark("生产任务单 " + order.getOrderNo() + " 完工入库");
        productionMapper.insert(in);

        order.setStatus(BizProductionOrder.STATUS_DONE);
        orderMapper.updateById(order);

        // D73：订单终态（已完成）——撤销该单未读待办（含"关联销售单已取消"等绑 production_order 的消息）
        messageService.revokeUnreadByBiz("production_order", id);

        // D70：关联销售单仍待出库 → 通知建单销售本人"可发货"（biz 绑定销售单，出库/作废撤未读）
        notifySalesReadyToShipIfLinked(order);
    }

    /**
     * D71：生产手工修正预计完工时间（仅未完结单；留痕走 Controller 层 @AuditLog）。
     * 手工值优先于系统推算；传 null 视为清除手工值（恢复系统推算）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateExpectedCompletion(Long id, LocalDateTime expectedCompletionTime) {
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
        w.eq(BizPickList::getProductionOrderId, productionOrderId);
        List<BizPickList> picks = pickListMapper.selectList(w);
        if (picks.isEmpty()) {
            return false;
        }
        return picks.stream().allMatch(p ->
                PickListService.STATUS_ISSUED == p.getStatus()
                        || PickListService.STATUS_DONE == p.getStatus());
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
            case BizProductionOrder.KIT_PARTIAL -> "部分缺料";
            case BizProductionOrder.KIT_BLOCK -> "严重缺料";
            default -> kit;
        };
    }
}