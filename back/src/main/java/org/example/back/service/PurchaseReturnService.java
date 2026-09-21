package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.PurchaseReturnQueryDTO;
import org.example.back.dto.PurchaseReturnSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.entity.BizPurchaseReturn;
import org.example.back.entity.BizPurchaseReturnDetail;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseReturnDetailMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.example.back.vo.PurchaseReturnDetailVO;
import org.example.back.vo.PurchaseReturnVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PurchaseReturnService {

    public static final int CONFIRM_PENDING = 1;    // 待出库确认
    public static final int CONFIRM_AWAITING = 2;   // 待退货确认(已出库)
    public static final int CONFIRM_COMPLETED = 3;  // 已退货

    @Autowired
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;

    @Autowired
    private BizPurchaseReturnDetailMapper bizPurchaseReturnDetailMapper;

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BizPurchaseDetailMapper bizPurchaseDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    private void requirePurchaseReturnModuleAccess() {
        // D32：采购部门成员（admin+员工）可建退货单/确认退货成功（不动库存，仓储确认出库扣库存）
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购部门可访问商品退货模块");
    }

    /**
     * 采购管理员权限（admin）：删除当天退货单收口 admin（D32：员工仅 create+read+确认退货成功）。
     */
    private void requirePurchaseReturnAdminOrSuperAdmin() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购管理员可执行该操作");
    }

    /**
     * 读权限：采购 + 仓储 均可查看退货单（仓储需查看待确认出库的单据）。
     */
    private void requirePurchaseReturnReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅采购/仓储部门可访问商品退货模块", AuthzService.DEPT_PURCHASE, AuthzService.DEPT_WAREHOUSE);
    }

    public PageResult<PurchaseReturnVO> page(PurchaseReturnQueryDTO queryDTO) {
        requirePurchaseReturnReadAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();
        // 构建查询条件
        LambdaQueryWrapper<BizPurchaseReturn> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getReturnNo()), BizPurchaseReturn::getReturnNo, queryDTO.getReturnNo());

        // D111：物料名/物料id 过滤下沉明细行（头表已无商品字段），EXISTS 参数化防注入
        wrapper.apply(StringUtils.hasText(queryDTO.getGoodsName()),
                        "EXISTS (SELECT 1 FROM biz_purchase_return_detail d WHERE d.return_id = biz_purchase_return.id AND d.is_deleted = 0"
                                + " AND d.goods_name LIKE CONCAT('%', {0}, '%'))", queryDTO.getGoodsName());
        wrapper.apply(queryDTO.getGoodsId() != null,
                        "EXISTS (SELECT 1 FROM biz_purchase_return_detail d WHERE d.return_id = biz_purchase_return.id AND d.is_deleted = 0"
                                + " AND d.goods_id = {0})", queryDTO.getGoodsId());
        wrapper.apply(StringUtils.hasText(queryDTO.getSupplierName()),
                        "EXISTS (SELECT 1 FROM biz_purchase_return_detail d"
                                + " JOIN base_goods g ON g.id = d.goods_id"
                                + " JOIN base_supplier s ON s.id = g.supplier_id"
                                + " WHERE d.return_id = biz_purchase_return.id AND d.is_deleted = 0"
                                + " AND s.supplier_name LIKE CONCAT('%', {0}, '%'))", queryDTO.getSupplierName());

        wrapper.ge(startTime != null, BizPurchaseReturn::getOperationTime, startTime)
            .lt(endTime != null, BizPurchaseReturn::getOperationTime, endTime)
                .orderByDesc(BizPurchaseReturn::getId);
        // 执行分页查询
        Page<BizPurchaseReturn> page = bizPurchaseReturnMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(
                page.getRecords().stream().map(BizPurchaseReturn::getId).toList());
        List<PurchaseReturnVO> records = page.getRecords().stream()
                .map(item -> toVO(item, approvalMap.get(item.getId())))
                .toList();
        fillDetails(records);
        // 构建并返回分页结果对象
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PurchaseReturnVO getById(Long id) {
        requirePurchaseReturnReadAccess();
        BizPurchaseReturn entity = requireEntity(id);
        PurchaseReturnVO vo = toVO(entity, resolveLatestApproval(entity.getId()));
        fillDetails(List.of(vo));
        return vo;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(PurchaseReturnSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseReturnModuleAccess();
        List<PurchaseReturnSaveDTO.LineDTO> lines = requireLines(dto.getLines());

        // D111：同一来源明细行一张退货单只能出现一次
        Set<Long> sourceDetailIds = new HashSet<>();
        for (PurchaseReturnSaveDTO.LineDTO line : lines) {
            if (line.getSourceDetailId() == null || !sourceDetailIds.add(line.getSourceDetailId())) {
                throw BusinessException.validateFail("同一来源明细行在一张退货单中只能出现一次，请合并数量");
            }
        }

        // 来源明细行加载；一张退货单只能退同一张进货单的行
        List<BizPurchaseDetail> sourceLines = bizPurchaseDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseDetail>()
                        .in(BizPurchaseDetail::getId, sourceDetailIds)
                        .orderByAsc(BizPurchaseDetail::getSortNo));
        if (sourceLines.size() != sourceDetailIds.size()) {
            throw BusinessException.validateFail("来源进货明细行不存在，禁止退货");
        }
        Set<Long> sourcePurchaseIds = sourceLines.stream().map(BizPurchaseDetail::getPurchaseId).collect(Collectors.toSet());
        if (sourcePurchaseIds.size() != 1) {
            throw BusinessException.validateFail("一张退货单只能退同一进货单的物料，请按进货单分别退货");
        }
        Long sourcePurchaseId = sourcePurchaseIds.iterator().next();
        BizPurchase sourcePurchase = requireSourcePurchase(sourcePurchaseId);
        ensureSourcePurchaseNormal(sourcePurchase);

        // 行级可退量校验（D111 决策⑤：行入库量 − 该行被有效/已出库退货累计）
        Map<Long, Integer> returnedMap = returnedQtyBySourceDetail(new ArrayList<>(sourceDetailIds));
        Map<Long, PurchaseReturnSaveDTO.LineDTO> inputBySourceDetail = linesBySourceDetailId(lines);
        for (BizPurchaseDetail sourceLine : sourceLines) {
            PurchaseReturnSaveDTO.LineDTO input = inputBySourceDetail.get(sourceLine.getId());
            int returnable = sourceLine.getQuantity() - returnedMap.getOrDefault(sourceLine.getId(), 0);
            if (returnable <= 0) {
                throw BusinessException.validateFail("明细行「" + sourceLine.getGoodsName() + "」已无可退数量");
            }
            if (input.getQuantity() == null || input.getQuantity() <= 0) {
                throw BusinessException.validateFail("数量必须大于0");
            }
            if (input.getQuantity() > returnable) {
                throw BusinessException.validateFail("明细行「" + sourceLine.getGoodsName() + "」退货数量超出可退数量，当前最多可退: " + returnable);
            }
        }

        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();
        List<Long> goodsIds = sourceLines.stream().map(BizPurchaseDetail::getGoodsId).distinct().toList();
        Map<Long, BaseGoods> goodsMap = baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));

        List<BizPurchaseReturnDetail> detailEntities = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int sortNo = 0;
        for (BizPurchaseDetail sourceLine : sourceLines) {
            sortNo++;
            PurchaseReturnSaveDTO.LineDTO input = inputBySourceDetail.get(sourceLine.getId());
            BaseGoods goods = goodsMap.get(sourceLine.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail("商品不存在");
            }
            // review：退货路径不校验商品启用状态——已停用/停产的物料也必须能退给供应商
            BigDecimal unitPrice = sourceLine.getUnitPrice(); // 退货单价按来源行进价带出
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(input.getQuantity()));

            BizPurchaseReturnDetail detail = new BizPurchaseReturnDetail();
            detail.setSourcePurchaseId(sourcePurchase.getId());
            detail.setSourceDetailId(sourceLine.getId());
            detail.setGoodsId(sourceLine.getGoodsId());
            detail.setGoodsName(sourceLine.getGoodsName());
            detail.setSpec(sourceLine.getSpec());
            detail.setMaterial(sourceLine.getMaterial());
            detail.setQuantity(input.getQuantity());
            detail.setUnitPrice(unitPrice);
            detail.setTotalPrice(lineTotal);
            detail.setSortNo(sortNo);
            detailEntities.add(detail);

            totalQuantity += input.getQuantity();
            totalAmount = totalAmount.add(lineTotal);
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        BizPurchaseReturn entity = new BizPurchaseReturn();
        entity.setReturnNo(CodeGenerator.purchaseReturnNo());
        entity.setSourcePurchaseId(sourcePurchase.getId());
        entity.setSourcePurchaseNo(sourcePurchase.getPurchaseNo());
        entity.setTotalQuantity(totalQuantity);
        entity.setTotalAmount(totalAmount);
        entity.setOperatorId(loginUser.getId());
        entity.setOperatorName(loginUser.getRealName());
        entity.setOperationTime(operationTime);
        entity.setRemark(dto.getRemark());
        entity.setBizStatus(1);
        entity.setConfirmStatus(CONFIRM_PENDING);

        bizPurchaseReturnMapper.insert(entity);
        for (BizPurchaseReturnDetail detail : detailEntities) {
            detail.setReturnId(entity.getId());
            bizPurchaseReturnDetailMapper.insert(detail);
        }
        // 不减库存，待仓储确认出库 + 采购确认退货成功
        messageService.sendPurchaseReturnPendingConfirmToWarehouseAdmins(entity.getReturnNo(), loginUser.getRealName(), entity.getId());
    }

    // ============================== 仓储确认出库（减库存） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirmOut(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAccess();
        BizPurchaseReturn entity = requireEntity(id);
        if (entity.getConfirmStatus() == null || entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("仅待出库确认状态可确认出库");
        }
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");
        ensureNoPendingVoidApproval(id, "进货退货单");

        List<BizPurchaseReturnDetail> details = requireDetails(entity.getId());
        for (BizPurchaseReturnDetail detail : details) {
            decreaseStock(detail.getGoodsId(), detail.getQuantity(),
                    "库存不足，无法确认退货出库（" + detail.getGoodsName() + " 需 " + detail.getQuantity() + "）");
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchaseReturn> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseReturn::getId, entity.getId())
                .eq(BizPurchaseReturn::getConfirmStatus, CONFIRM_PENDING)
                .set(BizPurchaseReturn::getConfirmStatus, CONFIRM_AWAITING)
                .set(BizPurchaseReturn::getConfirmerId, loginUser.getId())
                .set(BizPurchaseReturn::getConfirmerName, loginUser.getRealName())
                .set(BizPurchaseReturn::getConfirmTime, now);
        int rows = bizPurchaseReturnMapper.update(null, updateWrapper);
        if (rows != 1) {
            // 库存已逐行扣减但状态更新失败时由事务回滚保护
            throw BusinessException.validateFail("退货单状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_return", id);
    }

    // ============================== 采购确认退货成功（终态） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void complete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseReturnModuleAccess();
        BizPurchaseReturn entity = requireEntity(id);
        if (entity.getConfirmStatus() == null || entity.getConfirmStatus() != CONFIRM_AWAITING) {
            throw BusinessException.validateFail("仅待退货确认状态可确认退货成功");
        }
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");
        ensureNoPendingVoidApproval(id, "进货退货单");
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchaseReturn> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseReturn::getId, entity.getId())
                .eq(BizPurchaseReturn::getConfirmStatus, CONFIRM_AWAITING)
                .set(BizPurchaseReturn::getConfirmStatus, CONFIRM_COMPLETED)
                .set(BizPurchaseReturn::getCompleterId, loginUser.getId())
                .set(BizPurchaseReturn::getCompleterName, loginUser.getRealName())
                .set(BizPurchaseReturn::getCompleteTime, now);
        int rows = bizPurchaseReturnMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("退货单状态已变更，请刷新后重试");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseReturnAdminOrSuperAdmin();
        BizPurchaseReturn entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");
        validateDeleteWindow(entity.getOperationTime(), "进货退货单");
        // 仅待出库确认(未减库存)可删，不触碰库存；已出库走作废流程
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("已出库的退货单不可删除，请走作废流程");
        }
        messageService.revokeUnreadByBiz("purchase_return", id);
        bizPurchaseReturnMapper.deleteById(id);
        // D111：级联软删明细行
        bizPurchaseReturnDetailMapper.delete(new LambdaQueryWrapper<BizPurchaseReturnDetail>()
                .eq(BizPurchaseReturnDetail::getReturnId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseReturnVoidExecutionAccess();
        BizPurchaseReturn entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "进货退货单");
        // D99：作废并冲抵（红冲）已停用——界面无入口（D74），此处封死 API 直废路径
        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            throw BusinessException.validateFail("「作废并冲抵」已停用，请使用普通作废");
        }

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchaseReturn> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizPurchaseReturn::getId, entity.getId())
                .eq(BizPurchaseReturn::getBizStatus, 1)
                .set(BizPurchaseReturn::getBizStatus, 2)
                .set(BizPurchaseReturn::getVoidTime, now)
                .set(BizPurchaseReturn::getVoidReason, reason);
        int rows = bizPurchaseReturnMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("进货退货单已被处理，禁止重复作废");
        }

        // 仅已出库(confirm_status>=2)作废需逐行回补库存；未出库不触碰库存
        boolean outConfirmed = entity.getConfirmStatus() != null && entity.getConfirmStatus() >= CONFIRM_AWAITING;
        if (outConfirmed) {
            for (BizPurchaseReturnDetail detail : requireDetails(entity.getId())) {
                increaseStock(detail.getGoodsId(), detail.getQuantity());
            }
        }
        messageService.revokeUnreadByBiz("purchase_return", id);
    }

    /**
     * D111/review：进货单作废前的守卫——存在未终结(正常)退货单时禁止整单作废。
     * 否则作废按整行回冲会与已出库退货的库存扣减冲突（库存不足或被作废后再退导致库存错乱）；
     * 用户须先删除（当天待出库）/作废退货单，再作废进货单。
     */
    public void ensureNoActiveReturn(Long sourcePurchaseId) {
        if (sourcePurchaseId == null) {
            return;
        }
        List<BizPurchaseReturn> active = bizPurchaseReturnMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseReturn>()
                        .eq(BizPurchaseReturn::getSourcePurchaseId, sourcePurchaseId)
                        .eq(BizPurchaseReturn::getBizStatus, 1));
        if (!active.isEmpty()) {
            String nos = active.stream().map(BizPurchaseReturn::getReturnNo)
                    .limit(5).collect(Collectors.joining("、"));
            throw BusinessException.validateFail(
                    "该进货单关联有未终结的退货单（" + nos + "），请先删除或作废退货单后再作废进货单");
        }
    }

    /**
     * D111：按来源进货明细行汇总已实际扣减库存的退货量（biz_status=1 且 confirm_status>=2——
     * 仓储确认出库即扣库存，故 待退货确认(2)/已退货(3) 均计入，防止超退）。
     */
    public Map<Long, Integer> returnedQtyBySourceDetail(List<Long> sourceDetailIds) {
        if (sourceDetailIds == null || sourceDetailIds.isEmpty()) {
            return Map.of();
        }
        List<BizPurchaseReturnDetail> returnDetails = bizPurchaseReturnDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseReturnDetail>()
                        .in(BizPurchaseReturnDetail::getSourceDetailId, sourceDetailIds));
        if (returnDetails.isEmpty()) {
            return Map.of();
        }
        List<Long> returnIds = returnDetails.stream().map(BizPurchaseReturnDetail::getReturnId).distinct().toList();
        Map<Long, BizPurchaseReturn> returnMap = bizPurchaseReturnMapper.selectBatchIds(returnIds).stream()
                .collect(Collectors.toMap(BizPurchaseReturn::getId, r -> r));
        Map<Long, Integer> result = new LinkedHashMap<>();
        for (BizPurchaseReturnDetail rd : returnDetails) {
            BizPurchaseReturn r = returnMap.get(rd.getReturnId());
            if (r == null || r.getBizStatus() == null || r.getBizStatus() != 1
                    || r.getConfirmStatus() == null || r.getConfirmStatus() < CONFIRM_AWAITING) {
                continue;
            }
            result.merge(rd.getSourceDetailId(), rd.getQuantity(), Integer::sum);
        }
        return result;
    }

    private void requirePurchaseReturnVoidExecutionAccess() {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PURCHASE)) {
            throw BusinessException.validateFail("历史进货退货单作废需提交仓储审批");
        }
        throw BusinessException.forbidden("仅采购部门管理员可发起进货退货作废申请，且需由仓储部门审批");
    }

    private void requireWarehouseAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可确认退货出库");
    }

    private String confirmStatusText(Integer confirmStatus) {
        if (confirmStatus == null) return null;
        return switch (confirmStatus) {
            case CONFIRM_PENDING -> "待出库确认";
            case CONFIRM_AWAITING -> "待退货确认";
            case CONFIRM_COMPLETED -> "已退货";
            default -> String.valueOf(confirmStatus);
        };
    }

    private BizPurchaseReturn requireEntity(Long id) {
        BizPurchaseReturn entity = bizPurchaseReturnMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("进货退货单不存在");
        }
        return entity;
    }

    private BizPurchase requireSourcePurchase(Long sourcePurchaseId) {
        BizPurchase purchase = bizPurchaseMapper.selectById(sourcePurchaseId);
        if (purchase == null) {
            throw BusinessException.validateFail("来源进货单不存在");
        }
        return purchase;
    }

    private void ensureSourcePurchaseNormal(BizPurchase sourcePurchase) {
        if (sourcePurchase.getBizStatus() == null || sourcePurchase.getBizStatus() != 1) {
            throw BusinessException.validateFail("来源进货单非正常状态，禁止退货");
        }
    }

    private List<BizPurchaseReturnDetail> requireDetails(Long returnId) {
        return bizPurchaseReturnDetailMapper.selectList(new LambdaQueryWrapper<BizPurchaseReturnDetail>()
                .eq(BizPurchaseReturnDetail::getReturnId, returnId)
                .orderByAsc(BizPurchaseReturnDetail::getSortNo)
                .orderByAsc(BizPurchaseReturnDetail::getId));
    }

    private List<PurchaseReturnSaveDTO.LineDTO> requireLines(List<PurchaseReturnSaveDTO.LineDTO> lines) {
        if (lines == null || lines.isEmpty()) {
            throw BusinessException.validateFail("退货明细不能为空");
        }
        return lines;
    }

    private Map<Long, PurchaseReturnSaveDTO.LineDTO> linesBySourceDetailId(List<PurchaseReturnSaveDTO.LineDTO> lines) {
        Map<Long, PurchaseReturnSaveDTO.LineDTO> map = new LinkedHashMap<>();
        for (PurchaseReturnSaveDTO.LineDTO line : lines) {
            map.put(line.getSourceDetailId(), line);
        }
        return map;
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

    // D97：作废审批中冻结主流程——存在待审批(1)/处理中(4)的作废类申请时禁止继续确认
    private void ensureNoPendingVoidApproval(Long bizId, String docName) {
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "purchase_return")
                .eq(BizApprovalOrder::getBizId, bizId)
                .in(BizApprovalOrder::getStatus, 1, 4)
                .in(BizApprovalOrder::getRequestAction, "void", "void_red");
        if (bizApprovalOrderMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail(docName + "正在作废审批中，待仓储管理员处理后再操作");
        }
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "手工作废";
        }
        return reason.trim();
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

    private Map<Long, BizApprovalOrder> buildLatestApprovalMap(List<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "purchase_return")
                .in(BizApprovalOrder::getBizId, bizIds)
                .orderByDesc(BizApprovalOrder::getId);
        Map<Long, BizApprovalOrder> approvalMap = new LinkedHashMap<>();
        for (BizApprovalOrder item : bizApprovalOrderMapper.selectList(wrapper)) {
            approvalMap.putIfAbsent(item.getBizId(), item);
        }
        return approvalMap;
    }

    private BizApprovalOrder resolveLatestApproval(Long bizId) {
        return buildLatestApprovalMap(List.of(bizId)).get(bizId);
    }

    /**
     * D111：批量填充明细行（一次 in 查询）+ 商品汇总。
     */
    private void fillDetails(List<PurchaseReturnVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> returnIds = records.stream().map(PurchaseReturnVO::getId).toList();
        List<BizPurchaseReturnDetail> details = bizPurchaseReturnDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseReturnDetail>()
                        .in(BizPurchaseReturnDetail::getReturnId, returnIds)
                        .orderByAsc(BizPurchaseReturnDetail::getSortNo)
                        .orderByAsc(BizPurchaseReturnDetail::getId));
        Map<Long, List<BizPurchaseReturnDetail>> byReturn = details.stream()
                .collect(Collectors.groupingBy(BizPurchaseReturnDetail::getReturnId));
        // D123：退货单供应商 = 来源进货单头级供应商（create 限定单来源，故一一对应）；
        // 存量来源单无头级供应商时留空。批量预取防 N+1。
        List<Long> sourcePurchaseIds = records.stream().map(PurchaseReturnVO::getSourcePurchaseId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, BizPurchase> sourceMap = sourcePurchaseIds.isEmpty() ? Map.of()
                : bizPurchaseMapper.selectBatchIds(sourcePurchaseIds).stream()
                        .collect(Collectors.toMap(BizPurchase::getId, p -> p));
        Set<Long> sourceSupplierIds = sourceMap.values().stream()
                .map(BizPurchase::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, BaseSupplier> sourceSupplierMap = sourceSupplierIds.isEmpty() ? Map.of()
                : baseSupplierMapper.selectBatchIds(sourceSupplierIds).stream()
                        .collect(Collectors.toMap(BaseSupplier::getId, s -> s));
        for (PurchaseReturnVO vo : records) {
            List<PurchaseReturnDetailVO> lineVOs = byReturn.getOrDefault(vo.getId(), List.of()).stream()
                    .map(this::toDetailVO).toList();
            vo.setDetails(lineVOs);
            vo.setGoodsSummary(buildGoodsSummary(lineVOs));
            BizPurchase sourcePurchase = vo.getSourcePurchaseId() == null ? null : sourceMap.get(vo.getSourcePurchaseId());
            if (sourcePurchase != null && sourcePurchase.getSupplierId() != null) {
                BaseSupplier sourceSupplier = sourceSupplierMap.get(sourcePurchase.getSupplierId());
                vo.setSupplierName(sourceSupplier == null ? null : sourceSupplier.getSupplierName());
            }
        }
    }

    private PurchaseReturnDetailVO toDetailVO(BizPurchaseReturnDetail d) {
        PurchaseReturnDetailVO line = new PurchaseReturnDetailVO();
        line.setId(d.getId());
        line.setReturnId(d.getReturnId());
        line.setSourcePurchaseId(d.getSourcePurchaseId());
        line.setSourceDetailId(d.getSourceDetailId());
        line.setGoodsId(d.getGoodsId());
        line.setGoodsName(d.getGoodsName());
        line.setSpec(d.getSpec());
        line.setMaterial(d.getMaterial());
        line.setQuantity(d.getQuantity());
        line.setUnitPrice(d.getUnitPrice());
        line.setTotalPrice(d.getTotalPrice());
        line.setSortNo(d.getSortNo());
        return line;
    }

    private String buildGoodsSummary(List<PurchaseReturnDetailVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return "-";
        }
        String firstName = lines.get(0).getGoodsName();
        if (lines.size() == 1) {
            return firstName;
        }
        return firstName + "等" + lines.size() + "种";
    }

    private PurchaseReturnVO toVO(BizPurchaseReturn entity, BizApprovalOrder approvalOrder) {
        PurchaseReturnVO vo = new PurchaseReturnVO();
        vo.setId(entity.getId());
        vo.setReturnNo(entity.getReturnNo());
        vo.setSourcePurchaseId(entity.getSourcePurchaseId());
        vo.setSourcePurchaseNo(entity.getSourcePurchaseNo());
        vo.setTotalQuantity(entity.getTotalQuantity());
        vo.setTotalAmount(entity.getTotalAmount());
        LocalDateTime bizTime = entity.getOperationTime() == null ? entity.getCreateTime() : entity.getOperationTime();
        vo.setOperationTime(bizTime);
        vo.setReturnDate(bizTime);
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperator(entity.getOperatorName());
        vo.setRemark(entity.getRemark());
        vo.setBizStatus(entity.getBizStatus());
        vo.setConfirmStatus(entity.getConfirmStatus());
        vo.setConfirmStatusText(confirmStatusText(entity.getConfirmStatus()));
        vo.setConfirmerName(entity.getConfirmerName());
        vo.setConfirmTime(entity.getConfirmTime());
        vo.setCompleterName(entity.getCompleterName());
        vo.setCompleteTime(entity.getCompleteTime());
        vo.setSourceId(entity.getSourceId());
        vo.setVoidTime(entity.getVoidTime());
        vo.setVoidReason(entity.getVoidReason());
        vo.setCreateTime(entity.getCreateTime());
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        return vo;
    }
}
