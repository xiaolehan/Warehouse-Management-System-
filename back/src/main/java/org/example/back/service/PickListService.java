package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PickListDetailDTO;
import org.example.back.dto.PickListQueryDTO;
import org.example.back.dto.PickListRejectDTO;
import org.example.back.dto.PickListSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.vo.PickListDetailVO;
import org.example.back.vo.PickListVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
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

    // ============================== 查询 ==============================

    public PageResult<PickListVO> page(PickListQueryDTO queryDTO) {
        requirePickListModuleAccess();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        boolean isWarehouseOrSuper = authzService.isSuperAdmin()
                || authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE);

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
                .eq(!isWarehouseOrSuper, BizPickList::getApplicantId, loginUser.getId())
                .orderByDesc(BizPickList::getId);

        Page<BizPickList> page = bizPickListMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        List<PickListVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PickListVO getById(Long id) {
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
        ensureViewAccess(entity);
        return toVO(entity);
    }

    // ============================== 申请建单 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void create(PickListSaveDTO dto) {
        requireApplyAccess();
        validatePickType(dto.getPickType());

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizPickList entity = new BizPickList();
        entity.setPickNo(CodeGenerator.pickListNo());
        entity.setPickType(dto.getPickType());
        entity.setSourceSalesId(dto.getSourceSalesId());
        entity.setStatus(STATUS_PENDING);
        entity.setApplicantId(loginUser.getId());
        entity.setApplicantName(loginUser.getRealName());
        entity.setRemark(dto.getRemark());
        bizPickListMapper.insert(entity);

        int sortNo = 0;
        for (PickListDetailDTO detail : dto.getDetails()) {
            BaseGoods goods = requireGoods(detail.getGoodsId());
            ensureGoodsEnabled(goods);
            BizPickListDetail detailEntity = new BizPickListDetail();
            detailEntity.setPickListId(entity.getId());
            detailEntity.setGoodsId(goods.getId());
            detailEntity.setGoodsName(goods.getGoodsName());
            detailEntity.setQuantity(detail.getQuantity());
            detailEntity.setSortNo(detail.getSortNo() == null ? sortNo : detail.getSortNo());
            bizPickListDetailMapper.insert(detailEntity);
            sortNo++;
        }
    }

    // ============================== 发料 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void issue(Long id) {
        requireWarehouseIssueAccess();
        BizPickList entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待发料状态可发料");
        }

        List<BizPickListDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("领料单明细为空，无法发料");
        }

        // PICK/SUPPLY 扣减库存（任一缺料整单回滚）；RETURN 回流入库
        boolean isReturn = TYPE_RETURN.equals(entity.getPickType());
        for (BizPickListDetail detail : details) {
            if (isReturn) {
                increaseStock(detail.getGoodsId(), detail.getQuantity());
            } else {
                decreaseStock(detail.getGoodsId(), detail.getQuantity(),
                        "商品[" + detail.getGoodsName() + "]库存不足，发料失败");
            }
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LambdaUpdateWrapper<BizPickList> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPickList::getId, entity.getId())
                .eq(BizPickList::getStatus, STATUS_PENDING)
                .set(BizPickList::getStatus, STATUS_ISSUED)
                .set(BizPickList::getOperatorId, loginUser.getId())
                .set(BizPickList::getOperatorName, loginUser.getRealName())
                .set(BizPickList::getOperationTime, LocalDateTime.now());
        int rows = bizPickListMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("领料单已被处理，禁止重复发料");
        }
    }

    // ============================== 确认收货 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
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
        requireWarehouseIssueAccess();
        BizPickList entity = requireEntity(id);
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
    }

    // ============================== 撤销申请 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requirePickListModuleAccess();
        BizPickList entity = requireEntity(id);
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId()) && !authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("仅申请人本人可撤销领料单");
        }
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待发料状态可撤销");
        }
        bizPickListMapper.deleteById(id);
        LambdaQueryWrapper<BizPickListDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.eq(BizPickListDetail::getPickListId, id);
        bizPickListDetailMapper.delete(detailWrapper);
    }

    // ============================== 私有辅助 ==============================

    private void requirePickListModuleAccess() {
        authzService.requireAnyDeptAdminOrSuperAdmin(
                "仅销售/仓储管理员可访问领料模块", AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
    }

    private void requireApplyAccess() {
        authzService.requireAnyDeptAdminOrSuperAdmin(
                "仅销售/仓储管理员可申请领料", AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
    }

    private void requireWarehouseIssueAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可发料/驳回");
    }

    private void ensureViewAccess(BizPickList entity) {
        if (authzService.isSuperAdmin() || authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
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

    private BaseGoods requireGoods(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw BusinessException.notFound("商品不存在");
        }
        return goods;
    }

    private void ensureGoodsEnabled(BaseGoods goods) {
        if (goods.getStatus() == null || goods.getStatus() != 1) {
            throw BusinessException.validateFail("商品[" + goods.getGoodsName() + "]已停用");
        }
    }

    private void validatePickType(String pickType) {
        if (!TYPE_PICK.equals(pickType) && !TYPE_SUPPLY.equals(pickType) && !TYPE_RETURN.equals(pickType)) {
            throw BusinessException.validateFail("领料类型非法，仅支持 PICK/SUPPLY/RETURN");
        }
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
        PickListVO vo = new PickListVO();
        vo.setId(entity.getId());
        vo.setPickNo(entity.getPickNo());
        vo.setPickType(entity.getPickType());
        vo.setPickTypeText(pickTypeText(entity.getPickType()));
        vo.setSourceSalesId(entity.getSourceSalesId());
        vo.setStatus(entity.getStatus());
        vo.setStatusText(statusText(entity.getStatus()));
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
        vo.setDetails(listDetails(entity.getId()).stream().map(this::toDetailVO).toList());
        return vo;
    }

    private PickListDetailVO toDetailVO(BizPickListDetail detail) {
        PickListDetailVO vo = new PickListDetailVO();
        vo.setId(detail.getId());
        vo.setPickListId(detail.getPickListId());
        vo.setGoodsId(detail.getGoodsId());
        vo.setGoodsName(detail.getGoodsName());
        vo.setQuantity(detail.getQuantity());
        vo.setSortNo(detail.getSortNo());
        return vo;
    }

    private String pickTypeText(String pickType) {
        if (TYPE_PICK.equals(pickType)) return "领料";
        if (TYPE_SUPPLY.equals(pickType)) return "补料";
        if (TYPE_RETURN.equals(pickType)) return "退料";
        return pickType;
    }

    private String statusText(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case STATUS_PENDING -> "待发料";
            case STATUS_ISSUED -> "已发料";
            case STATUS_DONE -> "已完成";
            case STATUS_REJECTED -> "已驳回";
            default -> String.valueOf(status);
        };
    }
}
