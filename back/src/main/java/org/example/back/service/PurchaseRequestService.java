package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.PurchaseRequestDetailDTO;
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

        messageService.sendPurchaseRequestToPurchaseAdmins(entity.getRequestNo(), loginUser.getRealName());
    }

    // ============================== 采购认领（转采购中） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void process(Long id) {
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
                .set(BizPurchaseRequest::getOperationTime, LocalDateTime.now());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单已被处理，禁止重复认领");
        }
    }

    // ============================== 采购入库（转 biz_purchase） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void receive(Long id, PurchaseRequestReceiveDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PURCHASING) {
            throw BusinessException.validateFail("仅采购中状态可入库");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法入库");
        }

        Map<Long, BizPurchaseRequestDetail> detailMap = details.stream()
                .collect(Collectors.toMap(BizPurchaseRequestDetail::getId, Function.identity()));

        // 校验入库项与明细一致，并逐条转 biz_purchase（复用 PurchaseService.create 加库存）
        for (PurchaseRequestReceiveDTO.ReceiveItemDTO item : dto.getItems()) {
            BizPurchaseRequestDetail detail = detailMap.get(item.getDetailId());
            if (detail == null) {
                throw BusinessException.validateFail("入库明细ID不匹配：" + item.getDetailId());
            }
            PurchaseSaveDTO purchaseDto = new PurchaseSaveDTO();
            purchaseDto.setGoodsId(detail.getGoodsId());
            purchaseDto.setQuantity(item.getQuantity());
            purchaseDto.setUnitPrice(item.getUnitPrice());
            purchaseDto.setRemark("采购申请单 " + entity.getRequestNo() + " 转入库");
            purchaseService.create(purchaseDto);

            // 回写明细单价（便于追溯）
            LambdaUpdateWrapper<BizPurchaseRequestDetail> detailUpdate = new LambdaUpdateWrapper<>();
            detailUpdate.eq(BizPurchaseRequestDetail::getId, detail.getId())
                    .set(BizPurchaseRequestDetail::getUnitPrice, item.getUnitPrice());
            bizPurchaseRequestDetailMapper.update(null, detailUpdate);
        }

        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getStatus, STATUS_RECEIVED)
                .set(BizPurchaseRequest::getReceiveTime, LocalDateTime.now());
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
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
        vo.setReceiveTime(entity.getReceiveTime());
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
            default -> String.valueOf(status);
        };
    }
}
