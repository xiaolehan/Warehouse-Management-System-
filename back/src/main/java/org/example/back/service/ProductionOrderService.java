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
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.ProductionOrderVO;
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
import java.util.stream.Collectors;

/**
 * 生产任务单 + 齐套预警（D42/D43）。
 * 建单选成品×数量 → 展开 BOM 算需求 vs 库存 → ok/partial/block；
 * 开工前重查，严重缺料阻断；开工自动按 BOM×数量生成领料单并扣库存（避免漏领）。
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
    private BizBomMapper bomMapper;

    @Autowired
    private BizBomDetailMapper bomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizPickListMapper pickListMapper;

    @Autowired
    private BizPickListDetailMapper pickListDetailMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    // D40 8 道装配工序静态 SOP（首测/成品测两项测试工序走质检记录，不在此列）
    private static final String[] DEFAULT_PROCESS_STEPS = {
        "备料核对（对照BOM领料清点）",
        "部件装配组立",
        "线材/油管连接",
        "紧固与扭矩校验",
        "润滑注油",
        "外观与装配检查",
        "功能调试",
        "清洁与包装"
    };

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
        List<ProductionOrderVO> records = page.getRecords().stream().map(this::toVO).toList();
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
        return vo;
    }

    // ============================== 建单（含齐套预警） ==============================

    @Transactional(rollbackFor = Exception.class)
    public ProductionOrderVO create(ProductionOrderSaveDTO dto) {
        requireOrderWriteAccess();
        BaseGoods product = requireProduct(dto.getGoodsId());
        KuaiTaoResult kit = computeKit(dto.getGoodsId(), dto.getQuantity());

        BizProductionOrder order = new BizProductionOrder();
        order.setOrderNo(CodeGenerator.productionNo());
        order.setGoodsId(product.getId());
        order.setGoodsName(product.getGoodsName());
        order.setUnit(product.getUnit());
        order.setQuantity(dto.getQuantity());
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        order.setKitStatus(kit.kitStatus);
        order.setSource(BizProductionOrder.SOURCE_MANUAL);
        order.setProcessSnapshot(String.join("\n", DEFAULT_PROCESS_STEPS));
        order.setRemark(dto.getRemark());
        orderMapper.insert(order);
        BizProductionOrder saved = orderMapper.selectById(order.getId());

        // 有缺口即通知采购管理员补料（D42）
        if (kit.hasShortage) {
            messageService.sendKitShortageToPurchaseAdmins(
                    saved.getOrderNo(),
                    saved.getGoodsName(),
                    kit.summary(saved.getQuantity()),
                    saved.getId()
            );
        }
        ProductionOrderVO vo = toVO(saved);
        vo.setKitLines(kit.lines);
        return vo;
    }

    /**
     * 开工：重查齐套，严重缺料阻断；否则自动按 BOM×数量生成领料单并扣库存，进入生产中。
     */
    @Transactional(rollbackFor = Exception.class)
    public void start(Long id) {
        requireOrderExecuteAccess();
        BizProductionOrder order = requireOrder(id);
        ensureStatus(order, BizProductionOrder.STATUS_PENDING, "仅待生产状态可开工");

        KuaiTaoResult kit = computeKit(order.getGoodsId(), order.getQuantity());
        if (BizProductionOrder.KIT_BLOCK.equals(kit.kitStatus)) {
            throw BusinessException.validateFail("存在严重缺料（库存为零/缺口物料），无法开工，请采购补齐后重试");
        }
        generatePickListAndIssue(order, kit);

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
                    .map(l -> l.getGoodsName() + " 需" + l.getRequired().stripTrailingZeros().toPlainString()
                            + " 库" + l.getStock() + " 缺" + l.getDeficit().stripTrailingZeros().toPlainString())
                    .collect(Collectors.joining("；"));
        }
    }

    /** 展开成品 BOM×数量：算每项物料的 需求 vs 库存 与匹配等级 */
    private KuaiTaoResult computeKit(Long goodsId, int quantity) {
        BizBom bom = requireBomOfProduct(goodsId);
        KuaiTaoResult result = new KuaiTaoResult();

        LambdaQueryWrapper<BizBomDetail> dw = new LambdaQueryWrapper<>();
        dw.eq(BizBomDetail::getBomId, bom.getId())
                .eq(BizBomDetail::getIsReference, 0)
                .ne(BizBomDetail::getGoodsId, 0)
                .isNotNull(BizBomDetail::getGoodsId)
                .orderByAsc(BizBomDetail::getSortNo);
        List<BizBomDetail> details = bomDetailMapper.selectList(dw);
        if (details.isEmpty()) {
            result.kitStatus = BizProductionOrder.KIT_OK;
            return result;
        }

        for (BizBomDetail d : details) {
            BaseGoods g = baseGoodsMapper.selectById(d.getGoodsId());
            if (g == null) {
                continue;
            }
            BigDecimal usage = d.getQuantity() == null ? BigDecimal.ONE : d.getQuantity();
            BigDecimal required = usage.multiply(BigDecimal.valueOf(quantity)).setScale(4, RoundingMode.HALF_UP);
            int stock = g.getStock() == null ? 0 : g.getStock();

            KitShortageVO line = new KitShortageVO();
            line.setGoodsId(g.getId());
            line.setGoodsName(g.getGoodsName());
            line.setUnit(g.getUnit());
            line.setUnitUsage(usage);
            line.setRequired(required);
            line.setStock(stock);
            line.setDeficit(required.subtract(BigDecimal.valueOf(stock)).setScale(4, RoundingMode.HALF_UP));

            if (stock >= required.intValue()) {
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
        // 汇总等级：有严重缺 → block；否则有部分缺 → partial
        boolean anyBlock = result.lines.stream().anyMatch(l -> "block".equals(l.getLineStatus()));
        if (anyBlock) {
            result.kitStatus = BizProductionOrder.KIT_BLOCK;
        } else if (result.hasShortage) {
            result.kitStatus = BizProductionOrder.KIT_PARTIAL;
        }
        return result;
    }

    /** 按成品 BOM 生成领料单并直接发料扣库存（D43 自动防漏领） */
    private void generatePickListAndIssue(BizProductionOrder order, KuaiTaoResult kit) {
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        // 只对有库存、需求>0 的物料发料（缺料部分留待采购补料后再补领）
        List<KitShortageVO> issuable = kit.lines.stream()
                .filter(l -> l.getStock() != null && l.getStock() > 0
                        && l.getRequired().intValue() > 0)
                .toList();
        if (issuable.isEmpty()) {
            throw BusinessException.validateFail("无可用物料可发料，无法开工");
        }

        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_ISSUED);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setOperatorId(user.getId());
        pick.setOperatorName(user.getRealName());
        pick.setOperationTime(LocalDateTime.now());
        pick.setRemark("生产任务单 " + order.getOrderNo() + " 自动领料");
        pickListMapper.insert(pick);

        int sortNo = 0;
        for (KitShortageVO line : issuable) {
            int requiredInt = line.getRequired().setScale(0, RoundingMode.DOWN).intValue();
            int issueQty = Math.min(requiredInt, line.getStock());
            if (issueQty <= 0) {
                continue;
            }
            BaseGoods g = requireGoodsNoStatus(line.getGoodsId());
            reduceStock(g, issueQty, "生产任务单[" + order.getOrderNo() + "]自动领料");

            BizPickListDetail detail = new BizPickListDetail();
            detail.setPickListId(pick.getId());
            detail.setGoodsId(g.getId());
            detail.setGoodsName(g.getGoodsName());
            detail.setQuantity(issueQty);
            detail.setSortNo(sortNo++);
            pickListDetailMapper.insert(detail);
        }
    }

    // ============================== 私有：校验与工具 ==============================

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

    private void reduceStock(BaseGoods goods, int qty, String msg) {
        if (goods.getStock() == null || goods.getStock() < qty) {
            throw BusinessException.validateFail(msg);
        }
        goods.setStock(goods.getStock() - qty);
        baseGoodsMapper.updateById(goods);
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