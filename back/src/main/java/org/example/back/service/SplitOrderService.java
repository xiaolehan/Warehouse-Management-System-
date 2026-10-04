package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.util.CodeGenerator;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.SplitOrderCreateDTO;
import org.example.back.dto.SplitOrderQueryDTO;
import org.example.back.dto.SplitReturnSubmitDTO;
import org.example.back.dto.SplitOrderVoidDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.entity.BizSplitOrder;
import org.example.back.entity.BizSplitOrderDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSplitOrderDetailMapper;
import org.example.back.mapper.BizSplitOrderMapper;
import org.example.back.vo.SplitOrderDetailVO;
import org.example.back.vo.SplitOrderVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 成品拆分单（ADR-0020，D116）：销售明细行终止后「已入库未出库」成品的跨部门拆解处置。
 * 状态机：1待生产领取 → 2待仓储确认成品出库 → 3待生产确认收货 → 4拆分中 → 5已完成；
 * 旁支：7待仓储确认成品回库（生产放弃拆分，仓储确认才加库存）→ 5（完成方式=放弃回库）；
 * 6已作废（仓储，仅限未动库存的 1/2 态）。
 * 物料回流复用领料单模块 RETURN 类型（production_order_id 置空 + split_order_id 关联）；
 * 库存变更走本模块私有 increaseStock/decreaseStock（CLAUDE.md 口径：各业务模块各一份）。
 * 依赖方向：PickListService → 本类（回调 onSplitReturn*）；本类直用 BizPickListMapper，不反向注入 PickListService。
 */
@Service
public class SplitOrderService {

    @Autowired
    private BizSplitOrderMapper splitOrderMapper;

    @Autowired
    private BizSplitOrderDetailMapper splitOrderDetailMapper;

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

    @Autowired
    private BizPickListMapper bizPickListMapper;

    @Autowired
    private BizPickListDetailMapper bizPickListDetailMapper;

    @Autowired
    private BizBomMapper bomMapper;

    @Autowired
    private BizBomDetailMapper bomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    // ============================== 查询 ==============================

