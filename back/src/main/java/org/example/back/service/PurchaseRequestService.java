package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseRequestDetailDTO;
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.example.back.dto.PurchaseRequestQueryDTO;
import org.example.back.dto.PurchaseRequestReceiveDTO;
import org.example.back.dto.PurchaseRequestRejectDTO;
import org.example.back.dto.PurchaseRequestSaveDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.PurchaseRequestDetailVO;
import org.example.back.vo.PurchaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PurchaseRequestService {

    public static final int STATUS_PENDING = 1;    // 待采购
    public static final int STATUS_PURCHASING = 2;  // 采购中
    public static final int STATUS_RECEIVED = 3;    // 已入库
    public static final int STATUS_REJECTED = 4;    // 已驳回
    public static final int STATUS_AWAITING_CONFIRM = 5;  // 待入库确认

    @Autowired
    private BizPurchaseRequestMapper bizPurchaseRequestMapper;

    @Autowired
    private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    @Autowired
    private PurchaseService purchaseService;

    // ============================== 查询 ==============================

    public PageResult<PurchaseRequestVO> page(PurchaseRequestQueryDTO queryDTO) {
        requireModuleReadAccess();

        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        // 商品名模糊匹配：先查明细命中的申请单ID集合
        Set<Long> matchedRequestIds = null;
        if (StringUtils.hasText(queryDTO.getGoodsName())) {
            LambdaQueryWrapper<BizPurchaseRequestDetail> detailWrapper = new LambdaQueryWrapper<>();
            detailWrapper.like(BizPurchaseRequestDetail::getGoodsName, queryDTO.getGoodsName());
            List<BizPurchaseRequestDetail> matched = bizPurchaseRequestDetailMapper.selectList(detailWrapper);
            matchedRequestIds = matched.stream().map(BizPurchaseRequestDetail::getRequestId).collect(Collectors.toSet());
            if (matchedRequestIds.isEmpty()) {
                return new PageResult<>(List.of(), 0L, queryDTO.getPageNum(), queryDTO.getPageSize(), 0L);
            }
        }

        LambdaQueryWrapper<BizPurchaseRequest> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getRequestNo()), BizPurchaseRequest::getRequestNo, queryDTO.getRequestNo())
                .eq(queryDTO.getStatus() != null, BizPurchaseRequest::getStatus, queryDTO.getStatus())
                .ge(startTime != null, BizPurchaseRequest::getCreateTime, startTime)
                .lt(endTime != null, BizPurchaseRequest::getCreateTime, endTime)
                .in(matchedRequestIds != null, BizPurchaseRequest::getId, matchedRequestIds)
                .orderByDesc(BizPurchaseRequest::getId);

        Page<BizPurchaseRequest> page = bizPurchaseRequestMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);

        List<PurchaseRequestVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PurchaseRequestVO getById(Long id) {
        requireModuleReadAccess();
        BizPurchaseRequest entity = requireEntity(id);
        return toVO(entity);
    }

    // ============================== 缺货识别 ==============================

    /**
     * 返回当前库存 ≤ 预警阈值的启用商品清单，供仓储勾选生成采购申请单。
     */
    public List<BaseGoods> listShortageGoods() {
        requireWarehouseAccess();
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getStatus, 1)
                .apply("stock <= warning_stock")
                .orderByAsc(BaseGoods::getStock);
        return baseGoodsMapper.selectList(wrapper);
    }

    // ============================== 仓储建单 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void create(PurchaseRequestSaveDTO dto) {
        requireWarehouseAccess();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizPurchaseRequest entity = new BizPurchaseRequest();
        entity.setRequestNo(CodeGenerator.purchaseRequestNo());
        entity.setStatus(STATUS_PENDING);
        entity.setApplicantId(loginUser.getId());
        entity.setApplicantName(loginUser.getRealName());
        entity.setRemark(dto.getRemark());
        bizPurchaseRequestMapper.insert(entity);

        int sortNo = 0;
        for (PurchaseRequestDetailDTO detail : dto.getDetails()) {
            BaseGoods goods = requireGoods(detail.getGoodsId());
            ensureGoodsEnabled(goods);
            BizPurchaseRequestDetail detailEntity = new BizPurchaseRequestDetail();
            detailEntity.setRequestId(entity.getId());
            detailEntity.setGoodsId(goods.getId());
            detailEntity.setGoodsName(goods.getGoodsName());
            detailEntity.setQuantity(detail.getQuantity());
            detailEntity.setUnitPrice(detail.getUnitPrice());
            detailEntity.setSortNo(detail.getSortNo() == null ? sortNo : detail.getSortNo());
            bizPurchaseRequestDetailMapper.insert(detailEntity);
            sortNo++;
        }

        messageService.sendPurchaseRequestToPurchaseAdmins(entity.getRequestNo(), loginUser.getRealName(), entity.getId());
    }

    // ============================== 采购认领（转采购中） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void process(Long id, PurchaseRequestProcessDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待采购状态可认领");
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PENDING)
                .set(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getOperatorId, loginUser.getId())
                .set(BizPurchaseRequest::getOperatorName, loginUser.getRealName())
                .set(BizPurchaseRequest::getOperationTime, LocalDateTime.now())
                .set(BizPurchaseRequest::getExpectedArrivalTime, dto == null ? null : dto.getExpectedArrivalTime());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单已被处理，禁止重复认领");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }

    // ============================== 采购到货（提交入库申请，不加库存） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void arrive(Long id, PurchaseRequestReceiveDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PURCHASING) {
            throw BusinessException.validateFail("仅采购中状态可提交到货");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法到货");
        }
        Map<Long, BizPurchaseRequestDetail> detailMap = details.stream()
                .collect(Collectors.toMap(BizPurchaseRequestDetail::getId, Function.identity()));

        // 逐条校验并回写明细的到货数量+采购单价（不加库存，待仓储确认）
        for (PurchaseRequestReceiveDTO.ReceiveItemDTO item : dto.getItems()) {
            BizPurchaseRequestDetail detail = detailMap.get(item.getDetailId());
            if (detail == null) {
                throw BusinessException.validateFail("到货明细ID不匹配：" + item.getDetailId());
            }
            LambdaUpdateWrapper<BizPurchaseRequestDetail> detailUpdate = new LambdaUpdateWrapper<>();
            detailUpdate.eq(BizPurchaseRequestDetail::getId, detail.getId())
                    .set(BizPurchaseRequestDetail::getArriveQuantity, item.getQuantity())
                    .set(BizPurchaseRequestDetail::getUnitPrice, item.getUnitPrice());
            bizPurchaseRequestDetailMapper.update(null, detailUpdate);
        }

        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getArriveTime, LocalDateTime.now());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        messageService.sendPurchaseRequestArrivedToWarehouseAdmins(entity.getRequestNo(), loginUser.getRealName(), entity.getId());
    }

    // ============================== 仓储确认入库（加库存） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(Long id) {
        requireWarehouseConfirmAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_AWAITING_CONFIRM) {
            throw BusinessException.validateFail("仅待入库确认状态可确认入库");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法入库");
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        // 逐条转 biz_purchase 加库存（用明细到货数量+采购单价）
        for (BizPurchaseRequestDetail detail : details) {
            Integer qty = detail.getArriveQuantity() != null ? detail.getArriveQuantity() : detail.getQuantity();
            if (qty == null || qty <= 0) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]到货数量无效");
            }
            if (detail.getUnitPrice() == null) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]缺少采购单价");
            }
            PurchaseSaveDTO purchaseDto = new PurchaseSaveDTO();
            purchaseDto.setGoodsId(detail.getGoodsId());
            purchaseDto.setQuantity(qty);
            purchaseDto.setUnitPrice(detail.getUnitPrice());
            purchaseDto.setRemark("采购申请单 " + entity.getRequestNo() + " 入库");
            purchaseService.createInternal(purchaseDto, loginUser.getId(), loginUser.getRealName());
        }

        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getStatus, STATUS_RECEIVED)
                .set(BizPurchaseRequest::getConfirmerId, loginUser.getId())
                .set(BizPurchaseRequest::getConfirmerName, loginUser.getRealName())
                .set(BizPurchaseRequest::getConfirmTime, now)
                .set(BizPurchaseRequest::getReceiveTime, now);
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }

    // ============================== 到货退回（撤回/驳回 → 采购中） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void arriveCancel(Long id) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_AWAITING_CONFIRM) {
            throw BusinessException.validateFail("仅待入库确认状态可撤回到货");
        }
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getArriveTime, null);
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void arriveReject(Long id) {
        requireWarehouseConfirmAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_AWAITING_CONFIRM) {
            throw BusinessException.validateFail("仅待入库确认状态可驳回入库");
        }
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getArriveTime, null);
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }

    // ============================== 驳回 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, PurchaseRequestRejectDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待采购状态可驳回");
        }

        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PENDING)
                .set(BizPurchaseRequest::getStatus, STATUS_REJECTED)
                .set(BizPurchaseRequest::getRejectReason, dto.getReason());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单已被处理，禁止重复驳回");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
    }

    // ============================== 撤销申请 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireWarehouseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId()) && !authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("仅申请人本人可撤销采购申请单");
        }
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待采购状态可撤销");
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
        bizPurchaseRequestMapper.deleteById(id);
        LambdaQueryWrapper<BizPurchaseRequestDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.eq(BizPurchaseRequestDetail::getRequestId, id);
        bizPurchaseRequestDetailMapper.delete(detailWrapper);
    }

    // ============================== 私有辅助 ==============================

    /**
     * 读权限：仓储 + 采购 均可查看采购申请单（仓储看自己建的，采购看流转来的）。
     */
    private void requireModuleReadAccess() {
        authzService.requireAnyDeptAdminOrSuperAdmin(
                "仅仓储/采购管理员可访问采购申请模块", AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PURCHASE);
    }

    private void requireWarehouseAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可识别缺货并创建采购申请单");
    }

    private void requirePurchaseAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PURCHASE, "仅采购管理员可处理/入库/驳回采购申请单");
    }

    private void requireWarehouseConfirmAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可确认采购入库");
    }

    private BizPurchaseRequest requireEntity(Long id) {
        BizPurchaseRequest entity = bizPurchaseRequestMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("采购申请单不存在");
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

    private List<BizPurchaseRequestDetail> listDetails(Long requestId) {
        LambdaQueryWrapper<BizPurchaseRequestDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizPurchaseRequestDetail::getRequestId, requestId)
                .orderByAsc(BizPurchaseRequestDetail::getSortNo)
                .orderByAsc(BizPurchaseRequestDetail::getId);
        return bizPurchaseRequestDetailMapper.selectList(wrapper);
    }

    private PurchaseRequestVO toVO(BizPurchaseRequest entity) {
        PurchaseRequestVO vo = new PurchaseRequestVO();
        vo.setId(entity.getId());
        vo.setRequestNo(entity.getRequestNo());
        vo.setStatus(entity.getStatus());
        vo.setStatusText(statusText(entity.getStatus()));
        vo.setApplicantId(entity.getApplicantId());
        vo.setApplicantName(entity.getApplicantName());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperationTime(entity.getOperationTime());
        vo.setExpectedArrivalTime(entity.getExpectedArrivalTime());
        vo.setArriveTime(entity.getArriveTime());
        vo.setReceiveTime(entity.getReceiveTime());
        vo.setConfirmerId(entity.getConfirmerId());
        vo.setConfirmerName(entity.getConfirmerName());
        vo.setConfirmTime(entity.getConfirmTime());
        vo.setRejectReason(entity.getRejectReason());
        vo.setRemark(entity.getRemark());
        vo.setCreateTime(entity.getCreateTime());
        vo.setIsDeleted(entity.getIsDeleted());
        vo.setDetails(listDetails(entity.getId()).stream().map(this::toDetailVO).toList());
        return vo;
    }

    private PurchaseRequestDetailVO toDetailVO(BizPurchaseRequestDetail detail) {
        PurchaseRequestDetailVO vo = new PurchaseRequestDetailVO();
        vo.setId(detail.getId());
        vo.setRequestId(detail.getRequestId());
        vo.setGoodsId(detail.getGoodsId());
        vo.setGoodsName(detail.getGoodsName());
        vo.setQuantity(detail.getQuantity());
        vo.setArriveQuantity(detail.getArriveQuantity());
        vo.setUnitPrice(detail.getUnitPrice());
        vo.setSortNo(detail.getSortNo());
        return vo;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return null;
        }
        return switch (status) {
            case STATUS_PENDING -> "待采购";
            case STATUS_PURCHASING -> "采购中";
            case STATUS_RECEIVED -> "已入库";
            case STATUS_REJECTED -> "已驳回";
            case STATUS_AWAITING_CONFIRM -> "待入库确认";
            default -> String.valueOf(status);
        };
    }
}
