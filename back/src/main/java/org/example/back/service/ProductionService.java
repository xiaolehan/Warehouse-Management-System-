package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionQueryDTO;
import org.example.back.dto.ProductionSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.ProductionVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产入库：仓储管理员将自己生产的零件存入仓库（库存增加）。
 * 范式对齐进货（PurchaseService），但归属仓储部门、无供应商、生产单价可选、作废为仓储直接作废（不走跨部门审批）。
 * D107 两段式：生产端「提交入库申请」生成待确认记录（confirm_status=1，不加库存），
 * 仓储「确认入库」才加库存并由 ProductionOrderService 收尾任务单；仓储手动新增录入即验收（confirm_status=2）。
 */
@Service
public class ProductionService {

    @Autowired
    private BizProductionMapper bizProductionMapper;

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private ProductionOrderService productionOrderService;

    @Autowired
    private MessageService messageService;

    private void requireProductionReadAccess() {
        // 阶段13：生产部门成员可查看生产入库记录（含生产订单完工入库）；仓储管理员仍全权
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储/生产部门可查看生产入库", AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PRODUCTION);
    }

    private void requireProductionWriteAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可维护生产入库单");
    }

    /** D107：确认/驳回入库申请 = 仓储管理员专属（读权限之外的单独写权限，对齐其他确认动作） */
    private void requireInboundConfirmAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可确认成品入库");
    }

    public PageResult<ProductionVO> page(ProductionQueryDTO queryDTO) {
        requireProductionReadAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizProduction> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getProductionNo()), BizProduction::getProductionNo, queryDTO.getProductionNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizProduction::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getGoodsId() != null, BizProduction::getGoodsId, queryDTO.getGoodsId())
                .eq(queryDTO.getConfirmStatus() != null, BizProduction::getConfirmStatus, queryDTO.getConfirmStatus())
                .ge(startTime != null, BizProduction::getOperationTime, startTime)
                .lt(endTime != null, BizProduction::getOperationTime, endTime)
                .orderByDesc(BizProduction::getId);

        Page<BizProduction> page = bizProductionMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseGoods> goodsMap = buildGoodsMap(page.getRecords().stream().map(BizProduction::getGoodsId).collect(Collectors.toSet()));

        List<ProductionVO> records = page.getRecords().stream()
                .map(item -> toVO(item, goodsMap.get(item.getGoodsId())))
                .toList();
        // D107：批量填充来源生产任务单号（生产端提交的入库申请行展示）
        fillProductionOrderNo(records);

        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ProductionVO getById(Long id) {
        requireProductionReadAccess();
        BizProduction production = requireProduction(id);
        BaseGoods goods = baseGoodsMapper.selectById(production.getGoodsId());
        ProductionVO vo = toVO(production, goods);
        // D107/review：详情与列表同口径填充来源任务单号（否则仓储在确认弹窗无法核对来源任务单）
        fillProductionOrderNo(List.of(vo));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(ProductionSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionWriteAccess();
        validateQuantity(dto.getQuantity());

        BaseGoods goods = requireGoods(dto.getGoodsId());
        ensureGoodsEnabled(goods);
        GoodsService.ensureGoodsType(goods, GoodsService.GOODS_TYPE_PRODUCT, "生产入库只可选择成品（type=product）"); // D67
        BigDecimal unitPrice = resolveProductionUnitPrice(dto.getUnitPrice());
        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizProduction production = new BizProduction();
        production.setProductionNo(CodeGenerator.productionNo());
        production.setGoodsId(goods.getId());
        production.setGoodsName(goods.getGoodsName());
        production.setQuantity(dto.getQuantity());
        production.setUnitPrice(unitPrice);
        production.setTotalPrice(unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(dto.getQuantity())));
        production.setOperatorId(loginUser.getId());
        production.setOperatorName(loginUser.getRealName());
        production.setOperationTime(operationTime);
        production.setRemark(dto.getRemark());
        production.setBizStatus(1);
        // D107：仓储手动新增 = 录入即验收（自己确认），不进入待确认流
        production.setConfirmStatus(BizProduction.CONFIRM_CONFIRMED);
        production.setConfirmerId(loginUser.getId());
        production.setConfirmerName(loginUser.getRealName());
        production.setConfirmTime(operationTime);

        bizProductionMapper.insert(production);
        increaseStock(goods.getId(), dto.getQuantity());
    }

    /**
     * D107：仓储确认成品入库——待确认申请 → 已确认入库：此刻才增加库存，
     * 并同事务收尾来源生产任务单（转已完成 + 撤待办 + 通知关联销售可发货）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmInbound(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireInboundConfirmAccess();
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");
        requireApplicationRow(production);
        // review 修复：申请提交后任务单可能已被终止/质检返工或报废（此时申请会被系统自动关闭，
        // 但关闭与确认存在并发窗口）——确认前必须复核任务单仍是待入库态，杜绝凭空加库存+终态单复活
        BizProductionOrder order = bizProductionOrderMapper.selectById(production.getProductionOrderId());
        if (order == null || order.getStatus() == null
                || order.getStatus() != BizProductionOrder.STATUS_AWAIT_QC) {
            throw BusinessException.validateFail("生产任务单当前不是待入库状态（可能已终止/返工/报废），不能确认入库，请驳回该申请");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LocalDateTime confirmedAt = LocalDateTime.now();
        LambdaUpdateWrapper<BizProduction> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizProduction::getId, id)
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .set(BizProduction::getConfirmStatus, BizProduction.CONFIRM_CONFIRMED)
                .set(BizProduction::getConfirmerId, user.getId())
                .set(BizProduction::getConfirmerName, user.getRealName())
                .set(BizProduction::getConfirmTime, confirmedAt)
                // review 修复：入库日期以实际确认时刻为准（申请可能隔天才确认），
                // 否则入库日期列/日期筛选/当天删除窗口全部错按提交日
                .set(BizProduction::getOperationTime, confirmedAt);
        int rows = bizProductionMapper.update(null, wrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("入库申请已被处理，请勿重复确认");
        }
        // 刷新内存对象，保证同事务后续读到确认后的业务时间
        production.setOperationTime(confirmedAt);
        increaseStock(production.getGoodsId(), production.getQuantity());
        // 同事务收尾生产任务单：转已完成 + 撤任务单待办 + 通知关联销售可发货
        productionOrderService.finalizeAfterInboundConfirmed(production.getProductionOrderId());
        // 撤该入库申请的仓储待办消息（D21 范式）
        messageService.revokeUnreadByBiz("production", id);
    }

    /**
     * D107：仓储驳回入库申请（实物验收不符等）——记录不入库存，通知生产提交人核对后重新提交。
     */
    @Transactional(rollbackFor = Exception.class)
    public void rejectInbound(Long id, String reason) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireInboundConfirmAccess();
        if (!StringUtils.hasText(reason)) {
            throw BusinessException.validateFail("请填写驳回原因");
        }
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");
        requireApplicationRow(production);
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        LambdaUpdateWrapper<BizProduction> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizProduction::getId, id)
                .eq(BizProduction::getConfirmStatus, BizProduction.CONFIRM_PENDING)
                .set(BizProduction::getConfirmStatus, BizProduction.CONFIRM_REJECTED)
                .set(BizProduction::getConfirmerId, user.getId())
                .set(BizProduction::getConfirmerName, user.getRealName())
                .set(BizProduction::getConfirmTime, LocalDateTime.now())
                .set(BizProduction::getRejectReason, reason.trim());
        int rows = bizProductionMapper.update(null, wrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("入库申请已被处理，请勿重复驳回");
        }
        // 撤仓储待办 + 回执生产提交人（回执为留痕通知，不随流程撤回）
        messageService.revokeUnreadByBiz("production", id);
        messageService.sendProductionInboundRejectedToUser(
                production.getOperatorId(), production.getProductionNo(), reason.trim(), id);
    }

    /** D107：确认/驳回仅针对生产端提交的申请行（production_order_id 非空且待确认） */
    private void requireApplicationRow(BizProduction production) {
        if (production.getProductionOrderId() == null) {
            throw BusinessException.validateFail("该记录为仓储手动录入，无需确认");
        }
        if (!Integer.valueOf(BizProduction.CONFIRM_PENDING).equals(production.getConfirmStatus())) {
            throw BusinessException.validateFail("该入库申请不是待确认状态");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionWriteAccess();
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");
        ensureStockBearing(production, "删除");
        validateDeleteWindow(production.getOperationTime(), "生产入库单");
        decreaseStock(production.getGoodsId(), production.getQuantity(), "当前库存不足，无法删除该生产入库单");
        bizProductionMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionWriteAccess();
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");
        ensureStockBearing(production, "作废");
        // D99：作废并冲抵（红冲）已停用——界面无入口（D74），此处封死 API 直废路径
        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            throw BusinessException.validateFail("「作废并冲抵」已停用，请使用普通作废");
        }

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizProduction> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizProduction::getId, production.getId())
                .eq(BizProduction::getBizStatus, 1)
                .set(BizProduction::getBizStatus, 2)
                .set(BizProduction::getVoidTime, now)
                .set(BizProduction::getVoidReason, reason);
        int rows = bizProductionMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("生产入库单已被处理，禁止重复作废");
        }

        decreaseStock(production.getGoodsId(), production.getQuantity(), "当前库存不足，无法作废该生产入库单");

        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
            BizProduction redFlushDoc = new BizProduction();
            redFlushDoc.setProductionNo(CodeGenerator.productionNo());
            redFlushDoc.setGoodsId(production.getGoodsId());
            redFlushDoc.setGoodsName(production.getGoodsName());
            redFlushDoc.setQuantity(-production.getQuantity());
            redFlushDoc.setUnitPrice(production.getUnitPrice());
            redFlushDoc.setTotalPrice(production.getTotalPrice() == null ? null : production.getTotalPrice().negate());
            redFlushDoc.setOperatorId(loginUser.getId());
            redFlushDoc.setOperatorName(loginUser.getRealName());
            redFlushDoc.setOperationTime(now);
            redFlushDoc.setRemark("红冲来源:" + production.getProductionNo());
            redFlushDoc.setBizStatus(3);
            redFlushDoc.setSourceId(production.getId());
            redFlushDoc.setVoidReason(reason);
            bizProductionMapper.insert(redFlushDoc);
        }
    }

    private BizProduction requireProduction(Long id) {
        BizProduction production = bizProductionMapper.selectById(id);
        if (production == null) {
            throw BusinessException.notFound("生产入库单不存在");
        }
        return production;
    }

    private BaseGoods requireGoods(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw BusinessException.validateFail("商品不存在");
        }
        return goods;
    }

    private void ensureGoodsEnabled(BaseGoods goods) {
        if (goods.getStatus() == null || goods.getStatus() != 1) {
            throw BusinessException.validateFail("商品已下架，无法创建业务单据");
        }
    }

    private void validateDeleteWindow(LocalDateTime operationTime, String docName) {
        if (operationTime == null) {
            return;
        }
        if (!operationTime.toLocalDate().equals(LocalDate.now())) {
            throw BusinessException.validateFail("仅允许删除当天" + docName + "，历史单据请走作废流程");
        }
    }

    private void ensureNormalStatus(Integer bizStatus, String docName) {
        if (bizStatus == null || bizStatus == 1) {
            return;
        }
        if (bizStatus == 2) {
            throw BusinessException.validateFail(docName + "已作废，禁止重复操作");
        }
        throw BusinessException.validateFail(docName + "为冲抵记录，禁止删除或再次作废");
    }

    /**
     * D107：删除/作废只对已确认入库（加过库存）的记录有意义——
     * 待确认申请由生产端撤销，驳回记录未曾入过库存（删/废会错误扣库存，直接拒绝）。
     */
    private void ensureStockBearing(BizProduction production, String action) {
        if (Integer.valueOf(BizProduction.CONFIRM_PENDING).equals(production.getConfirmStatus())) {
            throw BusinessException.validateFail("待仓储确认的入库申请不可" + action + "，请由生产端撤销后重新提交");
        }
        if (Integer.valueOf(BizProduction.CONFIRM_REJECTED).equals(production.getConfirmStatus())) {
            throw BusinessException.validateFail("被驳回的入库申请未曾入库存，无需" + action);
        }
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "手工作废";
        }
        return reason.trim();
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw BusinessException.validateFail("数量必须大于0");
        }
    }

    /**
     * 生产单价可选：为空返回 null（不记录成本）；非空则必须大于 0。
     */
    private BigDecimal resolveProductionUnitPrice(BigDecimal inputPrice) {
        if (inputPrice == null) {
            return null;
        }
        if (inputPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.validateFail("单价必须大于0");
        }
        return inputPrice;
    }

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

    private Map<Long, BaseGoods> buildGoodsMap(Set<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BaseGoods::getId, goodsIds);
        return baseGoodsMapper.selectList(wrapper).stream().collect(Collectors.toMap(BaseGoods::getId, Function.identity()));
    }

    /** D107：批量填充来源生产任务单号（生产端提交的入库申请行展示用） */
    private void fillProductionOrderNo(List<ProductionVO> records) {
        List<Long> orderIds = records.stream().map(ProductionVO::getProductionOrderId)
                .filter(Objects::nonNull).distinct().toList();
        if (orderIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BizProductionOrder::getId, orderIds);
        Map<Long, BizProductionOrder> orderMap = bizProductionOrderMapper.selectList(wrapper).stream()
                .collect(Collectors.toMap(BizProductionOrder::getId, Function.identity()));
        for (ProductionVO vo : records) {
            if (vo.getProductionOrderId() != null) {
                BizProductionOrder order = orderMap.get(vo.getProductionOrderId());
                vo.setProductionOrderNo(order == null ? null : order.getOrderNo());
            }
        }
    }

    private ProductionVO toVO(BizProduction production, BaseGoods goods) {
        ProductionVO vo = new ProductionVO();
        BeanUtils.copyProperties(production, vo);
        LocalDateTime bizTime = production.getOperationTime() == null ? production.getCreateTime() : production.getOperationTime();
        vo.setOrderNo(production.getProductionNo());
        vo.setPrice(production.getUnitPrice());
        vo.setTotalAmount(production.getTotalPrice());
        vo.setOperationTime(bizTime);
        vo.setProductionDate(bizTime);
        vo.setOperator(production.getOperatorName());
        vo.setBizStatus(production.getBizStatus());
        vo.setSourceId(production.getSourceId());
        vo.setVoidTime(production.getVoidTime());
        vo.setVoidReason(production.getVoidReason());
        // D107：确认状态 + 来源任务单（productionOrderId 由 BeanUtils 拷贝，单号批量填充）
        vo.setConfirmStatus(production.getConfirmStatus());
        vo.setConfirmStatusText(confirmStatusText(production.getConfirmStatus()));
        vo.setConfirmerName(production.getConfirmerName());
        vo.setConfirmTime(production.getConfirmTime());
        vo.setRejectReason(production.getRejectReason());
        return vo;
    }

    /** D107：确认状态文案（confirmStatus 为空的存量行兜底为已确认入库） */
    private String confirmStatusText(Integer confirmStatus) {
        if (confirmStatus == null) {
            return "已确认入库";
        }
        return switch (confirmStatus) {
            case BizProduction.CONFIRM_PENDING -> "待确认";
            case BizProduction.CONFIRM_CONFIRMED -> "已确认入库";
            case BizProduction.CONFIRM_REJECTED -> "已驳回";
            default -> "";
        };
    }
}
