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
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
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
import java.util.ArrayList;
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

    /**
     * D86：补料幂等守卫的在途状态集合——仅在途单（待采购/采购中/待入库确认）阻止再补料；
     * 已入库(3)为终态不阻止（部分到货终态后剩余缺口可再补），已驳回(4)本就可重新发起。
     */
    public static final List<Integer> IN_FLIGHT_STATUSES =
            List.of(STATUS_PENDING, STATUS_PURCHASING, STATUS_AWAITING_CONFIRM);

    public static final String SOURCE_PRODUCTION = "production";
    public static final String SOURCE_WAREHOUSE = "warehouse";

    @Autowired
    private BizPurchaseRequestMapper bizPurchaseRequestMapper;

    @Autowired
    private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

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
     * 返回当前库存 ≤ 预警阈值的启用商品清单，供生产勾选生成采购申请单（成品除外，D65：成品不参与缺货识别；D130 创建权仓储→生产）。
     */
    public List<BaseGoods> listShortageGoods() {
        requireProductionAccess();
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getStatus, 1)
                .apply("stock <= warning_stock")
                .orderByAsc(BaseGoods::getStock);
        GoodsService.excludeProducts(wrapper); // D65：成品不参与缺货识别
        return baseGoodsMapper.selectList(wrapper);
    }

    // ============================== 生产缺料草稿 ==============================

    /**
     * 生产一键补料：从生产任务单缺料行生成采购申请草稿(DRAFT)。幂等——同一任务单至多一张在途补料单（D86）。
     */
    @Transactional(rollbackFor = Exception.class)
    public Long createDraft(ProductionDraftCreateDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionDraftAccess();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        // 幂等（D86）：同一生产任务单已有在途补料单则拒绝；已入库/已驳回不占用名额，
        // 部分到货终态后的剩余缺口可再次补料（重复补料由下方"无缺料"实时重算兜底拦截）
        List<BizPurchaseRequest> existing = listInFlightByProductionOrder(dto.getProductionOrderId());
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

    /**
     * D86：在途补料单查询——同一生产任务单至多一张（createDraft 幂等守卫保证）。
     * 仅含在途状态（1待采购/2采购中/5待入库确认）；已入库(3)/已驳回(4)不视为在途。
     */
    private List<BizPurchaseRequest> listInFlightByProductionOrder(Long productionOrderId) {
        LambdaQueryWrapper<BizPurchaseRequest> w = new LambdaQueryWrapper<>();
        w.eq(BizPurchaseRequest::getProductionOrderId, productionOrderId)
                .eq(BizPurchaseRequest::getSourceType, SOURCE_PRODUCTION)
                .in(BizPurchaseRequest::getStatus, IN_FLIGHT_STATUSES);
        return bizPurchaseRequestMapper.selectList(w);
    }

    // ============================== 建单（D130：生产管理员） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void create(PurchaseRequestSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireProductionAccess();
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
        authzService.requireNotSuperAdminForBusinessWrite();
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
                entity.getRequestNo(), loginUser.getRealName(),
                buildArrivalSummary(details, itemMap), id);
    }

    // ============================== 修改到货计划（采购中可改，D61） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void updateArrivalPlan(Long id, PurchaseRequestProcessDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
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

    // ============================== 采购到货（勾选行按批提交，不加库存；D120） ==============================

    /** D120 行级接收状态：1-待到货, 2-本批待入库确认, 3-已入库 */
    public static final int RECEIVE_PENDING = 1;
    public static final int RECEIVE_AWAITING = 2;
    public static final int RECEIVE_DONE = 3;

    /** 行级接收状态相等判断（避免逐处装箱比较） */
    private static boolean receiveStatusIs(BizPurchaseRequestDetail detail, int expected) {
        return detail != null && Integer.valueOf(expected).equals(detail.getReceiveStatus());
    }

    @Transactional(rollbackFor = Exception.class)
    public void arrive(Long id, PurchaseRequestReceiveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseAccess();
        BizPurchaseRequest entity = requireEntity(id);
        // 纯采购中(2)与部分入库后回到 2 均可继续勾选未到货行提交
        if (entity.getStatus() != STATUS_PURCHASING) {
            throw BusinessException.validateFail("仅采购中状态可提交到货");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法到货");
        }
        if (details.stream().anyMatch(d -> receiveStatusIs(d, RECEIVE_AWAITING))) {
            // 理论上头=2 时不存在待确认行，防御性兜底
            throw BusinessException.validateFail("存在待入库确认的批次，请先确认或驳回后再提交到货");
        }
        Map<Long, BizPurchaseRequestDetail> detailMap = details.stream()
                .collect(Collectors.toMap(BizPurchaseRequestDetail::getId, Function.identity()));

        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请勾选本次到货的明细行");
        }
        // 勾选行：必须属于本单、当前待到货(1)（已入库行不可再勾选）、不重复、单价有效；
        // 不传数量——按申请数量整行到货（行内数量不拆）
        List<BizPurchaseRequestDetail> batchLines = new ArrayList<>();
        Set<Long> pickedIds = new java.util.HashSet<>();
        for (PurchaseRequestReceiveDTO.ReceiveItemDTO item : dto.getItems()) {
            BizPurchaseRequestDetail detail = detailMap.get(item.getDetailId());
            if (detail == null) {
                throw BusinessException.validateFail("到货明细ID不匹配：" + item.getDetailId());
            }
            if (!pickedIds.add(detail.getId())) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]重复勾选");
            }
            if (!receiveStatusIs(detail, RECEIVE_PENDING)) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]已入库，不可重复到货");
            }
            if (item.getUnitPrice() == null || item.getUnitPrice().signum() <= 0) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]采购单价必须大于0");
            }
            // D131：行级供应商必选（预填物料绑定供应商可改），不得为「系统默认供应商」锚点
            if (item.getSupplierId() == null) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]请选择供应商");
            }
            if (GoodsService.DEFAULT_SUPPLIER_ID.equals(item.getSupplierId())) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]不能选择系统默认供应商");
            }
            BaseSupplier supplier = baseSupplierMapper.selectById(item.getSupplierId());
            if (supplier == null) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]供应商不存在");
            }
            if (!Integer.valueOf(1).equals(supplier.getStatus())) {
                throw BusinessException.validateFail(
                        "明细[" + detail.getGoodsName() + "]供应商已停用，请重新选择");
            }
            batchLines.add(detail);
        }

        String batchNo = nextBatchNo(details);
        LocalDateTime now = LocalDateTime.now();
        Map<Long, PurchaseRequestReceiveDTO.ReceiveItemDTO> itemMap = dto.getItems().stream()
                .collect(Collectors.toMap(PurchaseRequestReceiveDTO.ReceiveItemDTO::getDetailId, Function.identity()));
        for (BizPurchaseRequestDetail detail : batchLines) {
            PurchaseRequestReceiveDTO.ReceiveItemDTO item = itemMap.get(detail.getId());
            LambdaUpdateWrapper<BizPurchaseRequestDetail> detailUpdate = new LambdaUpdateWrapper<>();
            detailUpdate.eq(BizPurchaseRequestDetail::getId, detail.getId())
                    .set(BizPurchaseRequestDetail::getReceiveStatus, RECEIVE_AWAITING)
                    .set(BizPurchaseRequestDetail::getArriveBatchNo, batchNo)
                    .set(BizPurchaseRequestDetail::getArriveBatchTime, now)
                    .set(BizPurchaseRequestDetail::getArriveQuantity, detail.getQuantity())
                    .set(BizPurchaseRequestDetail::getUnitPrice, item.getUnitPrice())
                    .set(BizPurchaseRequestDetail::getSupplierId, item.getSupplierId());
            bizPurchaseRequestDetailMapper.update(null, detailUpdate);
        }

        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                .set(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getArriveTime, now);
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        messageService.sendPurchaseRequestArrivedToWarehouseAdmins(
                entity.getRequestNo(), loginUser.getRealName(), batchNo, batchLines.size(), entity.getId());
    }

    /** D120：批次号 = 既有最大批次序号 +1（B1/B2…）；无历史批次时为 B1 */
    private static String nextBatchNo(List<BizPurchaseRequestDetail> details) {
        int max = 0;
        for (BizPurchaseRequestDetail d : details) {
            String no = d.getArriveBatchNo();
            if (no != null && no.startsWith("B")) {
                try {
                    max = Math.max(max, Integer.parseInt(no.substring(1)));
                } catch (NumberFormatException ignored) {
                    // 非 B+数字 的异常值忽略
                }
            }
        }
        return "B" + (max + 1);
    }

    // ============================== 仓储确认入库（加库存） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseConfirmAccess();
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_AWAITING_CONFIRM) {
            throw BusinessException.validateFail("仅待入库确认状态可确认入库");
        }

        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        if (details.isEmpty()) {
            throw BusinessException.validateFail("采购申请明细为空，无法入库");
        }
        // 只确认本批（receive_status=2）；历史批次(3)与未到货行(1)不动
        List<BizPurchaseRequestDetail> batchLines = details.stream()
                .filter(d -> receiveStatusIs(d, RECEIVE_AWAITING))
                .toList();
        if (batchLines.isEmpty()) {
            throw BusinessException.validateFail("本批到货明细不存在，请刷新后重试");
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        // D120：每批确认生成「一张」多行进货单（ADR-0015），行数量=申请量整行
        List<PurchaseSaveDTO.LineDTO> receiptLines = new ArrayList<>();
        for (BizPurchaseRequestDetail detail : batchLines) {
            if (detail.getQuantity() == null || detail.getQuantity() <= 0) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]数量无效");
            }
            if (detail.getUnitPrice() == null) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName() + "]缺少采购单价");
            }
            if (detail.getSupplierId() == null) {
                throw BusinessException.validateFail("明细[" + detail.getGoodsName()
                        + "]缺少供应商，请采购撤回本批到货或仓储驳回后重新到货提交并选择供应商");
            }
            PurchaseSaveDTO.LineDTO line = new PurchaseSaveDTO.LineDTO();
            line.setGoodsId(detail.getGoodsId());
            line.setQuantity(detail.getQuantity());
            line.setUnitPrice(detail.getUnitPrice());
            line.setSupplierId(detail.getSupplierId()); // D131 行级供应商随行复制
            receiptLines.add(line);
        }
        String batchNo = batchLines.get(0).getArriveBatchNo();
        PurchaseSaveDTO receipt = new PurchaseSaveDTO();
        receipt.setLines(receiptLines);
        receipt.setRemark("采购申请单 " + entity.getRequestNo() + " 第 " + batchNo + " 批入库");
        purchaseService.createInternal(receipt, loginUser.getId(), loginUser.getRealName());

        LocalDateTime now = LocalDateTime.now();
        // 本批行 2→3，记本批入库时间（批次号保留）
        for (BizPurchaseRequestDetail detail : batchLines) {
            bizPurchaseRequestDetailMapper.update(null,
                    new LambdaUpdateWrapper<BizPurchaseRequestDetail>()
                            .eq(BizPurchaseRequestDetail::getId, detail.getId())
                            .set(BizPurchaseRequestDetail::getReceiveStatus, RECEIVE_DONE)
                            .set(BizPurchaseRequestDetail::getReceiveBatchTime, now));
        }

        // 全部入库 = 历史已入库行 + 本批行覆盖所有明细（本批行刚写库置3）
        Set<Long> batchIds = batchLines.stream()
                .map(BizPurchaseRequestDetail::getId).collect(Collectors.toSet());
        boolean allDone = details.stream()
                .allMatch(d -> receiveStatusIs(d, RECEIVE_DONE)
                        || batchIds.contains(d.getId()));
        LambdaUpdateWrapper<BizPurchaseRequest> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchaseRequest::getId, entity.getId())
                .eq(BizPurchaseRequest::getStatus, STATUS_AWAITING_CONFIRM)
                .set(BizPurchaseRequest::getConfirmerId, loginUser.getId())
                .set(BizPurchaseRequest::getConfirmerName, loginUser.getRealName())
                .set(BizPurchaseRequest::getConfirmTime, now);
        if (allDone) {
            // 全部行入库 → 终态 3
            updateWrapper.set(BizPurchaseRequest::getStatus, STATUS_RECEIVED)
                    .set(BizPurchaseRequest::getReceiveTime, now);
        } else {
            // 仍有未入库行 → 回到采购中(2)，可继续勾选到货；前端文案「部分入库」
            updateWrapper.set(BizPurchaseRequest::getStatus, STATUS_PURCHASING)
                    .set(BizPurchaseRequest::getArriveTime, null);
        }
        int rows = bizPurchaseRequestMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("采购申请单状态已变更，请刷新后重试");
        }
        if (allDone) {
            // 终态：本 biz 所有未读一并回收
            messageService.revokeUnreadByBiz("purchase_request", id);
        } else {
            // 部分入库：只撤本批到货待办（按标题白名单），认领等他条通知不动
            messageService.revokeUnreadByBizAndTitles("purchase_request", id,
                    List.of(MessageService.TITLE_PURCHASE_REQUEST_ARRIVED));
        }
        notifyKitAfterReceive(entity);
    }

    /**
     * D62+D87：生产补料入库确认后重算齐套——缺口清零发「物料已齐套可领料」，仍缺料发「补料部分到货仍缺料」（D86 已解锁再补）。
     * 两通知互斥：先按标题白名单撤齐套类旧通知再按结果发新（不动 D73 销售取消等其他通知，亦不堆积）。
     * 仅 production 来源且生产单存在（未删除/未作废/未报废/未终止）时触发。任何守卫命中都静默返回，不影响入库事务。
     * selectById 自带 @TableLogic 过滤，软删生产单同样返回 null。
     */
    private void notifyKitAfterReceive(BizPurchaseRequest request) {
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
        messageService.revokeUnreadByBizAndTitles("production_order", order.getId(), MessageService.KIT_FAMILY_TITLES);
        if (shortage.isEmpty()) {
            messageService.sendKitCompleteToProductionAdmins(
                    order.getOrderNo(), order.getGoodsName(), order.getQuantity(),
                    request.getRequestNo(), order.getId());
        } else {
            messageService.sendKitIncompleteToProductionAdmins(
                    order.getOrderNo(), order.getGoodsName(), order.getQuantity(),
                    request.getRequestNo(), shortageSummary(shortage), order.getId());
        }
    }

    /** D87：仍缺料通知的缺口摘要——「物料名×缺口整数」顿号连接（缺口向上取整，与补料默认申请量口径一致）。 */
    private static String shortageSummary(List<KitShortageVO> shortage) {
        return shortage.stream()
                .map(line -> (line.getGoodsName() == null ? "-" : line.getGoodsName()) + "×" + ceilDeficit(line.getDeficit()))
                .collect(Collectors.joining("、"));
    }

    // ============================== 到货退回（撤回/驳回：只退本批 → 采购中；D120） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void arriveCancel(Long id) {
        resetCurrentBatch(id, true);
    }

    @Transactional(rollbackFor = Exception.class)
    public void arriveReject(Long id) {
        resetCurrentBatch(id, false);
    }

    /**
     * D120：撤回到货(采购)/驳回入库(仓储)的共同实现——只把本批行(receive_status=2)
     * 回置待到货(1)并清批次信息，已入库历史批次(3)不动；头 5→2。
     */
    private void resetCurrentBatch(Long id, boolean purchaseAction) {
        authzService.requireNotSuperAdminForBusinessWrite();
        if (purchaseAction) {
            requirePurchaseAccess();
        } else {
            requireWarehouseConfirmAccess();
        }
        BizPurchaseRequest entity = requireEntity(id);
        if (entity.getStatus() != STATUS_AWAITING_CONFIRM) {
            throw BusinessException.validateFail(
                    purchaseAction ? "仅待入库确认状态可撤回到货" : "仅待入库确认状态可驳回入库");
        }
        List<BizPurchaseRequestDetail> details = listDetails(entity.getId());
        List<BizPurchaseRequestDetail> batchLines = details.stream()
                .filter(d -> receiveStatusIs(d, RECEIVE_AWAITING))
                .toList();
        if (batchLines.isEmpty()) {
            throw BusinessException.validateFail("本批到货明细不存在，请刷新后重试");
        }
        for (BizPurchaseRequestDetail detail : batchLines) {
            bizPurchaseRequestDetailMapper.update(null,
                    new LambdaUpdateWrapper<BizPurchaseRequestDetail>()
                            .eq(BizPurchaseRequestDetail::getId, detail.getId())
                            .set(BizPurchaseRequestDetail::getReceiveStatus, RECEIVE_PENDING)
                            .set(BizPurchaseRequestDetail::getArriveBatchNo, null)
                            .set(BizPurchaseRequestDetail::getArriveBatchTime, null)
                            .set(BizPurchaseRequestDetail::getArriveQuantity, null));
            // 单价保留预填，下次提交可直接沿用或修改
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
        // 按标题白名单只撤本批到货待办，认领等他条通知不撤（D21+ADR-0016）
        messageService.revokeUnreadByBizAndTitles("purchase_request", id,
                List.of(MessageService.TITLE_PURCHASE_REQUEST_ARRIVED));
    }

    // ============================== 驳回 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, PurchaseRequestRejectDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
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
        authzService.requireNotSuperAdminForBusinessWrite();
        // D130：申请人本人可撤（去部门条件）——普通申请（生产建）与补料草稿（生产建）统一，
        // 修复补料草稿「仓储+本人」双条件无人能撤的死路
        BizPurchaseRequest entity = requireEntity(id);
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        if (!entity.getApplicantId().equals(loginUser.getId())) {
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
     * 读权限：仓储 + 采购 + 生产 均可查看采购申请单（D130：生产创建普通申请，仓储只读+确认入库）。
     */
    private void requireModuleReadAccess() {
        authzService.requireAnyDeptAdminOrSuperAdmin(
                "仅仓储/采购/生产管理员可访问采购申请模块",
                AuthzService.DEPT_WAREHOUSE, AuthzService.DEPT_PURCHASE, AuthzService.DEPT_PRODUCTION);
    }

    /** D130：普通采购申请创建权归生产管理员（仓储回归只管出入库）——建单与缺货识别同口径 */
    private void requireProductionAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION, "仅生产管理员可识别缺货并创建采购申请单");
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
        // 先填明细再派生状态文案——「部分入库」依赖行级 receive_status（D120）
        vo.setDetails(listDetails(entity.getId()).stream().map(this::toDetailVO).toList());
        vo.setStatusText(statusText(entity.getStatus(), vo.getDetails()));
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
        vo.setSupplierId(detail.getSupplierId());
        vo.setReceiveStatus(detail.getReceiveStatus());
        vo.setArriveBatchNo(detail.getArriveBatchNo());
        vo.setArriveBatchTime(detail.getArriveBatchTime());
        vo.setReceiveBatchTime(detail.getReceiveBatchTime());
        vo.setSortNo(detail.getSortNo());
        return vo;
    }

    /**
     * D120：状态文案——头=2 且已有行入库（receive_status=3）时显示「部分入库」，
     * 否则按头状态显示（部分入库不新增头状态值，由行状态派生）。
     */
    private String statusText(Integer status, List<PurchaseRequestDetailVO> details) {
        if (status == null) {
            return null;
        }
        if (status == STATUS_PURCHASING && details != null
                && details.stream().anyMatch(d -> Integer.valueOf(RECEIVE_DONE).equals(d.getReceiveStatus()))) {
            return "部分入库";
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
