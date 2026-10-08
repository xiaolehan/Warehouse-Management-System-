package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PickListQueryDTO;
import org.example.back.dto.PickListRejectDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.PickListDetailVO;
import org.example.back.vo.PickListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PickListService {

    public static final String TYPE_PICK = "PICK";
    public static final String TYPE_SUPPLY = "SUPPLY";
    public static final String TYPE_RETURN = "RETURN";

    public static final int STATUS_PENDING = 1;
    public static final int STATUS_ISSUED = 2;
    public static final int STATUS_DONE = 3;
    public static final int STATUS_REJECTED = 4;

    @Autowired
    private BizPickListMapper bizPickListMapper;

    @Autowired
    private BizPickListDetailMapper bizPickListDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

    @Autowired
    private SplitOrderService splitOrderService;

    // ============================== 查询 ==============================

    public PageResult<PickListVO> page(PickListQueryDTO queryDTO) {
        requirePickListModuleAccess();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        // D141：仓储部门（admin+员工）与超管看全部，生产成员看生产来源，其余仅本人
        boolean isWarehouseOrSuper = authzService.isSuperAdmin()
                || authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE);
        boolean isProductionMember = authzService.isDeptMember(AuthzService.DEPT_PRODUCTION);

        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        // 商品名模糊匹配：先查明细命中的领料单ID集合
        Set<Long> matchedPickListIds = null;
        if (StringUtils.hasText(queryDTO.getGoodsName())) {
            LambdaQueryWrapper<BizPickListDetail> detailWrapper = new LambdaQueryWrapper<>();
            detailWrapper.like(BizPickListDetail::getGoodsName, queryDTO.getGoodsName());
            List<BizPickListDetail> matched = bizPickListDetailMapper.selectList(detailWrapper);
            matchedPickListIds = matched.stream().map(BizPickListDetail::getPickListId).collect(Collectors.toSet());
            if (matchedPickListIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, queryDTO.getPageNum(), queryDTO.getPageSize(), 0L);
            }
        }

        LambdaQueryWrapper<BizPickList> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getPickNo()), BizPickList::getPickNo, queryDTO.getPickNo())
                .eq(StringUtils.hasText(queryDTO.getPickType()), BizPickList::getPickType, queryDTO.getPickType())
                .eq(queryDTO.getStatus() != null, BizPickList::getStatus, queryDTO.getStatus())
                .ge(startTime != null, BizPickList::getCreateTime, startTime)
                .lt(endTime != null, BizPickList::getCreateTime, endTime)
                .in(matchedPickListIds != null, BizPickList::getId, matchedPickListIds)
                .orderByDesc(BizPickList::getId);
        // 数据范围：仓储/超管看全部；生产成员看生产来源全部类型（PICK/SUPPLY/RETURN，与 listByOrder 口径一致）；其余看本人
        if (!isWarehouseOrSuper && isProductionMember) {
            // 生产来源单据全类型可见（RETURN/SUPPLY 均由生产端提交）
        } else if (!isWarehouseOrSuper) {
            wrapper.eq(BizPickList::getApplicantId, loginUser.getId());
        }

        Page<BizPickList> page = bizPickListMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        // RETURN 行附带生产单状态（前端隐藏终止单退料单的驳回/撤销按钮）——批量取避免逐行查库
        List<BizPickList> rows = page.getRecords();
        List<Long> orderIds = rows.stream()
                .filter(e -> TYPE_RETURN.equals(e.getPickType()) && e.getProductionOrderId() != null)
                .map(BizPickList::getProductionOrderId)
                .distinct().toList();
        Map<Long, BizProductionOrder> orderMap = orderIds.isEmpty()
                ? Map.of()
                : bizProductionOrderMapper.selectBatchIds(orderIds).stream()
                        .collect(Collectors.toMap(BizProductionOrder::getId, o -> o));
        List<PickListVO> records = rows.stream()
                // 拆分单退料行 production_order_id 为 NULL（按 splitOrderId 关联，SplitOrderService 设计如此），
                // 空不可变 Map 对 null key 调 get 会抛 NPE——判空兜底（会话 64 修复「生产领料页 500」）
                .map(e -> toVO(e, e.getProductionOrderId() == null ? null : orderMap.get(e.getProductionOrderId())))
                .toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PickListVO getById(Long id) {
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
        ensureViewAccess(entity);
        return toVO(entity);
    }

    // ============================== 发料 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void issue(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseIssueAccess();
        BizPickList entity = requireEntity(id);
        boolean isReturn = TYPE_RETURN.equals(entity.getPickType());
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail(isReturn ? "仅待收料状态可收料" : "仅待发料状态可发料");
        }

        List<BizPickListDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail(isReturn ? "退料单明细为空，无法收料" : "领料单明细为空，无法发料");
        }

        // PICK/SUPPLY 扣减库存（任一缺料整单回滚）；RETURN 回流入库
        try {
            for (BizPickListDetail detail : details) {
                if (isReturn) {
                    increaseStock(detail.getGoodsId(), detail.getQuantity());
                } else {
                    decreaseStock(detail.getGoodsId(), detail.getQuantity(),
                            "商品[" + detail.getGoodsName() + "]库存不足，发料失败");
                }
            }
        } catch (BusinessException e) {
            // 缺料失败：按来源分流通知（REQUIRES_NEW 独立提交，不随本事务回滚），去重避免重试刷屏
            if (!messageService.hasUnreadBizMessage("pick_list", id)) {
                if (entity.getProductionOrderId() != null) {
                    messageService.sendPickIssueFailedToProductionAdmins(
                            entity.getPickNo(), e.getMessage(), id);
                } else {
                    messageService.sendPickListFailureToSalesAdmins(
                            entity.getPickNo(), "发料缺料，库存不足，发料失败", id);
                }
            }
            throw e;
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LambdaUpdateWrapper<BizPickList> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPickList::getId, entity.getId())
                .eq(BizPickList::getStatus, STATUS_PENDING)
                // 收料入库即完成：RETURN 仓储确认收料后直达已完成，无需申请人再确认收货
                .set(BizPickList::getStatus, isReturn ? STATUS_DONE : STATUS_ISSUED)
                .set(BizPickList::getOperatorId, loginUser.getId())
                .set(BizPickList::getOperatorName, loginUser.getRealName())
                .set(BizPickList::getOperationTime, LocalDateTime.now());
        int rows = bizPickListMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail(isReturn ? "退料单已被处理，禁止重复收料" : "领料单已被处理，禁止重复发料");
        }
        // 发料/收料成功：撤销之前可能存在的缺料反馈待办（已不再缺料）
        messageService.revokeUnreadByBiz("pick_list", id);
        // ADR-0020：拆分退料 RETURN 收料入库 → 拆分单完成（完成方式=退料完成）+ 行处置回算
        if (entity.getSplitOrderId() != null) {
            splitOrderService.onSplitReturnCompleted(entity.getSplitOrderId(), entity.getPickNo());
        }
        // 生产来源单据发料/收料成功 → 通知生产端（领料=可开工；退料=已收料入库闭环）
        if (entity.getProductionOrderId() != null) {
            String orderNo = null;
            BizProductionOrder productionOrder = bizProductionOrderMapper.selectById(entity.getProductionOrderId());
            if (productionOrder != null) {
                orderNo = productionOrder.getOrderNo();
            }
            if (isReturn) {
                messageService.sendPickReturnReceivedToProductionAdmins(entity.getPickNo(), orderNo, id);
            } else {
                messageService.sendPickIssuedToProductionAdmins(entity.getPickNo(), orderNo, id);
            }
        }
    }

    // ============================== 确认收货 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
        // 退料单由仓储收料入库即完成（issue 直达已完成），不存在"申请人确认收货"环节
        if (TYPE_RETURN.equals(entity.getPickType())) {
            throw BusinessException.validateFail("退料单由仓储收料入库即完成，无需确认收货");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId()) && !authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("仅申请人本人可确认收货");
        }
        if (entity.getStatus() != STATUS_ISSUED) {
            throw BusinessException.validateFail("仅已发料状态可确认收货");
        }

        LambdaUpdateWrapper<BizPickList> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPickList::getId, entity.getId())
                .eq(BizPickList::getStatus, STATUS_ISSUED)
                .set(BizPickList::getStatus, STATUS_DONE)
                .set(BizPickList::getConfirmTime, LocalDateTime.now());
        int rows = bizPickListMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("领料单状态已变更，请刷新后重试");
        }
    }

    // ============================== 驳回 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, PickListRejectDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseIssueAccess();
        BizPickList entity = requireEntity(id);
        // 终止退料死端守卫：已终止任务单的退料单被驳回后无重提路径（createReturn 仅生产中可提），
        // 物料将滞留生产端无法回库 → 禁止驳回，异常情况线下协调后走库存盘点修正
        if (isTerminatedOrderReturn(entity)) {
            throw BusinessException.validateFail("已终止任务单的终止退料单不可驳回，请收料入库；如物料无法回收，请联系生产管理员协商处理");
        }
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待发料状态可驳回");
        }

        LambdaUpdateWrapper<BizPickList> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPickList::getId, entity.getId())
                .eq(BizPickList::getStatus, STATUS_PENDING)
                .set(BizPickList::getStatus, STATUS_REJECTED)
                .set(BizPickList::getRejectReason, dto.getReason());
        int rows = bizPickListMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("领料单已被处理，禁止重复驳回");
        }
        // 驳回反馈：按来源分流（REQUIRES_NEW 独立提交）。先发通知再撤销未读——
        // 若先 revoke(父事务对 sys_message 加锁)，独立子事务的 INSERT 会等父锁而超时。
        String reason = "仓储驳回领料：" + dto.getReason();
        if (entity.getSplitOrderId() != null) {
            // ADR-0020：拆分退料驳回 → 回执生产领取人（清关联可重提），不通知销售
            splitOrderService.onSplitReturnRejected(entity.getSplitOrderId(), entity.getPickNo(), reason);
        } else if (entity.getProductionOrderId() != null) {
            messageService.sendPickIssueFailedToProductionAdmins(
                    entity.getPickNo(), reason, id);
        } else {
            messageService.sendPickListFailureToSalesAdmins(
                    entity.getPickNo(), reason, id);
        }
        // 驳回终态：仅撤销仓储部未读的"待出库"待办（避免悬挂），保留发给申请方(生产/销售)的驳回反馈通知
        messageService.revokeUnreadByBizAndDeptCode("pick_list", id, AuthzService.DEPT_WAREHOUSE);
    }

    // ============================== 撤销申请 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
        // 终止退料死端守卫：撤销后任务单已终止无重提路径，物料滞留生产端 → 禁止撤销
        if (isTerminatedOrderReturn(entity)) {
            throw BusinessException.validateFail("已终止任务单的终止退料单不可撤销，请由仓储收料入库");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId()) && !authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("仅申请人本人可撤销领料单");
        }
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待发料状态可撤销");
        }
        // 撤销未读的缺料反馈待办（避免悬挂通知）
        messageService.revokeUnreadByBiz("pick_list", id);
        bizPickListMapper.deleteById(id);
        LambdaQueryWrapper<BizPickListDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.eq(BizPickListDetail::getPickListId, id);
        bizPickListDetailMapper.delete(detailWrapper);
    }

    /** 手测问题 1（2026-09-23）：批量删除——请求级守卫先行，逐行调用单删（守卫幂等），尽力而为聚合明细 */
    @Transactional(rollbackFor = Exception.class)
    public BatchDeleteResultVO batchDelete(List<Long> ids) {
        authzService.requireNotSuperAdminForBusinessWrite();
        BatchDeleteResultVO result = new BatchDeleteResultVO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            try {
                delete(id);
                result.addSuccess();
            } catch (BusinessException e) {
                BizPickList head = bizPickListMapper.selectById(id);
                result.addFailure(id, head != null ? head.getPickNo() : String.valueOf(id), e.getMessage());
            }
        }
        return result;
    }

    // ============================== 私有辅助 ==============================

    private void requirePickListModuleAccess() {
        // 阶段13：生产部门成员也可查看领料（生产任务单自动领料的领料单），仓储管理员仍全权
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储/生产部门可查看领料", AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PRODUCTION);
    }

    private void requireWarehouseIssueAccess() {
        // D141：仓储同权开放——仓储部门（admin+员工）均可发料/驳回
        authzService.requireDeptMemberOrSuperAdmin(
                AuthzService.DEPT_WAREHOUSE, "仅仓储部门可发料/驳回");
    }

    private void ensureViewAccess(BizPickList entity) {
        // 仓储部门（admin+员工，D141）/超管全权；生产部门成员可看生产来源全部类型（PICK/SUPPLY/RETURN，与列表数据范围一致）
        if (authzService.isSuperAdmin() || authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE)
                || authzService.isDeptMember(AuthzService.DEPT_PRODUCTION)) {
            return;
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        // 其余角色仅本人单据
        if (!entity.getApplicantId().equals(loginUser.getId())) {
            throw BusinessException.forbidden("无权查看该领料单");
        }
    }

    private BizPickList requireEntity(Long id) {
        BizPickList entity = bizPickListMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("领料单不存在");
        }
        return entity;
    }

    private List<BizPickListDetail> listDetails(Long pickListId) {
        LambdaQueryWrapper<BizPickListDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizPickListDetail::getPickListId, pickListId)
                .orderByAsc(BizPickListDetail::getSortNo)
                .orderByAsc(BizPickListDetail::getId);
        return bizPickListDetailMapper.selectList(wrapper);
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

    private PickListVO toVO(BizPickList entity) {
        return toVO(entity, null);
    }

    private PickListVO toVO(BizPickList entity, BizProductionOrder productionOrder) {
        PickListVO vo = new PickListVO();
        vo.setId(entity.getId());
        vo.setPickNo(entity.getPickNo());
        vo.setPickType(entity.getPickType());
        vo.setPickTypeText(pickTypeText(entity.getPickType()));
        vo.setSourceSalesId(entity.getSourceSalesId());
        vo.setProductionOrderId(entity.getProductionOrderId());
        if (productionOrder != null) {
            vo.setProductionOrderStatus(productionOrder.getStatus());
        }
        vo.setSplitOrderId(entity.getSplitOrderId());
        vo.setStatus(entity.getStatus());
        vo.setStatusText(statusText(entity.getPickType(), entity.getStatus()));
        vo.setApplicantId(entity.getApplicantId());
        vo.setApplicantName(entity.getApplicantName());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperationTime(entity.getOperationTime());
        vo.setConfirmTime(entity.getConfirmTime());
        vo.setRejectReason(entity.getRejectReason());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setIsDeleted(entity.getIsDeleted());
        vo.setDetails(toDetailVOs(listDetails(entity.getId())));
        return vo;
    }

    /**
     * D63：明细转 VO。行上有快照（spec 非空）一律用快照（单据留档）；
     * 历史行无快照时兜底实时读物料主数据补显（软删/缺档保持 null，前端显示「-」）。
     */
    private List<PickListDetailVO> toDetailVOs(List<BizPickListDetail> details) {
        List<Long> fallbackGoodsIds = details.stream()
                .filter(d -> d.getSpec() == null && d.getGoodsId() != null)
                .map(BizPickListDetail::getGoodsId)
                .distinct()
                .toList();
        Map<Long, BaseGoods> fallbackGoods = fallbackGoodsIds.isEmpty()
                ? Map.of()
                : baseGoodsMapper.selectBatchIds(fallbackGoodsIds).stream()
                        .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        return details.stream()
                .map(d -> toDetailVO(d, d.getSpec() == null ? fallbackGoods.get(d.getGoodsId()) : null))
                .toList();
    }

    private PickListDetailVO toDetailVO(BizPickListDetail detail, BaseGoods fallbackGoods) {
        PickListDetailVO vo = new PickListDetailVO();
        vo.setId(detail.getId());
        vo.setPickListId(detail.getPickListId());
        vo.setGoodsId(detail.getGoodsId());
        vo.setGoodsName(detail.getGoodsName());
        vo.setQuantity(detail.getQuantity());
        vo.setSortNo(detail.getSortNo());
        vo.setDiffReason(detail.getDiffReason()); // 需求二 Q16：RETURN 行差异备注透出
        vo.setExpectedQuantity(detail.getExpectedQuantity()); // D138：RETURN 行应退量快照透出（该功能前历史行为 null，前端显示「—」）
        if (fallbackGoods != null) {
            vo.setSpec(fallbackGoods.getSpec());
            vo.setMaterial(fallbackGoods.getMaterial());
            vo.setRemark(fallbackGoods.getDescription());
        } else {
            vo.setSpec(detail.getSpec());
            vo.setMaterial(detail.getMaterial());
            vo.setRemark(detail.getRemark());
        }
        return vo;
    }

    private String pickTypeText(String pickType) {
        if (TYPE_PICK.equals(pickType)) return "领料";
        if (TYPE_SUPPLY.equals(pickType)) return "补料";
        if (TYPE_RETURN.equals(pickType)) return "退料";
        return pickType;
    }

    /** 类型感知状态文本：RETURN 按「收料」口径（待收料/已收料），其余按「发料」口径 */
    private String statusText(String pickType, Integer status) {
        if (status == null) return null;
        boolean isReturn = TYPE_RETURN.equals(pickType);
        return switch (status) {
            case STATUS_PENDING -> isReturn ? "待收料" : "待发料";
            case STATUS_ISSUED -> isReturn ? "已收料" : "已发料";
            case STATUS_DONE -> "已完成";
            case STATUS_REJECTED -> "已驳回";
            default -> String.valueOf(status);
        };
    }

    /** 终止退料死端守卫判定：RETURN 单且其生产任务单已终止（驳回/撤销将致物料滞留生产端无重提路径） */
    private boolean isTerminatedOrderReturn(BizPickList entity) {
        if (!TYPE_RETURN.equals(entity.getPickType()) || entity.getProductionOrderId() == null) {
            return false;
        }
        BizProductionOrder order = bizProductionOrderMapper.selectById(entity.getProductionOrderId());
        return order != null && order.getStatus() != null
                && order.getStatus().equals(BizProductionOrder.STATUS_TERMINATED);
    }
}