    public PageResult<SplitOrderVO> page(SplitOrderQueryDTO queryDTO) {
        requireReadAccess();
        LambdaQueryWrapper<BizSplitOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getSplitNo()), BizSplitOrder::getSplitNo, queryDTO.getSplitNo())
                .like(StringUtils.hasText(queryDTO.getSalesOrderNo()), BizSplitOrder::getSalesOrderNo, queryDTO.getSalesOrderNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizSplitOrder::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getStatus() != null, BizSplitOrder::getStatus, queryDTO.getStatus())
                .orderByDesc(BizSplitOrder::getId);
        Page<BizSplitOrder> page = splitOrderMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<SplitOrderVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SplitOrderVO getById(Long id) {
        requireReadAccess();
        return toVO(requireEntity(id));
    }

    // ============================== 仓储发起拆分 ==============================

    /**
     * 仓储发起成品拆分（行终止 + 已入库未出库量>0 的处置之二）：
     * 拆分数量默认=已入库未出库量、允许部分拆分；同一行同时只允许一张在途拆分单（状态 1/2/3/4/7）。
     * 发起时定格 BOM×拆分数量快照；行处置置为「已发起拆分」并撤销行处置待办，通知生产领取。
     */
    @Transactional(rollbackFor = Exception.class)
    public void create(SplitOrderCreateDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAdmin("仅仓储管理员可发起成品拆分");
        if (dto.getQuantity() == null || dto.getQuantity() <= 0) {
            throw BusinessException.validateFail("拆分数量必须大于0");
        }
        BizSalesDetail detail = bizSalesDetailMapper.selectById(dto.getSalesDetailId());
        if (detail == null) {
            throw BusinessException.notFound("销售明细行不存在");
        }
        BizSales sales = bizSalesMapper.selectById(detail.getSalesId());
        if (sales == null) {
            throw BusinessException.notFound("关联销售单不存在");
        }
        if (!isLineTerminated(detail)) {
            throw BusinessException.validateFail("仅已终止的明细行可发起成品拆分");
        }
        if (sales.getConfirmStatus() != null && sales.getConfirmStatus() == SalesService.CONFIRM_SHIPPED) {
            throw BusinessException.validateFail("销售单已确认出库，无需拆分处置");
        }
        if (hasActiveSplit(detail.getId())) {
            throw BusinessException.validateFail("该明细行已有在途成品拆分单，请先处理完成");
        }
        int unshipped = unshippedInboundQty(sales, detail);
        if (unshipped <= 0) {
            throw BusinessException.validateFail("该明细行没有已入库未出库的成品，无需拆分处置");
        }
        if (dto.getQuantity() > unshipped) {
            throw BusinessException.validateFail(
                    "拆分数量不能超过已入库未出库量（当前 " + unshipped + "）");
        }

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        BizSplitOrder split = new BizSplitOrder();
        split.setSplitNo(CodeGenerator.splitOrderNo());
        split.setSalesOrderId(sales.getId());
        split.setSalesOrderNo(sales.getSalesNo());
        split.setSalesDetailId(detail.getId());
        split.setGoodsId(detail.getGoodsId());
        split.setGoodsName(detail.getGoodsName());
        split.setSpec(specOfGoods(detail.getGoodsId()));
        split.setQuantity(dto.getQuantity());
        split.setStatus(BizSplitOrder.STATUS_PENDING_CLAIM);
        split.setInitiatorId(user.getId());
        split.setInitiatorName(user.getRealName());
        split.setInitTime(now);
        splitOrderMapper.insert(split);
        insertBomSnapshot(split);

        // 行处置留痕：已发起拆分 + 关联拆分单；撤销行处置待办（D21）
        LambdaUpdateWrapper<BizSalesDetail> lineWrapper = new LambdaUpdateWrapper<>();
        lineWrapper.eq(BizSalesDetail::getId, detail.getId())
                .set(BizSalesDetail::getSplitStatus, BizSalesDetail.SPLIT_INITIATED)
                .set(BizSalesDetail::getSplitOrderId, split.getId());
        bizSalesDetailMapper.update(null, lineWrapper);
        messageService.revokeUnreadByBiz("sales_detail_split", detail.getId());

        messageService.sendSplitOrderCreatedToProductionAdmins(
                split.getSplitNo(), split.getGoodsName(), split.getQuantity(),
                split.getSalesOrderNo(), split.getId());
    }

    // ============================== 生产领取 ==============================

    /** 生产领取（1→2）：生产部门成员可领取；领取后仓储确认成品出库。 */
    @Transactional(rollbackFor = Exception.class)
    public void claim(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionMember("仅生产部门可领取拆分任务");
        BizSplitOrder split = requireEntity(id);
        if (split.getStatus() != BizSplitOrder.STATUS_PENDING_CLAIM) {
            throw BusinessException.validateFail("仅待生产领取状态可领取");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_CLAIM)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_OUTBOUND)
                .set(BizSplitOrder::getClaimUserId, user.getId())
                .set(BizSplitOrder::getClaimUserName, user.getRealName())
                .set(BizSplitOrder::getClaimTime, LocalDateTime.now());
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被领取，请刷新后重试");
        }
        messageService.sendSplitOrderClaimedToWarehouseAdmins(split.getSplitNo(), user.getRealName(), id);
    }

    // ============================== 仓储确认成品出库 ==============================

    /** 仓储确认成品出库（2→3）：成品库存扣减；通知生产领取人确认收货。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmOutbound(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAdmin("仅仓储管理员可确认成品出库");
        BizSplitOrder split = requireEntity(id);
        if (split.getStatus() != BizSplitOrder.STATUS_PENDING_OUTBOUND) {
            throw BusinessException.validateFail("仅待仓储确认成品出库状态可确认出库");
        }
        decreaseStock(split.getGoodsId(), split.getQuantity(),
                "商品[" + split.getGoodsName() + "]库存不足，成品出库失败");
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_OUTBOUND)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_RECEIPT)
                .set(BizSplitOrder::getOutboundConfirmUserId, user.getId())
                .set(BizSplitOrder::getOutboundConfirmUserName, user.getRealName())
                .set(BizSplitOrder::getOutboundConfirmTime, LocalDateTime.now());
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
        messageService.sendSplitOrderOutboundConfirmedToProduction(
                split.getClaimUserId(), split.getSplitNo(), id);
    }

    // ============================== 生产确认收货 ==============================

    /** 生产确认收货（3→4）：领取人本人或生产管理员；收到成品后开始拆分。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmReceipt(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        BizSplitOrder split = requireEntity(id);
        requireProductionSideAccess(split, "仅领取人本人或生产管理员可确认收货");
        if (split.getStatus() != BizSplitOrder.STATUS_PENDING_RECEIPT) {
            throw BusinessException.validateFail("仅待生产确认收货状态可确认收货");
        }
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_RECEIPT)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_SPLITTING)
                .set(BizSplitOrder::getReceiptConfirmUserId, user.getId())
                .set(BizSplitOrder::getReceiptConfirmUserName, user.getRealName())
                .set(BizSplitOrder::getReceiptConfirmTime, LocalDateTime.now());
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
    }

    // ============================== 生产放弃拆分 ==============================

    /**
     * 生产放弃拆分：1/2 态（未动库存）→ 直接完成（完成方式=放弃回库，成品从未出库）；
     * 3/4 态（成品已出库在生产端）→ 7 待仓储确认成品回库（仓储确认才加库存）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void abandon(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        BizSplitOrder split = requireEntity(id);
        requireProductionSideAccess(split, "仅领取人本人或生产管理员可放弃拆分");
        int status = split.getStatus();
        if (status != BizSplitOrder.STATUS_PENDING_CLAIM
                && status != BizSplitOrder.STATUS_PENDING_OUTBOUND
                && status != BizSplitOrder.STATUS_PENDING_RECEIPT
                && status != BizSplitOrder.STATUS_SPLITTING) {
            throw BusinessException.validateFail("当前状态不可放弃拆分");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        if (status == BizSplitOrder.STATUS_PENDING_RECEIPT
                || status == BizSplitOrder.STATUS_SPLITTING) {
            // 成品已出库在生产端：转 7 待仓储确认回库
            LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(BizSplitOrder::getId, id)
                    .eq(BizSplitOrder::getStatus, status)
                    .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_RESTOCK)
                    .set(BizSplitOrder::getAbandonUserId, user.getId())
                    .set(BizSplitOrder::getAbandonUserName, user.getRealName())
                    .set(BizSplitOrder::getAbandonTime, now);
            if (splitOrderMapper.update(null, wrapper) != 1) {
                throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
            }
            messageService.sendSplitOrderAbandonedToWarehouseAdmins(split.getSplitNo(), user.getRealName(), id);
            return;
        }
        // 1/2 态：未动库存，直接完成（完成方式=放弃回库）
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, status)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_DONE)
                .set(BizSplitOrder::getFinishType, BizSplitOrder.FINISH_TYPE_ABANDON)
                .set(BizSplitOrder::getAbandonUserId, user.getId())
                .set(BizSplitOrder::getAbandonUserName, user.getRealName())
                .set(BizSplitOrder::getAbandonTime, now);
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
        recalcDispositionAfterSplit(split.getSalesDetailId());
    }

    // ============================== 生产提交拆分退料（RETURN） ==============================

    /**
     * 拆分中（状态4）提交 RETURN 退料单：复用领料单模块（production_order_id 置空 + split_order_id 关联）；
     * 行按 BOM 快照预填、生产按实际退回量提交（差异走 Q16 差异备注）；仓储收料确认后回调完成。
     */
    @Transactional(rollbackFor = Exception.class)
    public void submitReturn(Long id, SplitReturnSubmitDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        BizSplitOrder split = requireEntity(id);
        requireProductionSideAccess(split, "仅领取人本人或生产管理员可提交拆分退料");
        if (split.getStatus() != BizSplitOrder.STATUS_SPLITTING) {
            throw BusinessException.validateFail("仅拆分中状态可提交退料");
        }
        if (split.getReturnPickListId() != null) {
            throw BusinessException.validateFail("该拆分单已有待确认的退料单，请等待仓储确认或驳回后重提");
        }
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请填写退料明细");
        }
        List<BizSplitOrderDetail> snapshot = listDetails(id);
        Set<Long> snapshotGoodsIds = snapshot.stream()
                .map(BizSplitOrderDetail::getGoodsId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (snapshotGoodsIds.isEmpty()) {
            throw BusinessException.validateFail("该成品 BOM 无可退物料，无法提交退料");
        }
        for (SplitReturnSubmitDTO.SplitReturnItemDTO item : dto.getItems()) {
            if (item.getGoodsId() == null || !snapshotGoodsIds.contains(item.getGoodsId())) {
                throw BusinessException.validateFail("退料明细含不属于该拆分单 BOM 快照的物料");
            }
        }

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        // split RETURN 不挂生产任务单（规避终止单退料死端守卫），以 splitOrderId 关联拆分单
        pick.setSplitOrderId(id);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark("成品拆分单 " + split.getSplitNo() + " 拆分退料");
        bizPickListMapper.insert(pick);

        Map<Long, BaseGoods> goodsMap = loadGoodsMap(
                dto.getItems().stream().map(SplitReturnSubmitDTO.SplitReturnItemDTO::getGoodsId).toList());
        int sortNo = 0;
        for (SplitReturnSubmitDTO.SplitReturnItemDTO item : dto.getItems()) {
            BaseGoods goods = goodsMap.get(item.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail(
                        String.format(java.util.Locale.ROOT, "物料不存在: id=%d", item.getGoodsId()));
            }
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(goods.getId());
            det.setGoodsName(goods.getGoodsName());
            det.setQuantity(item.getQuantity());
            det.setDiffReason(item.getDiffReason()); // Q16：差异备注随退料单落库
            det.setSpec(goods.getSpec());
            det.setMaterial(goods.getMaterial());
            det.setSortNo(sortNo++);
            bizPickListDetailMapper.insert(det);
        }

        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_SPLITTING)
                .set(BizSplitOrder::getReturnPickListId, pick.getId());
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
        messageService.sendSplitReturnPendingToWarehouseAdmins(split.getSplitNo(), pick.getPickNo(), id);
    }

    // ============================== 仓储确认成品回库 ==============================

    /** 仓储确认成品回库（7→5，完成方式=放弃回库）：成品库存恢复；通知生产领取人；行处置回算。 */
    @Transactional(rollbackFor = Exception.class)
    public void confirmRestock(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAdmin("仅仓储管理员可确认成品回库");
        BizSplitOrder split = requireEntity(id);
        if (split.getStatus() != BizSplitOrder.STATUS_PENDING_RESTOCK) {
            throw BusinessException.validateFail("仅待仓储确认成品回库状态可确认回库");
        }
        increaseStock(split.getGoodsId(), split.getQuantity());
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_PENDING_RESTOCK)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_DONE)
                .set(BizSplitOrder::getFinishType, BizSplitOrder.FINISH_TYPE_ABANDON)
                .set(BizSplitOrder::getOutboundConfirmUserId, user.getId())
                .set(BizSplitOrder::getOutboundConfirmUserName, user.getRealName())
                .set(BizSplitOrder::getOutboundConfirmTime, LocalDateTime.now());
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
        messageService.sendSplitOrderRestockedToProduction(split.getClaimUserId(), split.getSplitNo(), id);
        recalcDispositionAfterSplit(split.getSalesDetailId());
    }

    // ============================== 仓储作废 ==============================

    /** 仓储作废（仅限未动库存的 1/2 态）：撤未读拆分消息，行处置回算（回退待处置并重发通知）。 */
    @Transactional(rollbackFor = Exception.class)
    public void voidOrder(Long id, SplitOrderVoidDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAdmin("仅仓储管理员可作废拆分单");
        BizSplitOrder split = requireEntity(id);
        if (split.getStatus() != BizSplitOrder.STATUS_PENDING_CLAIM
                && split.getStatus() != BizSplitOrder.STATUS_PENDING_OUTBOUND) {
            throw BusinessException.validateFail("仅待生产领取/待仓储确认出库状态可作废（已动库存的拆分单请联系管理员盘点修正）");
        }
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, id)
                .eq(BizSplitOrder::getStatus, split.getStatus())
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_VOIDED);
        if (splitOrderMapper.update(null, wrapper) != 1) {
            throw BusinessException.validateFail("拆分单已被处理，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("split_order", id);
        // 行处置回算：作废的拆分量归还池子，仍有余量则回退待处置并重发通知
        recalcDispositionAfterSplit(split.getSalesDetailId());
    }

    // ============================== PickListService 回调 ==============================

    /**
     * 拆分退料 RETURN 收料完成回调（PickListService.issue 调用）：物料已回流入库，
     * 拆分单 4→5（完成方式=退料完成），通知生产领取人，行处置回算。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onSplitReturnCompleted(Long splitOrderId, String pickNo) {
        BizSplitOrder split = splitOrderMapper.selectById(splitOrderId);
        if (split == null || split.getStatus() != BizSplitOrder.STATUS_SPLITTING) {
            return;
        }
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, splitOrderId)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_SPLITTING)
                .set(BizSplitOrder::getStatus, BizSplitOrder.STATUS_DONE)
                .set(BizSplitOrder::getFinishType, BizSplitOrder.FINISH_TYPE_RETURN);
        if (splitOrderMapper.update(null, wrapper) != 1) {
            return;
        }
        messageService.sendSplitReturnCompletedToProduction(
                split.getClaimUserId(), split.getSplitNo(), pickNo, splitOrderId);
        recalcDispositionAfterSplit(split.getSalesDetailId());
    }

    /**
     * 拆分退料 RETURN 被驳回回调（PickListService.reject 调用）：清退料单关联（可重提），拆分单保持拆分中。
     */
    @Transactional(rollbackFor = Exception.class)
    public void onSplitReturnRejected(Long splitOrderId, String pickNo, String rejectReason) {
        BizSplitOrder split = splitOrderMapper.selectById(splitOrderId);
        if (split == null || split.getStatus() != BizSplitOrder.STATUS_SPLITTING) {
            return;
        }
        LambdaUpdateWrapper<BizSplitOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSplitOrder::getId, splitOrderId)
                .eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_SPLITTING)
                .set(BizSplitOrder::getReturnPickListId, null);
        if (splitOrderMapper.update(null, wrapper) != 1) {
            return;
        }
        messageService.sendSplitReturnRejectedToProduction(
                split.getClaimUserId(), split.getSplitNo(), pickNo, rejectReason, splitOrderId);
    }

    // ============================== 销售行终止联动 ==============================

    /**
     * 销售行终止联动（SalesService.terminate 逐行调用，ADR-0020 触发口径=仅行终止通道）：
     * 已入库未出库量>0 → 行处置置「待处置」并通知仓储二选一（保留成品 / 发起成品拆分）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleLineTerminated(BizSales sales, BizSalesDetail detail) {
        int unshipped = unshippedInboundQty(sales, detail);
        if (unshipped <= 0) {
            return;
        }
        LambdaUpdateWrapper<BizSalesDetail> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSalesDetail::getId, detail.getId())
                .set(BizSalesDetail::getSplitStatus, BizSalesDetail.SPLIT_PENDING_HANDLE);
        bizSalesDetailMapper.update(null, wrapper);
        messageService.sendSalesDetailSplitHandleNoticeToWarehouse(
                sales.getSalesNo(), detail.getGoodsName(), unshipped, detail.getId());
    }

    /**
     * 保留成品（行处置二选一之一）：仓储管理员将已终止行标记「已保留成品」（成品正常留在库存），
     * 留痕 + 回执生产管理员；撤销行处置待办（D21）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void keepProduct(Long salesDetailId) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAdmin("仅仓储管理员可保留成品");
        BizSalesDetail detail = bizSalesDetailMapper.selectById(salesDetailId);
        if (detail == null) {
            throw BusinessException.notFound("销售明细行不存在");
        }
        if (!isLineTerminated(detail)) {
            throw BusinessException.validateFail("仅已终止的明细行可执行保留成品");
        }
        if (detail.getSplitStatus() != null
                && detail.getSplitStatus() != BizSalesDetail.SPLIT_PENDING_HANDLE) {
            throw BusinessException.validateFail("该明细行已完成处置或已有在途拆分，无需保留成品");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LambdaUpdateWrapper<BizSalesDetail> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSalesDetail::getId, salesDetailId)
                .set(BizSalesDetail::getSplitStatus, BizSalesDetail.SPLIT_KEPT)
                .set(BizSalesDetail::getSplitKeepBy, user.getId())
                .set(BizSalesDetail::getSplitKeepName, user.getRealName())
                .set(BizSalesDetail::getSplitKeepTime, LocalDateTime.now());
        bizSalesDetailMapper.update(null, wrapper);
        messageService.revokeUnreadByBiz("sales_detail_split", salesDetailId);
        BizSales sales = bizSalesMapper.selectById(detail.getSalesId());
        messageService.sendSalesDetailKeptToProductionAdmins(
                sales == null ? "-" : sales.getSalesNo(),
                detail.getGoodsName(), user.getRealName(), salesDetailId);
    }

    // ============================== 行处置回算 ==============================

    /**
     * 行处置回算（拆分单终态后调用）：余量 = 已入库未出库量 −（在途拆分量 + 退料完成拆分量）。
     * 余量>0 → 回退「待处置」并重发仓储通知；余量=0 → 「拆分完成」。
     * 计入口径：在途（1/2/3/4/7）与已完成-退料完成（成品已拆解消耗）；完成-放弃回库/已作废不计入。
     */
    private void recalcDispositionAfterSplit(Long salesDetailId) {
        BizSalesDetail detail = bizSalesDetailMapper.selectById(salesDetailId);
        if (detail == null) {
            return;
        }
        BizSales sales = bizSalesMapper.selectById(detail.getSalesId());
        int unshipped = sales == null ? 0 : unshippedInboundQty(sales, detail);
        int occupied = occupiedSplitQty(salesDetailId);
        int remaining = unshipped - occupied;

        LambdaUpdateWrapper<BizSalesDetail> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizSalesDetail::getId, salesDetailId);
        if (remaining > 0) {
            wrapper.set(BizSalesDetail::getSplitStatus, BizSalesDetail.SPLIT_PENDING_HANDLE)
                    .set(BizSalesDetail::getSplitOrderId, null);
            bizSalesDetailMapper.update(null, wrapper);
            messageService.sendSalesDetailSplitHandleNoticeToWarehouse(
                    sales.getSalesNo(), detail.getGoodsName(), remaining, salesDetailId);
        } else {
            wrapper.set(BizSalesDetail::getSplitStatus, BizSalesDetail.SPLIT_DONE)
                    .set(BizSalesDetail::getSplitOrderId, null);
            bizSalesDetailMapper.update(null, wrapper);
            messageService.revokeUnreadByBiz("sales_detail_split", salesDetailId);
        }
    }

    /** 在途拆分量 + 已完成-退料完成拆分量（放池子口径，见 recalcDispositionAfterSplit）。 */
    private int occupiedSplitQty(Long salesDetailId) {
        LambdaQueryWrapper<BizSplitOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSplitOrder::getSalesDetailId, salesDetailId)
                .and(w -> w.in(BizSplitOrder::getStatus, List.of(
                                BizSplitOrder.STATUS_PENDING_CLAIM,
                                BizSplitOrder.STATUS_PENDING_OUTBOUND,
                                BizSplitOrder.STATUS_PENDING_RECEIPT,
                                BizSplitOrder.STATUS_SPLITTING,
                                BizSplitOrder.STATUS_PENDING_RESTOCK))
                        .or(ow -> ow.eq(BizSplitOrder::getStatus, BizSplitOrder.STATUS_DONE)
                                .eq(BizSplitOrder::getFinishType, BizSplitOrder.FINISH_TYPE_RETURN)));
        return splitOrderMapper.selectList(wrapper).stream()
                .mapToInt(s -> s.getQuantity() == null ? 0 : s.getQuantity()).sum();
    }

    private boolean hasActiveSplit(Long salesDetailId) {
        return occupiedSplitQty(salesDetailId) > 0;
    }

    // ============================== 口径助手 ==============================

    /**
     * 已入库未出库量：关联该销售单+该成品的生产任务单（已完成=已确认入库）数量合计；
     * 销售单已确认出库（整单口径）时为 0（行终止只发生在确认出库前，防御性归零）。
     */
    private int unshippedInboundQty(BizSales sales, BizSalesDetail detail) {
        if (sales.getConfirmStatus() != null && sales.getConfirmStatus() == SalesService.CONFIRM_SHIPPED) {
            return 0;
        }
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizProductionOrder::getSalesOrderId, sales.getId())
                .eq(BizProductionOrder::getGoodsId, detail.getGoodsId())
                .eq(BizProductionOrder::getStatus, BizProductionOrder.STATUS_DONE);
        return bizProductionOrderMapper.selectList(wrapper).stream()
                .mapToInt(o -> o.getQuantity() == null ? 0 : o.getQuantity()).sum();
    }

    /** 展开成品 BOM×拆分数量快照（仅可退物料行：已绑定 goodsId 的真实需求行）。 */
    private void insertBomSnapshot(BizSplitOrder split) {
        BizBom bom = bomMapper.selectOne(new LambdaQueryWrapper<BizBom>()
                .eq(BizBom::getGoodsId, split.getGoodsId()));
        if (bom == null) {
            throw BusinessException.validateFail("该成品尚未建立 BOM，无法发起成品拆分");
        }
        List<BizBomDetail> bomDetails = bomDetailMapper.selectList(new LambdaQueryWrapper<BizBomDetail>()
                .eq(BizBomDetail::getBomId, bom.getId())
                .eq(BizBomDetail::getIsReference, 0)
                .orderByAsc(BizBomDetail::getSortNo)).stream()
                .filter(d -> d.getComponentName() != null && !d.getComponentName().trim().isEmpty())
                .filter(d -> d.getGoodsId() != null)
                .toList();
        if (bomDetails.isEmpty()) {
            throw BusinessException.validateFail("该成品 BOM 无可拆解物料，无法发起成品拆分");
        }
        Map<Long, BaseGoods> goodsMap = loadGoodsMap(
                bomDetails.stream().map(BizBomDetail::getGoodsId).toList());
        int sortNo = 0;
        for (BizBomDetail d : bomDetails) {
            BaseGoods goods = goodsMap.get(d.getGoodsId());
            BigDecimal usage = d.getQuantity() == null ? BigDecimal.ONE : d.getQuantity();
            int required = usage.multiply(BigDecimal.valueOf(split.getQuantity()))
                    .setScale(0, RoundingMode.CEILING).intValue();
            if (required <= 0) {
                continue;
            }
            BizSplitOrderDetail det = new BizSplitOrderDetail();
            det.setSplitOrderId(split.getId());
            det.setGoodsId(goods.getId());
            det.setGoodsName(goods.getGoodsName());
            det.setSpec(goods.getSpec());
            det.setMaterial(goods.getMaterial());
            det.setRequiredQuantity(required);
            det.setSortNo(sortNo++);
            splitOrderDetailMapper.insert(det);
        }
    }

    private Map<Long, BaseGoods> loadGoodsMap(List<Long> goodsIds) {
        List<Long> distinct = goodsIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return baseGoodsMapper.selectBatchIds(distinct).stream()
                .collect(Collectors.toMap(BaseGoods::getId, Function.identity()));
    }

    private String specOfGoods(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        return goods == null ? null : goods.getSpec();
    }

    private boolean isLineTerminated(BizSalesDetail detail) {
        return detail.getTerminateStatus() != null
                && detail.getTerminateStatus() == BizSalesDetail.TERMINATE_TERMINATED;
    }

    private List<BizSplitOrderDetail> listDetails(Long splitOrderId) {
        return splitOrderDetailMapper.selectList(new LambdaQueryWrapper<BizSplitOrderDetail>()
                .eq(BizSplitOrderDetail::getSplitOrderId, splitOrderId)
                .orderByAsc(BizSplitOrderDetail::getSortNo)
                .orderByAsc(BizSplitOrderDetail::getId));
    }

    private BizSplitOrder requireEntity(Long id) {
        BizSplitOrder split = splitOrderMapper.selectById(id);
        if (split == null) {
            throw BusinessException.notFound("成品拆分单不存在");
        }
        return split;
    }

    // ============================== 权限 ==============================

    private void requireReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储/生产部门可查看成品拆分单", AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PRODUCTION);
    }

    private void requireWarehouseAdmin(String message) {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, message);
    }

    private void requireProductionMember(String message) {
        if (authzService.isDeptAdmin(AuthzService.DEPT_PRODUCTION)
                || authzService.isDeptMember(AuthzService.DEPT_PRODUCTION)) {
            return;
        }
        throw BusinessException.forbidden(message);
    }

    /** 生产侧操作者口径（ADR-0020 #4）：领取人本人或生产管理员。 */
    private void requireProductionSideAccess(BizSplitOrder split, String message) {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PRODUCTION)) {
            return;
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        if (user.getId() != null && user.getId().equals(split.getClaimUserId())) {
            return;
        }
        throw BusinessException.forbidden(message);
    }

    // ============================== 库存助手（本模块私有，CLAUDE.md 口径） ==============================

    private void increaseStock(Long goodsId, Integer quantity) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .setSql("stock = stock + " + quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.validateFail("商品不存在");
        }
    }

    private void decreaseStock(Long goodsId, Integer quantity, String msg) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .ge(BaseGoods::getStock, quantity)
                .setSql("stock = stock - " + quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.stockInsufficient(msg);
        }
    }

    // ============================== VO ==============================

    private SplitOrderVO toVO(BizSplitOrder entity) {
        SplitOrderVO vo = new SplitOrderVO();
        vo.setId(entity.getId());
        vo.setSplitNo(entity.getSplitNo());
        vo.setSalesOrderId(entity.getSalesOrderId());
        vo.setSalesOrderNo(entity.getSalesOrderNo());
        vo.setSalesDetailId(entity.getSalesDetailId());
        vo.setGoodsId(entity.getGoodsId());
        vo.setGoodsName(entity.getGoodsName());
        vo.setSpec(entity.getSpec());
        vo.setMaterial(entity.getMaterial());
        vo.setQuantity(entity.getQuantity());
        vo.setStatus(entity.getStatus());
        vo.setStatusText(statusText(entity.getStatus()));
        vo.setInitiatorId(entity.getInitiatorId());
        vo.setInitiatorName(entity.getInitiatorName());
        vo.setInitTime(entity.getInitTime());
        vo.setClaimUserId(entity.getClaimUserId());
        vo.setClaimUserName(entity.getClaimUserName());
        vo.setClaimTime(entity.getClaimTime());
        vo.setOutboundConfirmUserId(entity.getOutboundConfirmUserId());
        vo.setOutboundConfirmUserName(entity.getOutboundConfirmUserName());
        vo.setOutboundConfirmTime(entity.getOutboundConfirmTime());
        vo.setReceiptConfirmUserId(entity.getReceiptConfirmUserId());
        vo.setReceiptConfirmUserName(entity.getReceiptConfirmUserName());
        vo.setReceiptConfirmTime(entity.getReceiptConfirmTime());
        vo.setAbandonUserId(entity.getAbandonUserId());
        vo.setAbandonUserName(entity.getAbandonUserName());
        vo.setAbandonTime(entity.getAbandonTime());
        vo.setReturnPickListId(entity.getReturnPickListId());
        vo.setFinishType(entity.getFinishType());
        vo.setFinishTypeText(finishTypeText(entity.getFinishType()));
        vo.setCreateTime(entity.getCreateTime());
        vo.setUpdateTime(entity.getUpdateTime());
        vo.setDetails(listDetails(entity.getId()).stream().map(d -> {
            SplitOrderDetailVO dv = new SplitOrderDetailVO();
            dv.setId(d.getId());
            dv.setSplitOrderId(d.getSplitOrderId());
            dv.setGoodsId(d.getGoodsId());
            dv.setGoodsName(d.getGoodsName());
            dv.setSpec(d.getSpec());
            dv.setMaterial(d.getMaterial());
            dv.setRequiredQuantity(d.getRequiredQuantity());
            dv.setSortNo(d.getSortNo());
            dv.setCreateTime(d.getCreateTime());
            return dv;
        }).toList());
        // 附 RETURN 退料单号（列表标注「拆分退料」用）
        if (entity.getReturnPickListId() != null) {
            BizPickList pick = bizPickListMapper.selectById(entity.getReturnPickListId());
            if (pick != null) {
                vo.setReturnPickNo(pick.getPickNo());
            }
        }
        return vo;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "-";
        }
        return switch (status) {
            case BizSplitOrder.STATUS_PENDING_CLAIM -> "待生产领取";
            case BizSplitOrder.STATUS_PENDING_OUTBOUND -> "待仓储确认成品出库";
            case BizSplitOrder.STATUS_PENDING_RECEIPT -> "待生产确认收货";
            case BizSplitOrder.STATUS_SPLITTING -> "拆分中";
            case BizSplitOrder.STATUS_DONE -> "已完成";
            case BizSplitOrder.STATUS_VOIDED -> "已作废";
            case BizSplitOrder.STATUS_PENDING_RESTOCK -> "待仓储确认成品回库";
            default -> "-";
        };
    }

    private String finishTypeText(Integer finishType) {
        if (finishType == null) {
            return null;
        }
        return finishType == BizSplitOrder.FINISH_TYPE_RETURN ? "退料完成" : "放弃回库";
    }
}
