package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.vo.GoodsOptionVO;
import org.example.back.vo.GoodsVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.List;
import java.util.Map;
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
    private BaseSupplierMapper baseSupplierMapper;

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
            authzService.requireAnyDeptAdminOrSuperAdmin(
                    AuthzService.WARNING_DEPT_CODES,
                    "仅仓储、采购、生产或销售部门管理员可访问预警中心"
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
        return baseGoodsMapper.selectList(wrapper).stream()
                .map(item -> new GoodsOptionVO(item.getId(), item.getGoodsName(), item.getStock(), item.getUnit(), item.getSpec(), item.getMaterial(), item.getSalePrice(), item.getType()))
                .toList();
    }

    public GoodsVO getById(Long id) {
        requireGoodsReadAccess();
        BaseGoods goods = requireGoods(id);
        BaseSupplier supplier = baseSupplierMapper.selectById(goods.getSupplierId());
        return toVO(goods, supplier);
    }

    // 建物料/成品仅仓储 admin；仓储建时不含进价/售价（物料价格由采购补录；成品无价格概念）
    public void create(GoodsSaveDTO dto) {
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