package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.PurchaseQueryDTO;
import org.example.back.dto.PurchaseSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.vo.PurchaseDetailVO;
import org.example.back.vo.PurchaseSourceOptionLineVO;
import org.example.back.vo.PurchaseSourceOptionVO;
import org.example.back.vo.PurchaseVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class PurchaseService {

    public static final int CONFIRM_PENDING = 1;    // 待到货
    public static final int CONFIRM_AWAITING = 2;   // 待入库确认
    public static final int CONFIRM_RECEIVED = 3;   // 已入库

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BizPurchaseDetailMapper bizPurchaseDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    @Autowired
    private PurchaseReturnService purchaseReturnService;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;

    private void requirePurchaseModuleAccess() {
        // D32：采购部门成员（admin+员工）可建进货/到货提交（不动库存，待仓储确认入库）
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购部门可访问进货模块");
    }

    /**
     * 采购管理员权限（admin）：删除当天进货单收口 admin（D32：员工仅 create+read+到货）。
     */
    private void requirePurchaseAdminOrSuperAdmin() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购管理员可执行该操作");
    }

    /**
     * 读权限：采购 + 仓储 均可查看进货单（仓储需查看待确认入库的单据）。
     */
    private void requirePurchaseReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅采购/仓储部门可访问进货模块", AuthzService.DEPT_PURCHASE, AuthzService.DEPT_WAREHOUSE);
    }

    public PageResult<PurchaseVO> page(PurchaseQueryDTO queryDTO) {
        requirePurchaseReadAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizPurchase> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getPurchaseNo()), BizPurchase::getPurchaseNo, queryDTO.getPurchaseNo());

        // D111：物料名/物料id/供应商过滤全部下沉明细行（头表已无商品字段），EXISTS 参数化防注入
        wrapper.apply(StringUtils.hasText(queryDTO.getGoodsName()),
                        "EXISTS (SELECT 1 FROM biz_purchase_detail d WHERE d.purchase_id = biz_purchase.id AND d.is_deleted = 0"
                                + " AND d.goods_name LIKE CONCAT('%', {0}, '%'))", queryDTO.getGoodsName());
        wrapper.apply(queryDTO.getGoodsId() != null,
                        "EXISTS (SELECT 1 FROM biz_purchase_detail d WHERE d.purchase_id = biz_purchase.id AND d.is_deleted = 0"
                                + " AND d.goods_id = {0})", queryDTO.getGoodsId());
        wrapper.apply(StringUtils.hasText(queryDTO.getSupplierName()),
                        "EXISTS (SELECT 1 FROM biz_purchase_detail d"
                                + " JOIN base_goods g ON g.id = d.goods_id"
                                + " JOIN base_supplier s ON s.id = g.supplier_id"
                                + " WHERE d.purchase_id = biz_purchase.id AND d.is_deleted = 0"
                                + " AND s.supplier_name LIKE CONCAT('%', {0}, '%'))", queryDTO.getSupplierName());

        wrapper.ge(startTime != null, BizPurchase::getOperationTime, startTime)
            .lt(endTime != null, BizPurchase::getOperationTime, endTime)
                .orderByDesc(BizPurchase::getId);

        Page<BizPurchase> page = bizPurchaseMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(page.getRecords().stream().map(BizPurchase::getId).toList());

        List<PurchaseVO> records = page.getRecords().stream()
                .map(item -> toVO(item, approvalMap.get(item.getId())))
                .toList();
        fillDetails(records); // D111：明细行 + 商品/供应商汇总

        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public PurchaseVO getById(Long id) {
        requirePurchaseReadAccess();
        BizPurchase purchase = requirePurchase(id);
        PurchaseVO vo = toVO(purchase, resolveLatestApproval(purchase.getId()));
        fillDetails(List.of(vo));
        return vo;
    }

    /**
     * D111：可退货来源进货单（正常+已入库），按单分组，明细行带行级可退量
     * （行入库量 − 该行被有效退货累计）。goodsId 给定时仅保留含该物料的单。
     */
    public List<PurchaseSourceOptionVO> returnableOptions(Long goodsId) {
        requirePurchaseModuleAccess();
        LambdaQueryWrapper<BizPurchase> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizPurchase::getBizStatus, 1)
                .eq(BizPurchase::getConfirmStatus, CONFIRM_RECEIVED)
                .orderByDesc(BizPurchase::getOperationTime)
                .orderByDesc(BizPurchase::getId);
        List<BizPurchase> purchases = bizPurchaseMapper.selectList(wrapper);
        if (purchases.isEmpty()) {
            return List.of();
        }

        List<Long> purchaseIds = purchases.stream().map(BizPurchase::getId).toList();
        List<BizPurchaseDetail> allLines = bizPurchaseDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseDetail>()
                        .in(BizPurchaseDetail::getPurchaseId, purchaseIds)
                        .orderByAsc(BizPurchaseDetail::getSortNo)
                        .orderByAsc(BizPurchaseDetail::getId));
        if (goodsId != null) {
            allLines = allLines.stream().filter(l -> goodsId.equals(l.getGoodsId())).toList();
        }
        if (allLines.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> returnedMap = purchaseReturnService.returnedQtyBySourceDetail(
                allLines.stream().map(BizPurchaseDetail::getId).toList());

        Map<Long, List<BizPurchaseDetail>> linesByPurchase = allLines.stream()
                .collect(Collectors.groupingBy(BizPurchaseDetail::getPurchaseId));
        List<PurchaseSourceOptionVO> result = new ArrayList<>();
        for (BizPurchase purchase : purchases) {
            List<BizPurchaseDetail> lines = linesByPurchase.get(purchase.getId());
            if (lines == null || lines.isEmpty()) {
                continue;
            }
            List<PurchaseSourceOptionLineVO> optionLines = lines.stream().map(d -> {
                int returnedQty = returnedMap.getOrDefault(d.getId(), 0);
                int returnableQty = d.getQuantity() - returnedQty;
                if (returnableQty <= 0) {
                    return null;
                }
                PurchaseSourceOptionLineVO line = new PurchaseSourceOptionLineVO();
                line.setPurchaseDetailId(d.getId());
                line.setGoodsId(d.getGoodsId());
                line.setGoodsName(d.getGoodsName());
                line.setSpec(d.getSpec());
                line.setQuantity(d.getQuantity());
                line.setUnitPrice(d.getUnitPrice());
                line.setReturnedQuantity(returnedQty);
                line.setReturnableQuantity(returnableQty);
                return line;
            }).filter(Objects::nonNull).toList();
            if (optionLines.isEmpty()) {
                continue;
            }
            PurchaseSourceOptionVO vo = new PurchaseSourceOptionVO();
            vo.setId(purchase.getId());
            vo.setPurchaseNo(purchase.getPurchaseNo());
            vo.setOperationTime(purchase.getOperationTime());
            vo.setLines(optionLines);
            result.add(vo);
        }
        return result;
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(PurchaseSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseModuleAccess();
        List<PurchaseSaveDTO.LineDTO> lines = requireLines(dto.getLines());

        // D111 决策：同一物料一单只允许一行
        Set<Long> goodsIds = new HashSet<>();
        for (PurchaseSaveDTO.LineDTO line : lines) {
            if (line.getGoodsId() == null || !goodsIds.add(line.getGoodsId())) {
                throw BusinessException.validateFail("同一物料在一张进货单中只能有一行，请合并数量");
            }
        }

        // D123：头级供应商必填且须为有效供应商（存在且未停用，与供应商下拉 options 口径一致）
        if (dto.getSupplierId() == null) {
            throw BusinessException.validateFail("供应商不能为空");
        }
        BaseSupplier supplier = baseSupplierMapper.selectById(dto.getSupplierId());
        if (supplier == null) {
            throw BusinessException.validateFail("供应商不存在");
        }
        if (!Integer.valueOf(1).equals(supplier.getStatus())) {
            throw BusinessException.validateFail("供应商已停用，请重新选择");
        }

        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        // 先全量校验再落库，避免半张单
        Map<Long, BaseGoods> goodsMap = baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        List<BizPurchaseDetail> detailEntities = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int sortNo = 0;
        for (PurchaseSaveDTO.LineDTO line : lines) {
            sortNo++;
            BaseGoods goods = goodsMap.get(line.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail("商品不存在");
            }
            ensureGoodsEnabled(goods);
            GoodsService.ensureGoodsType(goods, GoodsService.GOODS_TYPE_MATERIAL, "商品进货只可选择物料（type=material）"); // D67
            BigDecimal unitPrice = resolveUnitPrice(line.getUnitPrice(), goods.getPurchasePrice(),
                    "物料「" + goods.getGoodsName() + "」进价为空，请传入进货单价");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()));

            BizPurchaseDetail detail = new BizPurchaseDetail();
            detail.setGoodsId(goods.getId());
            detail.setGoodsName(goods.getGoodsName());
            detail.setSpec(goods.getSpec());
            detail.setMaterial(goods.getMaterial());
            detail.setQuantity(line.getQuantity());
            detail.setUnitPrice(unitPrice);
            detail.setTotalPrice(lineTotal);
            detail.setSortNo(sortNo);
            detailEntities.add(detail);

            totalQuantity += line.getQuantity();
            totalAmount = totalAmount.add(lineTotal);
        }

        BizPurchase purchase = new BizPurchase();
        purchase.setPurchaseNo(CodeGenerator.purchaseNo());
        purchase.setTotalQuantity(totalQuantity);
        purchase.setTotalAmount(totalAmount);
        purchase.setOperatorId(loginUser.getId());
        purchase.setOperatorName(loginUser.getRealName());
        purchase.setOperationTime(operationTime);
        purchase.setRemark(dto.getRemark());
        purchase.setSupplierId(dto.getSupplierId()); // D123 头级供应商
        purchase.setBizStatus(1);
        purchase.setConfirmStatus(CONFIRM_PENDING);

        bizPurchaseMapper.insert(purchase);
        for (BizPurchaseDetail detail : detailEntities) {
            detail.setPurchaseId(purchase.getId());
            bizPurchaseDetailMapper.insert(detail);
        }
        // 不加库存，待采购到货确认 + 仓储确认入库
    }

    /**
     * 内部创建进货单（不校验权限，供采购申请仓储确认入库等内部流程复用）。
     * 一次调用生成一张多行已入库单；operator 由调用方传入（如仓储确认入库人）。
     * D123：supplierId 保持 null（采购申请渠道无供应商来源），不参与「最新供应商」口径。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createInternal(PurchaseSaveDTO dto, Long operatorId, String operatorName) {
        List<PurchaseSaveDTO.LineDTO> lines = requireLines(dto.getLines());
        Set<Long> goodsIds = new HashSet<>();
        for (PurchaseSaveDTO.LineDTO line : lines) {
            if (line.getGoodsId() == null || !goodsIds.add(line.getGoodsId())) {
                throw BusinessException.validateFail("同一物料在一张进货单中只能有一行，请合并数量");
            }
        }

        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();
        Map<Long, BaseGoods> goodsMap = baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        List<BizPurchaseDetail> detailEntities = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int sortNo = 0;
        for (PurchaseSaveDTO.LineDTO line : lines) {
            sortNo++;
            BaseGoods goods = goodsMap.get(line.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail("商品不存在");
            }
            ensureGoodsEnabled(goods);
            GoodsService.ensureGoodsType(goods, GoodsService.GOODS_TYPE_MATERIAL, "商品进货只可选择物料（type=material）"); // D67
            BigDecimal unitPrice = resolveUnitPrice(line.getUnitPrice(), goods.getPurchasePrice(),
                    "物料「" + goods.getGoodsName() + "」进价为空，请传入进货单价");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(line.getQuantity()));

            BizPurchaseDetail detail = new BizPurchaseDetail();
            detail.setGoodsId(goods.getId());
            detail.setGoodsName(goods.getGoodsName());
            detail.setSpec(goods.getSpec());
            detail.setMaterial(goods.getMaterial());
            detail.setQuantity(line.getQuantity());
            detail.setUnitPrice(unitPrice);
            detail.setTotalPrice(lineTotal);
            detail.setSortNo(sortNo);
            detailEntities.add(detail);

            totalQuantity += line.getQuantity();
            totalAmount = totalAmount.add(lineTotal);
        }

        BizPurchase purchase = new BizPurchase();
        purchase.setPurchaseNo(CodeGenerator.purchaseNo());
        purchase.setTotalQuantity(totalQuantity);
        purchase.setTotalAmount(totalAmount);
        purchase.setOperatorId(operatorId);
        purchase.setOperatorName(operatorName);
        purchase.setOperationTime(operationTime);
        purchase.setRemark(dto.getRemark());
        purchase.setBizStatus(1);
        purchase.setConfirmStatus(CONFIRM_RECEIVED);
        purchase.setArriveTime(operationTime);
        purchase.setConfirmerId(operatorId);
        purchase.setConfirmerName(operatorName);
        purchase.setConfirmTime(operationTime);

        bizPurchaseMapper.insert(purchase);
        for (BizPurchaseDetail detail : detailEntities) {
            detail.setPurchaseId(purchase.getId());
            bizPurchaseDetailMapper.insert(detail);
            increaseStock(detail.getGoodsId(), detail.getQuantity());
            // 采购单价回写为物料最新进价（仅更新 purchase_price 一列；increaseStock 已用 SQL 自增 stock）
            updatePurchasePrice(detail.getGoodsId(), detail.getUnitPrice());
        }
    }

    // ============================== 采购到货确认（推仓储） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void arrive(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseModuleAccess();
        BizPurchase entity = requirePurchase(id);
        if (entity.getConfirmStatus() == null || entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("仅待到货状态可确认到货");
        }
        ensureNormalStatus(entity.getBizStatus(), "进货单");
        ensureNoPendingVoidApproval(id, "进货单");
        LambdaUpdateWrapper<BizPurchase> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchase::getId, entity.getId())
                .eq(BizPurchase::getConfirmStatus, CONFIRM_PENDING)
                .set(BizPurchase::getConfirmStatus, CONFIRM_AWAITING)
                .set(BizPurchase::getArriveTime, LocalDateTime.now());
        int rows = bizPurchaseMapper.update(null, updateWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("进货单状态已变更，请刷新后重试");
        }
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        messageService.sendPurchaseArrivedToWarehouseAdmins(entity.getPurchaseNo(), loginUser.getRealName(), entity.getId());
    }

    // ============================== 仓储确认入库（加库存） ==============================

    @Transactional(rollbackFor = Exception.class)
    public void confirmReceive(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireWarehouseAccess();
        BizPurchase entity = requirePurchase(id);
        if (entity.getConfirmStatus() == null || entity.getConfirmStatus() != CONFIRM_AWAITING) {
            throw BusinessException.validateFail("仅待入库确认状态可确认入库");
        }
        ensureNormalStatus(entity.getBizStatus(), "进货单");
        ensureNoPendingVoidApproval(id, "进货单");

        List<BizPurchaseDetail> details = requireDetails(entity.getId());
        for (BizPurchaseDetail detail : details) {
            increaseStock(detail.getGoodsId(), detail.getQuantity());
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchase> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizPurchase::getId, entity.getId())
                .eq(BizPurchase::getConfirmStatus, CONFIRM_AWAITING)
                .set(BizPurchase::getConfirmStatus, CONFIRM_RECEIVED)
                .set(BizPurchase::getConfirmerId, loginUser.getId())
                .set(BizPurchase::getConfirmerName, loginUser.getRealName())
                .set(BizPurchase::getConfirmTime, now);
        int rows = bizPurchaseMapper.update(null, updateWrapper);
        if (rows != 1) {
            // 库存已逐行加但状态更新失败时由事务回滚保护
            throw BusinessException.validateFail("进货单状态已变更，请刷新后重试");
        }
        // D101/D111：逐行回写最新进价（「最近一批进价」）；仅更新 purchase_price 一列
        for (BizPurchaseDetail detail : details) {
            updatePurchasePrice(detail.getGoodsId(), detail.getUnitPrice());
        }
        messageService.revokeUnreadByBiz("purchase", id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseAdminOrSuperAdmin();
        BizPurchase purchase = requirePurchase(id);
        ensureNormalStatus(purchase.getBizStatus(), "进货单");
        validateDeleteWindow(purchase.getOperationTime(), "进货单");
        // 仅待到货(未入库)可删，不触碰库存；已入库走作废流程
        if (purchase.getConfirmStatus() != null && purchase.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("已入库的进货单不可删除，请走作废流程");
        }
        messageService.revokeUnreadByBiz("purchase", id);
        bizPurchaseMapper.deleteById(id);
        // D111：级联软删明细行（对齐销售删除的级联口径，引用统计按明细行）
        bizPurchaseDetailMapper.delete(new LambdaQueryWrapper<BizPurchaseDetail>()
                .eq(BizPurchaseDetail::getPurchaseId, id));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requirePurchaseVoidExecutionAccess();
        BizPurchase purchase = requirePurchase(id);
        ensureNormalStatus(purchase.getBizStatus(), "进货单");
        // D99：作废并冲抵（红冲）已停用——界面无入口（D74），此处封死 API 直废路径
        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            throw BusinessException.validateFail("「作废并冲抵」已停用，请使用普通作废");
        }
        // D111/review：存在未终结退货单时禁止整单作废——否则整行回冲会与已出库退货的库存扣减冲突；
        // 须先删除（当天待出库）/作废退货单（退货作废会把库存退回），再作废进货单
        purchaseReturnService.ensureNoActiveReturn(purchase.getId());

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizPurchase> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizPurchase::getId, purchase.getId())
                .eq(BizPurchase::getBizStatus, 1)
                .set(BizPurchase::getBizStatus, 2)
                .set(BizPurchase::getVoidTime, now)
                .set(BizPurchase::getVoidReason, reason);
        int rows = bizPurchaseMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("进货单已被处理，禁止重复作废");
        }

        // 仅已入库(confirm_status=3)作废需逐行回冲库存 + 逐商品重算最近进价；未入库不触碰库存
        boolean received = purchase.getConfirmStatus() != null && purchase.getConfirmStatus() == CONFIRM_RECEIVED;
        if (received) {
            List<BizPurchaseDetail> details = requireDetails(purchase.getId());
            for (BizPurchaseDetail detail : details) {
                decreaseStock(detail.getGoodsId(), detail.getQuantity(),
                        "当前库存不足，无法作废该进货单（" + detail.getGoodsName() + " 需扣回 " + detail.getQuantity() + "）");
            }
            // D101：作废后逐商品重算进价——被作废单若是当前进价来源，回退为该物料最近一批有效已入库行单价
            for (BizPurchaseDetail detail : details) {
                refreshPurchasePriceAfterVoid(detail.getGoodsId());
            }
        }
        messageService.revokeUnreadByBiz("purchase", id);
    }

    /** D101/D111：作废后把物料进价重算为最近一批有效已入库明细行单价；无有效批次时保持原值不动 */
    private void refreshPurchasePriceAfterVoid(Long goodsId) {
        if (goodsId == null) {
            return;
        }
        BigDecimal latest = bizPurchaseMapper.latestValidUnitPrice(goodsId, LocalDateTime.now());
        if (latest == null) {
            return;
        }
        updatePurchasePrice(goodsId, latest);
    }

    private void requirePurchaseVoidExecutionAccess() {
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PURCHASE)) {
            throw BusinessException.validateFail("历史进货单作废需提交仓储审批");
        }
        throw BusinessException.forbidden("仅采购部门管理员可发起进货作废申请，且需由仓储部门审批");
    }

    private void requireWarehouseAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可确认进货入库");
    }

    private String confirmStatusText(Integer confirmStatus) {
        if (confirmStatus == null) return null;
        return switch (confirmStatus) {
            case CONFIRM_PENDING -> "待到货";
            case CONFIRM_AWAITING -> "待入库确认";
            case CONFIRM_RECEIVED -> "已入库";
            default -> String.valueOf(confirmStatus);
        };
    }

    private BizPurchase requirePurchase(Long id) {
        BizPurchase purchase = bizPurchaseMapper.selectById(id);
        if (purchase == null) {
            throw BusinessException.notFound("进货单不存在");
        }
        return purchase;
    }

    private List<BizPurchaseDetail> requireDetails(Long purchaseId) {
        List<BizPurchaseDetail> details = bizPurchaseDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseDetail>()
                        .eq(BizPurchaseDetail::getPurchaseId, purchaseId)
                        .orderByAsc(BizPurchaseDetail::getSortNo)
                        .orderByAsc(BizPurchaseDetail::getId));
        if (details.isEmpty()) {
            throw BusinessException.validateFail("进货单明细缺失");
        }
        return details;
    }

    private List<PurchaseSaveDTO.LineDTO> requireLines(List<PurchaseSaveDTO.LineDTO> lines) {
        if (lines == null || lines.isEmpty()) {
            throw BusinessException.validateFail("进货明细不能为空");
        }
        return lines;
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

    // D97：作废审批中冻结主流程——存在待审批(1)/处理中(4)的作废类申请时禁止继续确认
    private void ensureNoPendingVoidApproval(Long bizId, String docName) {
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "purchase")
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

    private BigDecimal resolveUnitPrice(BigDecimal inputPrice, BigDecimal fallbackPrice, String emptyPriceMsg) {
        BigDecimal finalPrice = inputPrice == null ? fallbackPrice : inputPrice;
        if (finalPrice == null) {
            throw BusinessException.validateFail(emptyPriceMsg);
        }
        if (finalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.validateFail("单价必须大于0");
        }
        return finalPrice;
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

    private void updatePurchasePrice(Long goodsId, BigDecimal unitPrice) {
        LambdaUpdateWrapper<BaseGoods> priceUpdate = new LambdaUpdateWrapper<>();
        priceUpdate.eq(BaseGoods::getId, goodsId).set(BaseGoods::getPurchasePrice, unitPrice);
        baseGoodsMapper.update(null, priceUpdate);
    }

    private Map<Long, BizApprovalOrder> buildLatestApprovalMap(List<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "purchase")
                .in(BizApprovalOrder::getBizId, bizIds)
                .orderByDesc(BizApprovalOrder::getId);
        Map<Long, BizApprovalOrder> approvalMap = new LinkedHashMap<>();
        for (BizApprovalOrder item : bizApprovalOrderMapper.selectList(wrapper)) {
            approvalMap.putIfAbsent(item.getBizId(), item);
        }
        return approvalMap;
    }

    /**
     * D124：批量「最近成交价」——最近一张 已入库+正常 进货单的明细单价（口径同 latestValidUnitPrices）；
     * 无历史价回退 base_goods.purchase_price 标准进价；都没有则不入 map（前端留空）。
     * 进价口径仅采购可见（同 D102 进价历史守卫），供采购申请到货提交预填单价。
     */
    public Map<Long, BigDecimal> latestPrices(Collection<Long> goodsIds) {
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_PURCHASE, "最近采购价仅采购部门可查看");
        if (goodsIds == null || goodsIds.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = goodsIds.stream().filter(Objects::nonNull).distinct().toList();
        if (distinctIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, BigDecimal> result = new LinkedHashMap<>();
        bizPurchaseMapper.latestValidUnitPrices(distinctIds, LocalDateTime.now())
                .forEach(row -> result.put(row.getGoodsId(), row.getUnitPrice()));
        // 无成交记录的物料回退标准进价（仍无值则留空）
        List<Long> missing = distinctIds.stream().filter(id -> !result.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            baseGoodsMapper.selectBatchIds(missing).forEach(g -> {
                if (g.getPurchasePrice() != null) {
                    result.put(g.getId(), g.getPurchasePrice());
                }
            });
        }
        return result;
    }

    private BizApprovalOrder resolveLatestApproval(Long bizId) {
        return buildLatestApprovalMap(List.of(bizId)).get(bizId);
    }

    /**
     * D111：批量填充明细行（一次 in 查询）+ 商品/供应商汇总 + 单价（全同价时）。
     */
    private void fillDetails(List<PurchaseVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> purchaseIds = records.stream().map(PurchaseVO::getId).toList();
        List<BizPurchaseDetail> details = bizPurchaseDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseDetail>()
                        .in(BizPurchaseDetail::getPurchaseId, purchaseIds)
                        .orderByAsc(BizPurchaseDetail::getSortNo)
                        .orderByAsc(BizPurchaseDetail::getId));
        List<Long> goodsIds = details.stream().map(BizPurchaseDetail::getGoodsId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, BaseGoods> goodsMap = goodsIds.isEmpty() ? Map.of() : baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        Map<Long, BaseSupplier> supplierMap = buildSupplierMap(goodsMap.values().stream()
                .map(BaseGoods::getSupplierId).filter(Objects::nonNull).collect(Collectors.toSet()));
        // D123：头级供应商批量预取（存量单与采购申请渠道单据 supplierId 为 null 不在集合中）
        Set<Long> headSupplierIds = records.stream().map(PurchaseVO::getSupplierId)
                .filter(Objects::nonNull).collect(Collectors.toSet());
        Map<Long, BaseSupplier> headSupplierMap = headSupplierIds.isEmpty() ? Map.of()
                : baseSupplierMapper.selectBatchIds(headSupplierIds).stream()
                        .collect(Collectors.toMap(BaseSupplier::getId, s -> s));
        Map<Long, List<BizPurchaseDetail>> byPurchase = details.stream()
                .collect(Collectors.groupingBy(BizPurchaseDetail::getPurchaseId));

        for (PurchaseVO vo : records) {
            List<PurchaseDetailVO> lineVOs = byPurchase.getOrDefault(vo.getId(), List.of()).stream()
                    .map(this::toDetailVO).toList();
            vo.setDetails(lineVOs);
            vo.setGoodsSummary(buildGoodsSummary(lineVOs));
            // D123：头级供应商优先展示；无头级（存量/采购申请渠道单据）回退旧口径（行物料绑定供应商汇总）
            BaseSupplier headSupplier = vo.getSupplierId() == null ? null : headSupplierMap.get(vo.getSupplierId());
            vo.setSupplierName(headSupplier == null ? null : headSupplier.getSupplierName());
            vo.setSupplierSummary(headSupplier != null ? headSupplier.getSupplierName()
                    : buildSupplierSummary(lineVOs, goodsMap, supplierMap));
            vo.setAvgPrice(commonUnitPrice(lineVOs));
        }
    }

    private PurchaseDetailVO toDetailVO(BizPurchaseDetail d) {
        PurchaseDetailVO line = new PurchaseDetailVO();
        line.setId(d.getId());
        line.setPurchaseId(d.getPurchaseId());
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

    /** 商品汇总描述：单行显示品名，多行显示「首品名 等 N 种」 */
    private String buildGoodsSummary(List<PurchaseDetailVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return "-";
        }
        String firstName = lines.get(0).getGoodsName();
        if (lines.size() == 1) {
            return firstName;
        }
        return firstName + "等" + lines.size() + "种";
    }

    /** 供应商汇总：全部行同一供应商为其名称，跨供应商为「多个供应商」 */
    private String buildSupplierSummary(List<PurchaseDetailVO> lines,
                                        Map<Long, BaseGoods> goodsMap,
                                        Map<Long, BaseSupplier> supplierMap) {
        Set<String> names = new HashSet<>();
        for (PurchaseDetailVO line : lines) {
            BaseGoods goods = goodsMap.get(line.getGoodsId());
            if (goods == null || goods.getSupplierId() == null) {
                continue;
            }
            BaseSupplier supplier = supplierMap.get(goods.getSupplierId());
            if (supplier != null) {
                names.add(supplier.getSupplierName());
            }
        }
        if (names.isEmpty()) {
            return "-";
        }
        if (names.size() == 1) {
            return names.iterator().next();
        }
        return "多个供应商";
    }

    /** 全部行同价返回该单价，否则 null（列表单价列多价时显示「—」） */
    private BigDecimal commonUnitPrice(List<PurchaseDetailVO> lines) {
        Set<BigDecimal> prices = lines.stream()
                .map(PurchaseDetailVO::getUnitPrice)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        return prices.size() == 1 ? prices.iterator().next() : null;
    }

    private Map<Long, BaseSupplier> buildSupplierMap(Set<Long> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        return baseSupplierMapper.selectBatchIds(supplierIds).stream()
                .collect(Collectors.toMap(BaseSupplier::getId, s -> s));
    }

    private PurchaseVO toVO(BizPurchase purchase, BizApprovalOrder approvalOrder) {
        PurchaseVO vo = new PurchaseVO();
        vo.setId(purchase.getId());
        vo.setPurchaseNo(purchase.getPurchaseNo());
        vo.setOrderNo(purchase.getPurchaseNo());
        vo.setTotalQuantity(purchase.getTotalQuantity());
        vo.setTotalAmount(purchase.getTotalAmount());
        LocalDateTime bizTime = purchase.getOperationTime() == null ? purchase.getCreateTime() : purchase.getOperationTime();
        vo.setOperationTime(bizTime);
        vo.setPurchaseDate(bizTime);
        vo.setOperatorName(purchase.getOperatorName());
        vo.setOperator(purchase.getOperatorName());
        vo.setRemark(purchase.getRemark());
        vo.setSupplierId(purchase.getSupplierId()); // D123 头级供应商
        vo.setBizStatus(purchase.getBizStatus());
        vo.setConfirmStatus(purchase.getConfirmStatus());
        vo.setConfirmStatusText(confirmStatusText(purchase.getConfirmStatus()));
        vo.setArriveTime(purchase.getArriveTime());
        vo.setConfirmerName(purchase.getConfirmerName());
        vo.setConfirmTime(purchase.getConfirmTime());
        vo.setSourceId(purchase.getSourceId());
        vo.setVoidTime(purchase.getVoidTime());
        vo.setVoidReason(purchase.getVoidReason());
        vo.setCreateTime(purchase.getCreateTime());
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        return vo;
    }
}
