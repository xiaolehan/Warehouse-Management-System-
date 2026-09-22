package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.dto.QuickProductDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BizPurchase;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizBom;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.GoodsLatestSupplierVO;
import org.example.back.vo.GoodsOptionVO;
import org.example.back.vo.GoodsPurchaseHistoryVO;
import org.example.back.vo.GoodsVO;
import org.example.back.vo.QuickProductVO;
import org.example.back.vo.SupplierMatchVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class GoodsService {

    /** D41 货品类型：成品 */
    public static final String GOODS_TYPE_PRODUCT = "product";
    /** D41 货品类型：物料/零件（缺省） */
    public static final String GOODS_TYPE_MATERIAL = "material";

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizBomMapper bizBomMapper;

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    // D102：进价历史查询复用进货单 mapper
    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    // D111：进价最小记录粒度下沉明细行
    @Autowired
    private BizPurchaseDetailMapper bizPurchaseDetailMapper;

    // D109：未知物料供应商匹配——从采购申请明细到货备注取供应商名字
    @Autowired
    private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;

    // D109：用申请单号/建单时间做命中来源追溯与新旧排序
    @Autowired
    private BizPurchaseRequestMapper purchaseRequestMapper;

    @Autowired
    private AuthzService authzService;

    // D66：成品主数据删除安全口径（库存=0 且无单据引用），BOM 删除级联与成品手工删除共用
    @Autowired
    private GoodsReferenceService goodsReferenceService;

    // D35 职责分工：物料资料开放给仓储+采购部门读取；写操作按部门区分字段
    // D39 生产部门可只读看物料库存（数量层）
    // D68 销售部门可只读看物料/成品两页（定价场景要看库存与规格）
    private void requireGoodsReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储、采购、生产或销售部门可访问物料资料",
                AuthzService.DEPT_WAREHOUSE,
                AuthzService.DEPT_PURCHASE,
                AuthzService.DEPT_PRODUCTION,
                AuthzService.DEPT_SALES
        );
    }

    private void requireGoodsPageAccess(boolean warningOnly) {
        if (warningOnly) {
            // D92：预警中心读权限放开到四部门成员——首页预警卡片全员可点直达，数据本就是成员可见物料列表的预警子集
            authzService.requireAnyDeptMemberOrSuperAdmin(
                    AuthzService.WARNING_DEPT_CODES,
                    "仅仓储、采购、生产或销售部门成员可访问预警中心"
            );
            return;
        }
        requireGoodsReadAccess();
    }

    // 编辑：仓储 admin / 采购部门（admin+员工）/ 销售部门（admin+员工，D68 仅成品售价）可进入，各改各职责字段
    private void requireGoodsUpdateAccess() {
        if (authzService.isSuperAdmin()) {
            return;
        }
        boolean warehouseAdmin = authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE);
        boolean purchaseMember = authzService.isDeptMember(AuthzService.DEPT_PURCHASE);
        boolean salesMember = authzService.isDeptMember(AuthzService.DEPT_SALES);
        if (!warehouseAdmin && !purchaseMember && !salesMember) {
            throw BusinessException.forbidden("仅仓储管理员、采购部门或销售部门可编辑");
        }
    }

    public PageResult<GoodsVO> page(GoodsQueryDTO queryDTO) {
        String warningType = queryDTO.getWarningType() == null ? "" : queryDTO.getWarningType().trim().toLowerCase(Locale.ROOT);
        boolean warningOnly = Boolean.TRUE.equals(queryDTO.getWarningOnly());
        requireGoodsPageAccess(warningOnly);

        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getGoodsName()), BaseGoods::getGoodsName, queryDTO.getGoodsName())
                .like(StringUtils.hasText(queryDTO.getProductName()), BaseGoods::getProductName, queryDTO.getProductName())
                .like(StringUtils.hasText(queryDTO.getCategory()), BaseGoods::getCategory, queryDTO.getCategory())
                .eq(queryDTO.getSupplierId() != null, BaseGoods::getSupplierId, queryDTO.getSupplierId())
                .eq(queryDTO.getStatus() != null, BaseGoods::getStatus, queryDTO.getStatus())
                .eq(StringUtils.hasText(queryDTO.getType()), BaseGoods::getType, queryDTO.getType())
            .apply(warningOnly && !"zero".equals(warningType), "stock <= warning_stock")
            .eq(warningOnly && "zero".equals(warningType), BaseGoods::getStock, 0)
                .orderByDesc(BaseGoods::getId);
        if (warningOnly) {
            excludeProducts(wrapper); // D65：成品不参与库存预警
        }

        Page<BaseGoods> page = baseGoodsMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseSupplier> supplierMap = buildSupplierMap(page.getRecords().stream().map(BaseGoods::getSupplierId).collect(Collectors.toSet()));
        List<GoodsVO> records = page.getRecords().stream().map(item -> toVO(item, supplierMap.get(item.getSupplierId()))).toList();
        fillLatestSuppliers(records); // D123：物料「最新供应商」
        desensitizeSalePrice(records); // D126：成品售价脱敏（非销售部门+非超管）
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public List<GoodsOptionVO> options(String type, Boolean hasBom) {
        // D32：销售/采购员工建单时需加载商品下拉，放开部门成员（admin+员工）
        // D39/D41：生产部门只读；type 用于生产任务单品选成品(product)
        authzService.requireAnyDeptMemberOrSuperAdmin(
            "仅仓储、采购、销售或生产部门可获取商品选项",
            AuthzService.DEPT_WAREHOUSE,
            AuthzService.DEPT_PURCHASE,
            AuthzService.DEPT_SALES,
            AuthzService.DEPT_PRODUCTION
        );
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getStatus, 1)
                .eq(StringUtils.hasText(type) && !"all".equals(type), BaseGoods::getType, normalizeType(type))
                .orderByAsc(BaseGoods::getGoodsName);
        if (Boolean.TRUE.equals(hasBom)) {
            // D66：下达生产任务单的成品下拉只列「已建立有效(未删) BOM」的成品
            wrapper.inSql(BaseGoods::getId, "SELECT goods_id FROM biz_bom WHERE is_deleted = 0");
        }
        List<BaseGoods> goods = baseGoodsMapper.selectList(wrapper);
        // D112：一次 in 查有效 BOM 归属——成品行下拉可标「无 BOM」，指引先建档（不再静默消失）
        List<Long> optionIds = goods.stream().map(BaseGoods::getId).toList();
        Set<Long> bomGoodsIds = optionIds.isEmpty() ? Set.of() : bizBomMapper.selectList(
                        new LambdaQueryWrapper<BizBom>().in(BizBom::getGoodsId, optionIds)).stream()
                .map(BizBom::getGoodsId).collect(Collectors.toSet());
        boolean hideSale = hideSalePrice(); // D126：调用方粒度一次判定，不在逐行 map 内重复求值
        return goods.stream()
                .map(item -> new GoodsOptionVO(item.getId(), item.getGoodsName(), item.getStock(), item.getUnit(),
                        item.getSpec(), item.getMaterial(),
                        maskedSalePrice(hideSale, item.getType(), item.getSalePrice()), // D126：成品售价脱敏
                        item.getPurchasePrice(),
                        item.getType(),
                        "product".equals(item.getType()) ? bomGoodsIds.contains(item.getId()) : null))
                .toList();
    }

    public GoodsVO getById(Long id) {
        requireGoodsReadAccess();
        BaseGoods goods = requireGoods(id);
        BaseSupplier supplier = baseSupplierMapper.selectById(goods.getSupplierId());
        GoodsVO vo = toVO(goods, supplier);
        fillLatestSuppliers(List.of(vo)); // D123：物料「最新供应商」
        desensitizeSalePrice(List.of(vo)); // D126：成品售价脱敏（非销售部门+非超管）
        return vo;
    }

    // ============================== D126：成品售价可见性 ==============================

    /** D126：当前调用方是否应隐藏成品售价（非销售部门成员且非超管 → true；销售部门成员或超管 → false） */
    private boolean hideSalePrice() {
        return !authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES);
    }

    /** D126：售价脱敏唯一规则（options 与 VO 列表共用）——隐藏时成品售价抹为 null，物料不受影响 */
    private BigDecimal maskedSalePrice(boolean hide, String type, BigDecimal salePrice) {
        return hide && GOODS_TYPE_PRODUCT.equals(type) ? null : salePrice;
    }

    /**
     * D126：成品售价脱敏——非销售部门成员且非超管不返回售价（VO 序列化前抹除）。
     * 物料进价口径不变；D121 快速建品端点仅销售+超管可达，不经此读链路不受影响。
     */
    private void desensitizeSalePrice(List<GoodsVO> records) {
        boolean hideSale = hideSalePrice();
        if (!hideSale) {
            return;
        }
        for (GoodsVO vo : records) {
            vo.setSalePrice(maskedSalePrice(hideSale, vo.getType(), vo.getSalePrice()));
        }
    }

    /**
     * D123/D129 共用解析内核——「最新供应商」回退链：行级（D131/ADR-0018）→ 头级 → 绑定供应商+「默认」标。
     * 最近行对应的供应商资料已被删除时同样视为无有效记录，回退绑定供应商。
     */
    private static GoodsLatestSupplierVO resolveLatestSupplier(Long goodsId, Long latestSupplierId,
            Map<Long, BaseSupplier> supplierMap, Long bindingSupplierId, String bindingSupplierName) {
        GoodsLatestSupplierVO vo = new GoodsLatestSupplierVO();
        vo.setGoodsId(goodsId);
        vo.setBindingSupplierId(bindingSupplierId);
        BaseSupplier latest = latestSupplierId == null ? null : supplierMap.get(latestSupplierId);
        if (latest != null) {
            vo.setSupplierId(latest.getId());
            vo.setSupplierName(latest.getSupplierName());
            vo.setIsDefault(false);
        } else {
            vo.setSupplierId(bindingSupplierId);
            vo.setSupplierName(bindingSupplierName);
            vo.setIsDefault(true);
        }
        return vo;
    }

    /**
     * D123：物料「最新供应商」批量填充——最近一张 已入库+正常+记录了供应商 进货明细行的供应商；
     * 无此记录回退物料绑定供应商并标「默认」。仅物料计算（成品无供应商概念），防 N+1：
     * 一次窗口函数查询 + 一次供应商批量加载。
     */
    private void fillLatestSuppliers(List<GoodsVO> records) {
        List<Long> materialIds = records.stream()
                .filter(v -> GOODS_TYPE_MATERIAL.equals(v.getType()) && v.getId() != null)
                .map(GoodsVO::getId).toList();
        if (materialIds.isEmpty()) {
            return;
        }
        Map<Long, Long> latestByGoods = bizPurchaseMapper.latestValidSuppliers(materialIds).stream()
                .collect(Collectors.toMap(BizPurchaseMapper.LatestPurchaseSupplier::getGoodsId,
                        BizPurchaseMapper.LatestPurchaseSupplier::getSupplierId));
        Set<Long> latestSupplierIds = new HashSet<>(latestByGoods.values());
        Map<Long, BaseSupplier> latestSupplierMap = latestSupplierIds.isEmpty() ? Map.of()
                : baseSupplierMapper.selectBatchIds(latestSupplierIds).stream()
                        .collect(Collectors.toMap(BaseSupplier::getId, s -> s));
        for (GoodsVO vo : records) {
            if (!GOODS_TYPE_MATERIAL.equals(vo.getType())) {
                continue; // 成品/其他类型不计算
            }
            GoodsLatestSupplierVO info = resolveLatestSupplier(vo.getId(),
                    latestByGoods.get(vo.getId()), latestSupplierMap, vo.getSupplierId(), vo.getSupplierName());
            vo.setLatestSupplierName(info.getSupplierName());
            vo.setLatestSupplierDefault(info.getIsDefault());
        }
    }

    /**
     * D129：批量查询物料「最新供应商」（采购申请全链参考列 + 到货提交预填绑定值）。
     * 口径与商品资料页 fillLatestSuppliers 完全一致（共用 resolveLatestSupplier 内核）；
     * 成品/未知 id 不入结果。供应商名非价格敏感数据，登录即可调（同 page/options 家族）。
     */
    public Map<Long, GoodsLatestSupplierVO> computeLatestSuppliers(java.util.Collection<Long> goodsIds) {
        if (goodsIds == null || goodsIds.isEmpty()) {
            return Map.of();
        }
        List<BaseGoods> materials = baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .filter(g -> GOODS_TYPE_MATERIAL.equals(g.getType()))
                .toList();
        if (materials.isEmpty()) {
            return Map.of();
        }
        List<Long> materialIds = materials.stream().map(BaseGoods::getId).toList();
        Map<Long, Long> latestByGoods = bizPurchaseMapper.latestValidSuppliers(materialIds).stream()
                .collect(Collectors.toMap(BizPurchaseMapper.LatestPurchaseSupplier::getGoodsId,
                        BizPurchaseMapper.LatestPurchaseSupplier::getSupplierId));
        // 一次批量加载：最新供应商 + 绑定供应商（到货提交预填需要绑定 id 与名称）
        Set<Long> supplierIds = new HashSet<>(latestByGoods.values());
        materials.forEach(g -> {
            if (g.getSupplierId() != null) {
                supplierIds.add(g.getSupplierId());
            }
        });
        Map<Long, BaseSupplier> supplierMap = supplierIds.isEmpty() ? Map.of()
                : baseSupplierMapper.selectBatchIds(supplierIds).stream()
                        .collect(Collectors.toMap(BaseSupplier::getId, s -> s));
        Map<Long, GoodsLatestSupplierVO> result = new java.util.HashMap<>();
        for (BaseGoods g : materials) {
            BaseSupplier binding = g.getSupplierId() == null ? null : supplierMap.get(g.getSupplierId());
            GoodsLatestSupplierVO info = resolveLatestSupplier(g.getId(),
                    latestByGoods.get(g.getId()), supplierMap, g.getSupplierId(),
                    binding == null ? null : binding.getSupplierName());
            result.put(g.getId(), info);
        }
        return result;
    }

    // D102/D111：进价历史——该物料全部有效已入库明细行（最近在上，LIMIT 100）；仅采购部门成员/超管可见。
    // 单据状态在头表，故先取该物料明细行、再按头表过滤+按头操作时间排序。
    public List<GoodsPurchaseHistoryVO> purchasePriceHistory(Long goodsId) {
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_PURCHASE, "进价历史仅采购部门可查看");
        requireGoods(goodsId);
        List<BizPurchaseDetail> lines = bizPurchaseDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseDetail>()
                        .eq(BizPurchaseDetail::getGoodsId, goodsId)
                        .orderByDesc(BizPurchaseDetail::getId));
        if (lines.isEmpty()) {
            return List.of();
        }
        List<Long> purchaseIds = lines.stream().map(BizPurchaseDetail::getPurchaseId).distinct().toList();
        Map<Long, BizPurchase> headMap = bizPurchaseMapper.selectBatchIds(purchaseIds).stream()
                .collect(Collectors.toMap(BizPurchase::getId, p -> p));
        return lines.stream()
                .filter(d -> {
                    BizPurchase p = headMap.get(d.getPurchaseId());
                    return p != null && Integer.valueOf(1).equals(p.getBizStatus())
                            && Integer.valueOf(3).equals(p.getConfirmStatus());
                })
                .sorted(Comparator.comparing((BizPurchaseDetail d) -> headMap.get(d.getPurchaseId()).getOperationTime())
                        .reversed()
                        .thenComparing(BizPurchaseDetail::getId, Comparator.reverseOrder()))
                .limit(100)
                .map(d -> {
                    BizPurchase p = headMap.get(d.getPurchaseId());
                    GoodsPurchaseHistoryVO vo = new GoodsPurchaseHistoryVO();
                    vo.setPurchaseNo(p.getPurchaseNo());
                    vo.setUnitPrice(d.getUnitPrice());
                    vo.setQuantity(d.getQuantity());
                    vo.setTotalPrice(d.getTotalPrice());
                    vo.setOperationTime(p.getOperationTime());
                    vo.setConfirmTime(p.getConfirmTime());
                    return vo;
                }).toList();
    }

    // 建物料/成品仅仓储 admin；仓储建时不含进价/售价（物料价格由采购补录；成品无价格概念）
    public void create(GoodsSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门管理员可创建物料/成品");
        boolean isProduct = GOODS_TYPE_PRODUCT.equals(normalizeType(dto.getType()));
        if (isProduct) {
            // D65：成品允许手工建档（修订 D46），名称唯一口径不变（全库唯一）
            checkGoodsNameUnique(dto.getGoodsName(), null);
        } else {
            // D60/ADR-0003：物料按「名称+规格」唯一，同名不同规格各自成条
            checkMaterialNameSpecUnique(dto.getGoodsName(), dto.getSpec(), null);
        }
        // D65：成品无供应商概念，缺省挂缺省供应商
        Long supplierId = dto.getSupplierId() == null && isProduct ? DEFAULT_SUPPLIER_ID : dto.getSupplierId();
        requireSupplier(supplierId);
        validateStock(dto.getStock());
        validateWarningStock(dto.getWarningStock());
        BaseGoods goods = new BaseGoods();
        BeanUtils.copyProperties(dto, goods);
        goods.setType(normalizeType(dto.getType()));
        goods.setGoodsCode(CodeGenerator.goodsCode());
        goods.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        goods.setStock(dto.getStock() == null ? 0 : dto.getStock());
        if (isProduct) {
            goods.setSupplierId(supplierId);
            goods.setCategory("成品");
            goods.setWarningStock(0); // D65：成品不参与库存预警
        } else {
            goods.setWarningStock(dto.getWarningStock() == null ? 10 : dto.getWarningStock());
        }
        baseGoodsMapper.insert(goods);
    }

    // D35：编辑按职责分字段——采购可改进价(并校验>0)、不可动库存；仓储改库存/预警阈值、不可动价格；
    //      二者均可改物料基本字段(名称/产品名/种类/供应商/单位)。
    // D68：销售部门(admin+员工)可编辑成品标准售价(并校验>0)，其余字段一概不动；物料不维护售价。
    public void update(Long id, GoodsSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireGoodsUpdateAccess();
        BaseGoods goods = requireGoods(id);
        boolean isPurchase = authzService.isDeptMember(AuthzService.DEPT_PURCHASE);
        boolean isSales = authzService.isDeptMember(AuthzService.DEPT_SALES);

        if (isSales && !authzService.isSuperAdmin()) {
            // D68 销售(admin/员工)：仅维护成品标准售价（镜像采购进价范式），基本资料/库存一概不动
            if (!GOODS_TYPE_PRODUCT.equals(goods.getType())) {
                throw BusinessException.forbidden("物料无售价概念，销售部门仅可编辑成品售价");
            }
            if (dto.getSalePrice() == null || dto.getSalePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.validateFail("售价必须大于0");
            }
            goods.setSalePrice(dto.getSalePrice());
            baseGoodsMapper.updateById(goods);
            return;
        }

        if (isPurchase) {
            // D65：成品无进价概念，采购部门不可编辑成品
            if (GOODS_TYPE_PRODUCT.equals(goods.getType())) {
                throw BusinessException.forbidden("成品无进价概念，采购部门不可编辑成品");
            }
            // D35.2 采购(admin/员工)：仅就地补录/修改进价，基本资料与库存均由仓储维护，采购一概不动
            if (dto.getPurchasePrice() == null || dto.getPurchasePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.validateFail("进价必须大于0");
            }
            goods.setPurchasePrice(dto.getPurchasePrice());
            baseGoodsMapper.updateById(goods);
            return;
        }

        // D65：成品维护路径——仅 名称/单位/规格/备注/状态/库存 可改；供应商/预警/材质/种类/产品名 成品页不维护，保持原值
        if (GOODS_TYPE_PRODUCT.equals(goods.getType())) {
            checkGoodsNameUnique(dto.getGoodsName(), id);
            validateStock(dto.getStock());
            goods.setGoodsName(dto.getGoodsName());
            goods.setUnit(dto.getUnit());
            goods.setSpec(dto.getSpec());
            goods.setDescription(dto.getDescription());
            goods.setStatus(dto.getStatus() == null ? goods.getStatus() : dto.getStatus());
            goods.setStock(dto.getStock() == null ? goods.getStock() : dto.getStock());
            baseGoodsMapper.updateById(goods);
            return;
        }

        // 仓储(或超管)：改基本字段(名称/产品名/种类/供应商/单位/描述/状态) + 库存/预警阈值，价格字段不动
        requireSupplier(dto.getSupplierId());
        String targetType = StringUtils.hasText(dto.getType()) ? normalizeType(dto.getType()) : goods.getType();
        if (GOODS_TYPE_MATERIAL.equalsIgnoreCase(targetType)) {
            checkMaterialNameSpecUnique(dto.getGoodsName(), dto.getSpec(), id);
        } else {
            checkGoodsNameUnique(dto.getGoodsName(), id);
        }
        validateStock(dto.getStock());
        validateWarningStock(dto.getWarningStock());
        goods.setGoodsName(dto.getGoodsName());
        goods.setProductName(dto.getProductName());
        goods.setCategory(dto.getCategory());
        goods.setBrand(dto.getBrand());
        goods.setType(StringUtils.hasText(dto.getType()) ? normalizeType(dto.getType()) : goods.getType());
        goods.setSupplierId(dto.getSupplierId());
        goods.setUnit(dto.getUnit());
        goods.setSpec(dto.getSpec());
        goods.setMaterial(dto.getMaterial());
        goods.setDescription(dto.getDescription());
        goods.setStatus(dto.getStatus() == null ? goods.getStatus() : dto.getStatus());
        goods.setStock(dto.getStock() == null ? goods.getStock() : dto.getStock());
        goods.setWarningStock(dto.getWarningStock() == null ? goods.getWarningStock() : dto.getWarningStock());
        baseGoodsMapper.updateById(goods);
    }

    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门管理员可删除物料/成品");
        BaseGoods goods = requireGoods(id);
        // D65/Q11：成品主数据有库存、存在有效 BOM 或被任何单据引用时不允许删除（与 BOM 删除级联同一口径）
        if (GOODS_TYPE_PRODUCT.equals(goods.getType()) && !goodsReferenceService.isProductDeletable(id, goods.getStock())) {
            throw BusinessException.validateFail("该成品有库存、存在有效 BOM 或已被单据引用，不能删除");
        }
        baseGoodsMapper.deleteById(id);
    }

    private void checkGoodsNameUnique(String goodsName, Long excludeId) {
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getGoodsName, goodsName)
                .ne(excludeId != null, BaseGoods::getId, excludeId);
        if (baseGoodsMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("商品名称已存在");
        }
    }

    /** D60/ADR-0003：自动建档挂缺省供应商（与 db.sql 种子 base_goods supplier_id=1 一致） */
    public static final Long DEFAULT_SUPPLIER_ID = 1L;

    /**
     * D60/ADR-0003：物料按「名称+规格」唯一——同名不同规格各自成条；名称与规格均相同时视为重复。
     * 空规格统一归一为 NULL（与空串等价处理）。
     */
    private void checkMaterialNameSpecUnique(String goodsName, String spec, Long excludeId) {
        String normalizedSpec = StringUtils.hasText(spec) ? spec.trim() : null;
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getGoodsName, goodsName)
                .eq(BaseGoods::getType, GOODS_TYPE_MATERIAL)
                .ne(excludeId != null, BaseGoods::getId, excludeId)
                .and(w -> {
                    if (normalizedSpec != null) {
                        w.eq(BaseGoods::getSpec, normalizedSpec);
                    } else {
                        w.isNull(BaseGoods::getSpec).or().eq(BaseGoods::getSpec, "");
                    }
                });
        if (baseGoodsMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail(
                    "物料[" + goodsName + "]（规格：" + (normalizedSpec == null ? "无" : normalizedSpec) + "）已存在，请改绑已有物料");
        }
    }

    /**
     * D60/ADR-0002：生产补料「未知物料」自动建档——挂缺省供应商、进价留空由采购维护、零库存启用。
     * 返回新物料 id；名称+规格与既有物料重复时抛错（调用方引导改绑已有物料）。
     */
    public Long createMaterialFromProduction(String goodsName, String spec, String material, String unit) {
        checkMaterialNameSpecUnique(goodsName, spec, null);
        BaseGoods goods = new BaseGoods();
        goods.setType(GOODS_TYPE_MATERIAL);
        goods.setGoodsCode(CodeGenerator.goodsCode());
        goods.setGoodsName(goodsName);
        goods.setSpec(StringUtils.hasText(spec) ? spec.trim() : null);
        goods.setMaterial(StringUtils.hasText(material) ? material.trim() : null);
        goods.setSupplierId(DEFAULT_SUPPLIER_ID);
        goods.setUnit(StringUtils.hasText(unit) ? unit.trim() : null);
        goods.setStock(0);
        goods.setWarningStock(10);
        goods.setStatus(1);
        goods.setDescription("生产补料自动建档");
        baseGoodsMapper.insert(goods);
        return goods.getId();
    }

    /**
     * D121：销售建单内嵌「+新品」快速建品（ADR-0017）——商品仍由系统建档，触发方从人工扩展为销售动作。
     * 仅四项入参；type=product、库存 0、无进价、正常状态、无 BOM 均服务端强制（镜像 createMaterialFromProduction 范式）。
     * 同名成品已存在 → 不报错、直接返回已有商品（existing=true）；同名物料占用 → 明确报错（checkGoodsNameUnique 全库唯一口径）。
     */
    public QuickProductVO quickCreateProduct(QuickProductDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_SALES, "仅销售部门可快速建品");
        String name = dto.getGoodsName().trim();
        String spec = StringUtils.hasText(dto.getSpec()) ? dto.getSpec().trim() : null;
        String unit = dto.getUnit().trim();
        BigDecimal salePrice = dto.getSalePrice();
        if (salePrice == null || salePrice.compareTo(BigDecimal.ZERO) < 0) {
            throw BusinessException.validateFail("售价不能为空且不能为负数");
        }

        // 成品名唯一铁律（checkGoodsNameUnique 全库唯一口径）：先找同名成品——正常态直接选用；已停用不能选用也不能新建
        List<BaseGoods> sameName = baseGoodsMapper.selectList(new LambdaQueryWrapper<BaseGoods>()
                .eq(BaseGoods::getGoodsName, name)
                .eq(BaseGoods::getType, GOODS_TYPE_PRODUCT)
                .orderByDesc(BaseGoods::getId));
        if (!sameName.isEmpty()) {
            BaseGoods enabled = sameName.stream()
                    .filter(g -> Integer.valueOf(1).equals(g.getStatus()))
                    .findFirst().orElse(null);
            if (enabled == null) {
                throw BusinessException.validateFail(
                        "已存在同名成品「" + name + "」但已停用，无法创建或选用，请联系管理员启用或换名");
            }
            return toQuickProductVO(enabled, true);
        }

        // 同名物料占用：全库唯一口径下无法建档，提前给出可理解的报错（create() 同名时报「商品名称已存在」）
        Long materialCount = baseGoodsMapper.selectCount(new LambdaQueryWrapper<BaseGoods>()
                .eq(BaseGoods::getGoodsName, name)
                // 类型是恒等匹配（normalizeType 后只有 material/product），常量直比
                .eq(BaseGoods::getType, GOODS_TYPE_MATERIAL));
        if (materialCount > 0) {
            throw BusinessException.validateFail("已存在同名物料「" + name + "」，成品与物料名称不可重复，请换个名称");
        }

        BaseGoods goods = new BaseGoods();
        goods.setType(GOODS_TYPE_PRODUCT);
        goods.setGoodsCode(CodeGenerator.goodsCode());
        goods.setGoodsName(name);
        goods.setSpec(spec);
        goods.setUnit(unit);
        goods.setSalePrice(salePrice);
        goods.setSupplierId(DEFAULT_SUPPLIER_ID); // D65：成品无供应商概念，缺省挂缺省供应商
        goods.setCategory("成品");
        goods.setStock(0);
        goods.setWarningStock(0); // D65：成品不参与库存预警
        goods.setStatus(1);
        goods.setDescription("销售快速建品");
        baseGoodsMapper.insert(goods);
        return toQuickProductVO(goods, false);
    }

    /** D121：快速建品返回体——新建品恒无 BOM；同名选用按 D112 口径现查有效 BOM 归属 */
    private QuickProductVO toQuickProductVO(BaseGoods goods, boolean existing) {
        boolean hasBom = !existing
                ? false
                : bizBomMapper.selectCount(new LambdaQueryWrapper<BizBom>()
                        .eq(BizBom::getGoodsId, goods.getId())) > 0;
        return new QuickProductVO(goods.getId(), goods.getGoodsName(), goods.getStock(), goods.getUnit(),
                goods.getSpec(), goods.getSalePrice(), goods.getType(), hasBom, existing);
    }

    /**
     * D109：未知物料「匹配供应商」——仓储管理员人工触发（无后台扫描）。
     * 读取该物料最新一张采购申请（建单时间倒序）明细上的到货备注
     * （"供应商名字/其他信息"，只取首个斜杠前），按全名精确匹配采购已建档的供应商，
     * 回写 base_goods.supplier_id。任何失败都不改写，采购补备注/建档后可重新触发；
     * 每条失败指引同时告知仓储可直接编辑物料绑定供应商（单据终态后采购侧不可改备注）。
     * @AuditLog 在 Controller 层留痕（仅成功落审计，定格来源单号与备注原文）。
     */
    @Transactional
    public SupplierMatchVO matchSupplier(Long goodsId) {
        if (!authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)) {
            throw BusinessException.forbidden("仅仓储管理员可匹配供应商");
        }
        BaseGoods goods = requireGoods(goodsId);
        if (!GOODS_TYPE_MATERIAL.equals(goods.getType())) {
            throw BusinessException.validateFail("仅物料可匹配供应商，成品不参与");
        }
        if (!DEFAULT_SUPPLIER_ID.equals(goods.getSupplierId())) {
            throw BusinessException.validateFail("该物料已绑定供应商，无需匹配；如需更换请直接编辑物料");
        }

        RemarkSource source = findLatestArrivalRemark(goodsId);
        if (source == null) {
            throw BusinessException.validateFail(
                    "未找到该物料的采购到货备注，无法匹配。请让采购在对应采购申请明细的「到货备注」中"
                            + "按「供应商名字/其他信息」填写（斜杠前为供应商名字）后再匹配；"
                            + "也可由仓储直接在物料管理中编辑该物料绑定供应商");
        }
        String rawRemark = source.detail().getArrivalRemark();
        String supplierName = extractSupplierName(rawRemark);
        if (supplierName == null) {
            throw BusinessException.validateFail(
                    "采购申请单 " + source.requestNo() + " 的到货备注「" + rawRemark
                            + "」格式不正确：斜杠前的供应商名字为空，"
                            + "应为「供应商名字/其他信息」，请让采购修正后再匹配；"
                            + "也可由仓储直接在物料管理中编辑该物料绑定供应商");
        }

        List<BaseSupplier> candidates = baseSupplierMapper.selectList(new LambdaQueryWrapper<BaseSupplier>()
                .eq(BaseSupplier::getSupplierName, supplierName));
        if (candidates.isEmpty()) {
            throw BusinessException.validateFail(
                    "采购申请单 " + source.requestNo() + " 的到货备注指向供应商「" + supplierName
                            + "」，但供应商管理中查无此名。请确认斜杠前是供应商建档全名"
                            + "（若填的是简称或物流说明，请让采购修正备注）；采购也可按该名称建档后"
                            + "重新点击匹配；也可由仓储直接编辑物料绑定供应商");
        }
        if (candidates.size() > 1) {
            throw BusinessException.validateFail(
                    "存在多家名为「" + supplierName + "」的供应商（共 " + candidates.size()
                            + " 家），无法判断应绑哪一家，请联系超级管理员核对供应商主数据后再匹配");
        }
        BaseSupplier supplier = candidates.get(0);
        if (DEFAULT_SUPPLIER_ID.equals(supplier.getId())) {
            // 备注写的是占位名「系统默认供应商」：1→1 条件更新会假成功
            throw BusinessException.validateFail(
                    "采购申请单 " + source.requestNo() + " 的到货备注斜杠前是占位供应商「"
                            + supplierName + "」，不是真实建档供应商，无法匹配。"
                            + "请让采购改为真实供应商名字，或由仓储直接编辑物料绑定供应商");
        }

        // 条件更新：仅当仍挂系统默认供应商时回写，防与手工编辑并发打架
        LambdaUpdateWrapper<BaseGoods> uw = new LambdaUpdateWrapper<>();
        uw.eq(BaseGoods::getId, goodsId)
                .eq(BaseGoods::getSupplierId, DEFAULT_SUPPLIER_ID)
                .set(BaseGoods::getSupplierId, supplier.getId());
        int rows = baseGoodsMapper.update(null, uw);
        if (rows != 1) {
            throw BusinessException.validateFail("该物料的供应商刚被他人更新，请刷新后重试");
        }
        return new SupplierMatchVO(goodsId, goods.getGoodsName(), goods.getSpec(),
                supplier.getId(), supplier.getSupplierName(), source.requestNo(), rawRemark);
    }

    /** 命中的到货备注来源：明细 + 所属采购申请单号。 */
    private record RemarkSource(BizPurchaseRequestDetail detail, String requestNo) {
    }

    /**
     * 该物料到货备注非空的采购申请明细中，取最新一张采购申请上的一条
     * （申请单 create_time/id 倒序，同单按明细 id 倒序）。备注在明细上就地修改、
     * 不更新任何时间戳，故「新旧」以申请单建单时间为准；随带申请单号供审计追溯。
     * 申请单已删除/查无的明细跳过；一条都取不到返回 null。
     */
    private RemarkSource findLatestArrivalRemark(Long goodsId) {
        List<BizPurchaseRequestDetail> details = purchaseRequestDetailMapper.selectList(
                new LambdaQueryWrapper<BizPurchaseRequestDetail>()
                        .eq(BizPurchaseRequestDetail::getGoodsId, goodsId)
                        .isNotNull(BizPurchaseRequestDetail::getArrivalRemark)
                        .ne(BizPurchaseRequestDetail::getArrivalRemark, ""));
        if (details.isEmpty()) {
            return null;
        }
        Set<Long> requestIds = details.stream()
                .map(BizPurchaseRequestDetail::getRequestId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (requestIds.isEmpty()) {
            return null;
        }
        Map<Long, BizPurchaseRequest> requests = purchaseRequestMapper.selectBatchIds(requestIds).stream()
                .collect(Collectors.toMap(BizPurchaseRequest::getId, Function.identity()));

        BizPurchaseRequestDetail bestDetail = null;
        BizPurchaseRequest bestRequest = null;
        for (BizPurchaseRequestDetail detail : details) {
            BizPurchaseRequest request = requests.get(detail.getRequestId());
            if (request == null) {
                continue;
            }
            if (bestRequest == null
                    || request.getCreateTime().isAfter(bestRequest.getCreateTime())
                    || (request.getCreateTime().equals(bestRequest.getCreateTime())
                        && (request.getId() > bestRequest.getId()
                            || (request.getId().equals(bestRequest.getId())
                                && (bestDetail == null || detail.getId() > bestDetail.getId()))))) {
                bestDetail = detail;
                bestRequest = request;
            }
        }
        return bestRequest == null ? null : new RemarkSource(bestDetail, bestRequest.getRequestNo());
    }

    /**
     * D109：从到货备注解析供应商名字——规整空白后取第一个斜杠（兼容全角／）之前的文本再 trim。
     * 无斜杠=整串；多个斜杠=第一段；斜杠前为空（"/xxx"）=null（调用方报错让采购修正）。
     */
    static String extractSupplierName(String arrivalRemark) {
        if (!StringUtils.hasText(arrivalRemark)) {
            return null;
        }
        // 兼容中文输入法全角空格 U+3000 与不间断空格 U+00A0：前导位置 MySQL PAD SPACE 不豁免
        String text = arrivalRemark.replace('　', ' ').replace(' ', ' ').trim();
        int slash = text.indexOf('/');
        int fullWidthSlash = text.indexOf('／');
        int cut = slash < 0 ? fullWidthSlash
                : (fullWidthSlash < 0 ? slash : Math.min(slash, fullWidthSlash));
        String name = (cut >= 0 ? text.substring(0, cut) : text).trim();
        return name.isEmpty() ? null : name;
    }

    private BaseGoods requireGoods(Long id) {
        BaseGoods goods = baseGoodsMapper.selectById(id);
        if (goods == null) {
            throw BusinessException.notFound("商品不存在");
        }
        return goods;
    }

    /** D65：成品不参与库存预警/缺货识别——各预警类查询统一调用，防漏写排除条件 */
    public static void excludeProducts(LambdaQueryWrapper<BaseGoods> wrapper) {
        wrapper.ne(BaseGoods::getType, GOODS_TYPE_PRODUCT);
    }

    /** D67：业务单据形态校验——销售/生产入库仅成品，进货/采购申请仅物料（前端下拉收紧的服务端兜底） */
    public static void ensureGoodsType(BaseGoods goods, String requiredType, String message) {
        String actual = goods.getType() == null ? GOODS_TYPE_MATERIAL : goods.getType();
        if (!requiredType.equalsIgnoreCase(actual)) {
            throw BusinessException.validateFail(message);
        }
    }

    private BaseSupplier requireSupplier(Long id) {
        if (id == null) {
            throw BusinessException.validateFail("供应商不能为空");
        }
        BaseSupplier supplier = baseSupplierMapper.selectById(id);
        if (supplier == null) {
            throw BusinessException.validateFail("供应商不存在");
        }
        return supplier;
    }
    // D41：货品类型归一化，缺省为物料
    private String normalizeType(String type) {
        if (!StringUtils.hasText(type)) {
            return GOODS_TYPE_MATERIAL;
        }
        String t = type.trim().toLowerCase(Locale.ROOT);
        return GOODS_TYPE_PRODUCT.equals(t) ? GOODS_TYPE_PRODUCT : GOODS_TYPE_MATERIAL;
    }

    // 验证库存是否合法
    private void validateStock(Integer stock) {
        if (stock != null && stock < 0) {
            throw BusinessException.validateFail("库存不能小于0");
        }
    }

    private void validateWarningStock(Integer warningStock) {
        if (warningStock != null && warningStock < 0) {
            throw BusinessException.validateFail("预警阈值不能小于0");
        }
    }
    // 构建供应商 ID 到供应商实体的映射
    private Map<Long, BaseSupplier> buildSupplierMap(Set<Long> supplierIds) {
        if (supplierIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BaseSupplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BaseSupplier::getId, supplierIds);
        return baseSupplierMapper.selectList(wrapper).stream().collect(Collectors.toMap(BaseSupplier::getId, Function.identity()));
    }

    private GoodsVO toVO(BaseGoods goods, BaseSupplier supplier) {
        GoodsVO vo = new GoodsVO();
        BeanUtils.copyProperties(goods, vo);
        vo.setSupplierName(supplier == null ? null : supplier.getSupplierName());
        // D35：单价列为进价（仅供采购可见；仓储前端隐藏该列），售价不在本页维护
        vo.setPrice(goods.getPurchasePrice() == null ? BigDecimal.ZERO : goods.getPurchasePrice());
        return vo;
    }
}