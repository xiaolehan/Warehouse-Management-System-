package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.dto.ProductionDraftItemDTO;
import org.example.back.dto.PurchaseRequestDetailDTO;
import org.example.back.dto.PurchaseRequestProcessDTO;
import org.example.back.dto.PurchaseRequestQueryDTO;
import org.example.back.dto.PurchaseRequestReceiveDTO;
import org.example.back.dto.PurchaseRequestRejectDTO;
import org.example.back.dto.PurchaseRequestSaveDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.KitShortageVO;
import org.example.back.vo.PurchaseRequestDetailVO;
import org.example.back.vo.PurchaseRequestVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
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

    public static final String SOURCE_PRODUCTION = "production";
    public static final String SOURCE_WAREHOUSE = "warehouse";

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

    @Autowired
    private ProductionOrderService productionOrderService;

    @Autowired
    private GoodsService goodsService;

    @Autowired
    private BizBomDetailMapper bizBomDetailMapper;

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

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
                .eq(StringUtils.hasText(queryDTO.getSourceType()), BizPurchaseRequest::getSourceType, queryDTO.getSourceType())
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
     * 返回当前库存 ≤ 预警阈值的启用商品清单，供仓储勾选生成采购申请单（成品除外，D65：成品不参与缺货识别）。
     */
    public List<BaseGoods> listShortageGoods() {
        requireWarehouseAccess();
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getStatus, 1)
                .apply("stock <= warning_stock")
                .orderByAsc(BaseGoods::getStock);
        GoodsService.excludeProducts(wrapper); // D65：成品不参与缺货识别
        return baseGoodsMapper.selectList(wrapper);
    }

    // ============================== 生产缺料草稿 ==============================

    /**
     * 生产一键补料：从生产任务单缺料行生成采购申请草稿(DRAFT)。幂等——同一任务单只允许一张草稿。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(ProductionDraftCreateDTO dto) {
        requireProductionDraftAccess();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        // 幂等：同一生产任务单已有进行中/已入库的补料单则拒绝（仅 rejected 可重新补料）
        List<BizPurchaseRequest> existing = listNonFinalByProductionOrder(dto.getProductionOrderId());
        if (!existing.isEmpty()) {
            throw BusinessException.validateFail(
                    String.format(Locale.ROOT, "该生产任务单已补料（单号 %s），请勿重复",
                            existing.get(0).getRequestNo()));
        }

        List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(dto.getProductionOrderId());
        if (shortage.isEmpty()) {
            throw BusinessException.validateFail("该生产任务单当前无缺料，无需补料");
        }
        Map<Long, Integer> override = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null && i.getQuantity() != null)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, ProductionDraftItemDTO::getQuantity));
        Map<Long, Long> overrideGoods = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null && i.getGoodsId() != null)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, ProductionDraftItemDTO::getGoodsId, (a, b) -> b));
        Map<Long, ProductionDraftItemDTO> itemByBomDetail = dto.getDetails() == null ? Map.of()
                : dto.getDetails().stream()
                        .filter(i -> i.getBomDetailId() != null)
                        .collect(Collectors.toMap(ProductionDraftItemDTO::getBomDetailId, Function.identity(), (a, b) -> b));

        BizPurchaseRequest request = new BizPurchaseRequest();
        request.setRequestNo(CodeGenerator.purchaseRequestNo());
        request.setStatus(STATUS_PENDING);
        request.setSourceType(SOURCE_PRODUCTION);
        request.setProductionOrderId(dto.getProductionOrderId());
        request.setApplicantId(loginUser.getId());
        request.setApplicantName(loginUser.getRealName());
        request.setRemark(dto.getRemark());
        bizPurchaseRequestMapper.insert(request);

        int sortNo = 0;
        for (KitShortageVO line : shortage) {
            Integer qty = override.getOrDefault(line.getBomDetailId(), ceilDeficit(line.getDeficit()));
            if (qty == null || qty <= 0) {
                continue;
            }
            // D60/ADR-0002：改绑已有物料优先；未绑定的未知物料行内联建档并回绑 BOM 行
            Long goodsId = overrideGoods.getOrDefault(line.getBomDetailId(), line.getGoodsId());
            boolean isNewMaterial = false;
            BaseGoods reboundGoods = null;
            ProductionDraftItemDTO item = itemByBomDetail.get(line.getBomDetailId());
            if (goodsId == null) {
                if (item == null || !StringUtils.hasText(item.getNewGoodsName())) {
                    throw BusinessException.validateFail(
                            "物料[" + line.getGoodsName() + "]未在仓库建档，请填写新物料信息或改绑已有物料");
                }
                isNewMaterial = true;
                goodsId = goodsService.createMaterialFromProduction(
                        item.getNewGoodsName(), item.getSpec(), item.getMaterial(), item.getUnit());
                bindBomDetail(line.getBomDetailId(), goodsId, item);
            } else if (overrideGoods.containsKey(line.getBomDetailId())) {
                // D60：改绑已有物料——快照取改绑目标物料档案（名称/规格/材质以主数据为准，避免沿用旧 BOM 行信息）
                reboundGoods = requireGoods(goodsId);
            }

            BizPurchaseRequestDetail det = new BizPurchaseRequestDetail();
            det.setRequestId(request.getId());
            det.setGoodsId(goodsId);
            det.setQuantity(qty);
            det.setBomDetailId(line.getBomDetailId());
            if (isNewMaterial) {
                // 快照取行内编辑后的值（与回绑 BOM 行一致）
                det.setGoodsName(item.getNewGoodsName().trim());
                det.setSpec(StringUtils.hasText(item.getSpec()) ? item.getSpec().trim() : null);
                det.setMaterial(StringUtils.hasText(item.getMaterial()) ? item.getMaterial().trim() : null);
                det.setRemark(StringUtils.hasText(item.getRemark()) ? item.getRemark().trim() : null);
                det.setIsNewMaterial(1);
            } else if (reboundGoods != null) {
                det.setGoodsName(reboundGoods.getGoodsName());
                det.setSpec(reboundGoods.getSpec());
                det.setMaterial(reboundGoods.getMaterial());
                det.setRemark(line.getRemark());
                det.setIsNewMaterial(0);
            } else {
                det.setGoodsName(line.getGoodsName());
                det.setSpec(line.getSpec());
                det.setMaterial(line.getMaterial());
                det.setRemark(line.getRemark());
                det.setIsNewMaterial(0);
            }
            det.setSortNo(sortNo++);
            bizPurchaseRequestDetailMapper.insert(det);
        }
        if (sortNo == 0) {
            throw BusinessException.validateFail("无有效缺料行可补料");
        }

        messageService.sendPurchaseRequestToPurchaseAdmins(request.getRequestNo(), loginUser.getRealName(), request.getId());
        return request.getId();
    }

    private static int ceilDeficit(java.math.BigDecimal deficit) {
        return deficit.setScale(0, java.math.RoundingMode.UP).intValue();
    }

    /** D60/ADR-0002：未知物料自动建档后回绑 BOM 行——goodsId/名称/规格/材质/备注与建档信息对齐，保持 BOM 即时准确 */
    private void bindBomDetail(Long bomDetailId, Long goodsId, ProductionDraftItemDTO item) {
        BizBomDetail bomDetail = bizBomDetailMapper.selectById(bomDetailId);
        if (bomDetail == null) {
            return;
        }
        bomDetail.setGoodsId(goodsId);
        if (StringUtils.hasText(item.getNewGoodsName())) {
            bomDetail.setComponentName(item.getNewGoodsName().trim());
        }
        // null 视为未传（保持原值），空串视为用户清空（写 NULL）
        if (item.getSpec() != null) {
            bomDetail.setSpec(StringUtils.hasText(item.getSpec()) ? item.getSpec().trim() : null);
        }
        if (item.getMaterial() != null) {
            bomDetail.setMaterial(StringUtils.hasText(item.getMaterial()) ? item.getMaterial().trim() : null);
        }
        if (item.getRemark() != null) {
            bomDetail.setRemark(StringUtils.hasText(item.getRemark()) ? item.getRemark().trim() : null);
        }
        bizBomDetailMapper.updateById(bomDetail);
    }

    private List<BizPurchaseRequest> listNonFinalByProductionOrder(Long productionOrderId) {
        LambdaQueryWrapper<BizPurchaseRequest> w = new LambdaQueryWrapper<>();
        w.eq(BizPurchaseRequest::getProductionOrderId, productionOrderId)
                .eq(BizPurchaseRequest::getSourceType, SOURCE_PRODUCTION)
                .ne(BizPurchaseRequest::getStatus, STATUS_REJECTED);
        return bizPurchaseRequestMapper.selectList(w);
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
            GoodsService.ensureGoodsType(goods, GoodsService.GOODS_TYPE_MATERIAL, "采购申请只可选择物料（type=material）"); // D67
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

    // ============================== 采购认领（转采购中，行级到货计划 D61） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void process(Long id, PurchaseRequestProcessDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PENDING) {
            throw BusinessException.validateFail("仅待采购状态可认领");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法认领");
        }
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = toValidatedItemMap(dto, details);

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
        // 行级写入预计到货时间+到货备注
        for (BizPurchaseRequestDetail detail : details) {
            PurchaseRequestProcessDTO.ProcessItemDTO item = itemMap.get(detail.getId());
            detail.setExpectedArrivalTime(item.getExpectedArrivalTime());
            detail.setArrivalRemark(trimToNull(item.getArrivalRemark()));
            bizPurchaseRequestDetailMapper.updateById(detail);
        }
        messageService.revokeUnreadByBiz("purchase_request", id);
        messageService.sendPurchaseRequestClaimedToSourceApplicant(
                entity.getRequestNo(), loginUser.getRealName(), entity.getSourceType(),
                buildArrivalSummary(details, itemMap), id);
    }

    // ============================== 修改到货计划（采购中可改，D61） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void updateArrivalPlan(Long id, PurchaseRequestProcessDTO dto) {
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_PURCHASING) {
            throw BusinessException.validateFail("仅采购中状态可修改到货计划");
        }
        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = toValidatedItemMap(dto, details);
        for (BizPurchaseRequestDetail detail : details) {
            PurchaseRequestProcessDTO.ProcessItemDTO item = itemMap.get(detail.getId());
            detail.setExpectedArrivalTime(item.getExpectedArrivalTime());
            detail.setArrivalRemark(trimToNull(item.getArrivalRemark()));
            bizPurchaseRequestDetailMapper.updateById(detail);
        }
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
        notifyKitCompleteIfReady(entity);
    }

    /**
     * D62：生产补料入库确认后重算齐套，缺口清零即通知生产部管理员可申请领料。
     * 仅 production 来源且生产单存在（未删除/未作废/未报废）时触发；仍缺料则沉默，
     * 靠生产任务单列表实时齐套状态兜底。任何守卫命中都静默返回，不影响入库事务。
     * selectById 自带 @TableLogic 过滤，软删生产单同样返回 null。
     */
    private void notifyKitCompleteIfReady(BizPurchaseRequest request) {
        if (!SOURCE_PRODUCTION.equals(request.getSourceType()) || request.getProductionOrderId() == null) {
            return;
        }
        BizProductionOrder order = bizProductionOrderMapper.selectById(request.getProductionOrderId());
        if (order == null
                || order.getStatus() == null
                || order.getStatus() == BizProductionOrder.STATUS_VOIDED
                || order.getStatus() == BizProductionOrder.STATUS_SCRAPPED
                || order.getStatus() == BizProductionOrder.STATUS_TERMINATED) {
            return;
        }
        List<KitShortageVO> shortage = productionOrderService.computeShortageForOrder(order.getId());
        if (!shortage.isEmpty()) {
            return;
        }
        messageService.sendKitCompleteToProductionAdmins(
                order.getOrderNo(), order.getGoodsName(), order.getQuantity(),
                request.getRequestNo(), order.getId());
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
        messageService.revokeUnreadByBizAndDeptCode("purchase_request", id, AuthzService.DEPT_WAREHOUSE);
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
        messageService.revokeUnreadByBizAndDeptCode("purchase_request", id, AuthzService.DEPT_WAREHOUSE);
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

    /** D61：校验认领/修改到货计划的行级 items——逐行必填时间、detailId 必须与单据明细一一对应 */
    private Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> toValidatedItemMap(
            PurchaseRequestProcessDTO dto, List<BizPurchaseRequestDetail> details) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请填写各行预计到货时间");
        }
        Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap = new java.util.HashMap<>();
        for (PurchaseRequestProcessDTO.ProcessItemDTO item : dto.getItems()) {
            if (item.getDetailId() == null) {
                throw BusinessException.validateFail("明细ID不能为空");
            }
            if (item.getExpectedArrivalTime() == null) {
                throw BusinessException.validateFail("预计到货时间必填");
            }
            itemMap.put(item.getDetailId(), item);
        }
        for (BizPurchaseRequestDetail detail : details) {
            if (!itemMap.containsKey(detail.getId())) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]缺少预计到货时间");
            }
        }
        return itemMap;
    }

    /** D61：行级到货摘要（消息用），格式「轴承 2026-09-15；钢板 2026-09-20」 */
    private String buildArrivalSummary(List<BizPurchaseRequestDetail> details,
                                       Map<Long, PurchaseRequestProcessDTO.ProcessItemDTO> itemMap) {
        return details.stream()
                .map(d -> d.getGoodsName() + " " + itemMap.get(d.getId()).getExpectedArrivalTime().toLocalDate())
                .collect(Collectors.joining("；"));
    }

    private String trimToNull(String text) {
        return StringUtils.hasText(text) ? text.trim() : null;
    }

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

    private void requireProductionDraftAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION, "仅生产研发部管理员可生成/管理补料草稿");
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
        vo.setSourceType(entity.getSourceType());
        vo.setProductionOrderId(entity.getProductionOrderId());
        vo.setApplicantId(entity.getApplicantId());
        vo.setApplicantName(entity.getApplicantName());
        vo.setOperatorId(entity.getOperatorId());
        vo.setOperatorName(entity.getOperatorName());
        vo.setOperationTime(entity.getOperationTime());
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
        vo.setBomDetailId(detail.getBomDetailId());
        vo.setGoodsName(detail.getGoodsName());
        vo.setSpec(detail.getSpec());
        vo.setMaterial(detail.getMaterial());
        vo.setRemark(detail.getRemark());
        vo.setIsNewMaterial(detail.getIsNewMaterial());
        vo.setQuantity(detail.getQuantity());
        vo.setExpectedArrivalTime(detail.getExpectedArrivalTime());
        vo.setArrivalRemark(detail.getArrivalRemark());
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
