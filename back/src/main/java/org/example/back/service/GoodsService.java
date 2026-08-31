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

    // D35 职责分工：物料资料开放给仓储+采购部门读取；写操作按部门区分字段
    // D39 生产部门可只读看物料库存（数量层）
    private void requireGoodsReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储、采购或生产部门可访问物料资料",
                AuthzService.DEPT_WAREHOUSE,
                AuthzService.DEPT_PURCHASE,
                AuthzService.DEPT_PRODUCTION
        );
    }

    private void requireGoodsPageAccess(boolean warningOnly) {
        if (warningOnly) {
            authzService.requireAnyDeptAdminOrSuperAdmin(
                    "仅仓储、采购或销售部门管理员可访问预警中心",
                    AuthzService.DEPT_WAREHOUSE,
                    AuthzService.DEPT_PURCHASE,
                    AuthzService.DEPT_SALES
            );
            return;
        }
        requireGoodsReadAccess();
    }

    // 编辑：仓储 admin 或 采购部门（admin+员工）可进入，各改各职责字段
    private void requireGoodsUpdateAccess() {
        if (authzService.isSuperAdmin()) {
            return;
        }
        boolean warehouseAdmin = authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE);
        boolean purchaseMember = authzService.isDeptMember(AuthzService.DEPT_PURCHASE);
        if (!warehouseAdmin && !purchaseMember) {
            throw BusinessException.forbidden("仅仓储管理员或采购部门可编辑物料");
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

        Page<BaseGoods> page = baseGoodsMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseSupplier> supplierMap = buildSupplierMap(page.getRecords().stream().map(BaseGoods::getSupplierId).collect(Collectors.toSet()));
        List<GoodsVO> records = page.getRecords().stream().map(item -> toVO(item, supplierMap.get(item.getSupplierId()))).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public List<GoodsOptionVO> options(String type) {
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
        return baseGoodsMapper.selectList(wrapper).stream()
                .map(item -> new GoodsOptionVO(item.getId(), item.getGoodsName(), item.getStock(), item.getUnit(), item.getSalePrice(), item.getType()))
                .toList();
    }

    public GoodsVO getById(Long id) {
        requireGoodsReadAccess();
        BaseGoods goods = requireGoods(id);
        BaseSupplier supplier = baseSupplierMapper.selectById(goods.getSupplierId());
        return toVO(goods, supplier);
    }

    // 建物料仅仓储 admin；仓储建时不含进价/售价（价格由采购补录）
    public void create(GoodsSaveDTO dto) {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门管理员可创建物料");
        checkGoodsNameUnique(dto.getGoodsName(), null);
        requireSupplier(dto.getSupplierId());
        validateStock(dto.getStock());
        validateWarningStock(dto.getWarningStock());
        BaseGoods goods = new BaseGoods();
        BeanUtils.copyProperties(dto, goods);
        goods.setType(normalizeType(dto.getType()));
        goods.setGoodsCode(CodeGenerator.goodsCode());
        goods.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        goods.setStock(dto.getStock() == null ? 0 : dto.getStock());
        goods.setWarningStock(dto.getWarningStock() == null ? 10 : dto.getWarningStock());
        baseGoodsMapper.insert(goods);
    }

    // D35：编辑按职责分字段——采购可改进价(并校验>0)、不可动库存；仓储改库存/预警阈值、不可动价格；
    //      二者均可改物料基本字段(名称/产品名/种类/供应商/单位)。售价不在本页维护。
    public void update(Long id, GoodsSaveDTO dto) {
        requireGoodsUpdateAccess();
        BaseGoods goods = requireGoods(id);
        boolean isPurchase = authzService.isDeptMember(AuthzService.DEPT_PURCHASE);

        if (isPurchase) {
            // D35.2 采购(admin/员工)：仅就地补录/修改进价，基本资料与库存均由仓储维护，采购一概不动
            if (dto.getPurchasePrice() == null || dto.getPurchasePrice().compareTo(BigDecimal.ZERO) <= 0) {
                throw BusinessException.validateFail("进价必须大于0");
            }
            goods.setPurchasePrice(dto.getPurchasePrice());
            baseGoodsMapper.updateById(goods);
            return;
        }

        // 仓储(或超管)：改基本字段(名称/产品名/种类/供应商/单位/描述/状态) + 库存/预警阈值，价格字段不动
        requireSupplier(dto.getSupplierId());
        checkGoodsNameUnique(dto.getGoodsName(), id);
        validateStock(dto.getStock());
        validateWarningStock(dto.getWarningStock());
        goods.setGoodsName(dto.getGoodsName());
        goods.setProductName(dto.getProductName());
        goods.setCategory(dto.getCategory());
        goods.setBrand(dto.getBrand());
        goods.setType(StringUtils.hasText(dto.getType()) ? normalizeType(dto.getType()) : goods.getType());
        goods.setSupplierId(dto.getSupplierId());
        goods.setUnit(dto.getUnit());
        goods.setDescription(dto.getDescription());
        goods.setStatus(dto.getStatus() == null ? goods.getStatus() : dto.getStatus());
        goods.setStock(dto.getStock() == null ? goods.getStock() : dto.getStock());
        goods.setWarningStock(dto.getWarningStock() == null ? goods.getWarningStock() : dto.getWarningStock());
        baseGoodsMapper.updateById(goods);
    }

    public void delete(Long id) {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门管理员可删除物料");
        requireGoods(id);
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

    private BaseGoods requireGoods(Long id) {
        BaseGoods goods = baseGoodsMapper.selectById(id);
        if (goods == null) {
            throw BusinessException.notFound("商品不存在");
        }
        return goods;
    }

    private BaseSupplier requireSupplier(Long id) {
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