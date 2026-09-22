package org.example.back.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.GoodsQueryDTO;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.dto.QuickProductDTO;
import org.example.back.vo.QuickProductVO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.vo.GoodsOptionVO;
import org.example.back.vo.GoodsVO;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.SupplierMatchVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new org.apache.ibatis.session.Configuration(), "test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BaseGoods.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BaseSupplier.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BizPurchaseRequestDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BizPurchaseRequest.class);
        // D121：快速建品同名选用需对 BizBom 构建 wrapper（hasBom 现查）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, org.example.back.entity.BizBom.class);
    }

    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizBomMapper bizBomMapper;
    @Mock private BaseSupplierMapper baseSupplierMapper;
    @Mock private BizPurchaseMapper bizPurchaseMapper;
    @Mock private BizPurchaseRequestDetailMapper purchaseRequestDetailMapper;
    @Mock private BizPurchaseRequestMapper purchaseRequestMapper;
    @Mock private AuthzService authzService;
    @Mock private GoodsReferenceService goodsReferenceService;

    @InjectMocks private GoodsService service;

    // ---------- 预警中心放开生产 admin：warningOnly 分页鉴权须含生产部门 ----------
    @Test
    void page_warningOnly_authorizesProductionAdmin() {
        // D92：预警中心读权限已放开到四部门成员（不再仅管理员）
        GoodsQueryDTO query = new GoodsQueryDTO();
        query.setWarningOnly(true);
        Page<BaseGoods> emptyPage = new Page<>(query.getPageNum(), query.getPageSize());
        emptyPage.setRecords(java.util.List.of());
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(emptyPage);

        service.page(query);

        verify(authzService).requireAnyDeptMemberOrSuperAdmin(
                eq(AuthzService.WARNING_DEPT_CODES),
                anyString());
    }

    // ---------- D60/ADR-0002：生产补料自动建档——缺省供应商/零库存启用/规格材质写入 ----------
    @Test
    void createMaterialFromProduction_registersWithDefaultsAndTrims() {
        when(baseGoodsMapper.selectCount(any())).thenReturn(0L);
        when(baseGoodsMapper.insert(any(BaseGoods.class))).thenAnswer(inv -> {
            inv.getArgument(0, BaseGoods.class).setId(88L);
            return 1;
        });

        Long id = service.createMaterialFromProduction("新轴承", " M8 ", " 不锈钢 ", " 个 ");

        assertEquals(88L, id);
        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        verify(baseGoodsMapper).insert(cap.capture());
        BaseGoods g = cap.getValue();
        assertEquals("新轴承", g.getGoodsName());
        assertEquals("M8", g.getSpec());
        assertEquals("不锈钢", g.getMaterial());
        assertEquals("个", g.getUnit());
        assertEquals(GoodsService.GOODS_TYPE_MATERIAL, g.getType());
        assertEquals(GoodsService.DEFAULT_SUPPLIER_ID, g.getSupplierId());
        assertEquals(0, g.getStock());
        assertEquals(1, g.getStatus());
        assertEquals("生产补料自动建档", g.getDescription());
        assertNotNull(g.getGoodsCode());
    }

    // ---------- D60/ADR-0003：名称+规格与既有物料重复 → 拒绝并提示改绑 ----------
    @Test
    void createMaterialFromProduction_rejectsDuplicateNameSpec() {
        when(baseGoodsMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.createMaterialFromProduction("轴承", "M8", null, null));
        assertTrue(ex.getMessage().contains("已存在"), "实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("改绑"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    // ---------- D60/ADR-0003：物料建档入口同样按「名称+规格」唯一 ----------
    @Test
    void create_materialRejectsDuplicateNameSpec() {
        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setGoodsName("轴承");
        dto.setSpec("M8");
        dto.setType("material");
        when(baseGoodsMapper.selectCount(any())).thenReturn(1L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("已存在"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    // ---------- D65：成品手工建档——缺省供应商 / category=成品 / 预警阈值=0 ----------
    @Test
    void createProduct_appliesProductDefaults() {
        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setGoodsName("电炒锅 PT200");
        dto.setUnit("台");
        dto.setSpec("PT200-A");
        dto.setType("product");
        when(baseGoodsMapper.selectCount(any())).thenReturn(0L);
        when(baseSupplierMapper.selectById(1L)).thenReturn(new BaseSupplier());
        when(baseGoodsMapper.insert(any(BaseGoods.class))).thenReturn(1);

        service.create(dto);

        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        verify(baseGoodsMapper).insert(cap.capture());
        BaseGoods g = cap.getValue();
        assertEquals("product", g.getType());
        assertEquals("成品", g.getCategory());
        assertEquals(0, g.getWarningStock());
        assertEquals(1L, g.getSupplierId());
        assertNotNull(g.getGoodsCode());
    }

    // ---------- D65：成品更新——只改名称/单位/规格/备注/库存，不抹供应商与预警等主数据 ----------
    @Test
    void updateProduct_updatesOnlyProductFields() {
        BaseGoods goods = new BaseGoods();
        goods.setId(9L);
        goods.setType("product");
        goods.setGoodsName("PT200");
        goods.setProductName("旧产品名");
        goods.setSupplierId(1L);
        goods.setStock(0);
        goods.setWarningStock(0);
        when(baseGoodsMapper.selectById(9L)).thenReturn(goods);
        when(authzService.isSuperAdmin()).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(authzService.isDeptMember(AuthzService.DEPT_PURCHASE)).thenReturn(false);
        when(baseGoodsMapper.selectCount(any())).thenReturn(0L);
        when(baseGoodsMapper.updateById(any(BaseGoods.class))).thenReturn(1);

        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setGoodsName("PT200 改");
        dto.setUnit("台");
        dto.setSpec("PT200-B");
        dto.setDescription("新备注");
        dto.setType("product");
        service.update(9L, dto);

        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        verify(baseGoodsMapper).updateById(cap.capture());
        BaseGoods g = cap.getValue();
        assertEquals("PT200 改", g.getGoodsName());
        assertEquals("台", g.getUnit());
        assertEquals("PT200-B", g.getSpec());
        assertEquals("新备注", g.getDescription());
        assertEquals(0, g.getWarningStock(), "预警阈值不应被 null 覆盖");
        assertEquals(1L, g.getSupplierId(), "供应商不应被 null 覆盖");
        assertEquals("旧产品名", g.getProductName(), "产品名不应被 null 抹掉");
    }

    // ---------- D65：采购部门不可编辑成品（成品无进价概念） ----------
    @Test
    void updateProductByPurchase_forbidden() {
        BaseGoods goods = new BaseGoods();
        goods.setId(9L);
        goods.setType("product");
        when(baseGoodsMapper.selectById(9L)).thenReturn(goods);
        when(authzService.isSuperAdmin()).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptMember(AuthzService.DEPT_PURCHASE)).thenReturn(true);

        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setGoodsName("PT200");
        dto.setPurchasePrice(new BigDecimal("9.9"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(9L, dto));
        assertTrue(ex.getMessage().contains("采购部门不可编辑成品"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).updateById(any(BaseGoods.class));
    }

    // ---------- D65/Q11：成品手工删除守卫——有库存或被单据引用时拒绝 ----------
    @Test
    void deleteProduct_blockedWhenNotDeletable() {
        BaseGoods goods = new BaseGoods();
        goods.setId(9L);
        goods.setType("product");
        goods.setStock(0);
        when(baseGoodsMapper.selectById(9L)).thenReturn(goods);
        when(goodsReferenceService.isProductDeletable(9L, 0)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(9L));
        assertTrue(ex.getMessage().contains("不能删除"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).deleteById(9L);
    }

    @Test
    void deleteProduct_allowedWhenOrphan() {
        BaseGoods goods = new BaseGoods();
        goods.setId(9L);
        goods.setType("product");
        goods.setStock(0);
        when(baseGoodsMapper.selectById(9L)).thenReturn(goods);
        when(goodsReferenceService.isProductDeletable(9L, 0)).thenReturn(true);

        service.delete(9L);

        verify(baseGoodsMapper).deleteById(9L);
    }

    // ---------- D67：业务单据形态校验——类型不符抛错；type 为 null 按物料兜底（历史数据兼容） ----------
    @Test
    void ensureGoodsType_rejectsMismatchedType() {
        BaseGoods product = new BaseGoods();
        product.setType("product");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> GoodsService.ensureGoodsType(product, GoodsService.GOODS_TYPE_MATERIAL, "只可选择物料"));
        assertTrue(ex.getMessage().contains("只可选择物料"), "实际: " + ex.getMessage());
    }

    @Test
    void ensureGoodsType_acceptsMatchAndNullTypeAsMaterial() {
        BaseGoods material = new BaseGoods();
        material.setType("material");
        GoodsService.ensureGoodsType(material, GoodsService.GOODS_TYPE_MATERIAL, "只可选择物料"); // 不抛即通过

        BaseGoods legacy = new BaseGoods(); // type=null 按物料兜底
        GoodsService.ensureGoodsType(legacy, GoodsService.GOODS_TYPE_MATERIAL, "只可选择物料");

        BusinessException ex = assertThrows(BusinessException.class,
                () -> GoodsService.ensureGoodsType(legacy, GoodsService.GOODS_TYPE_PRODUCT, "只可选择成品"));
        assertTrue(ex.getMessage().contains("只可选择成品"), "实际: " + ex.getMessage());
    }

    // ---------- D68：销售部门仅可编辑成品标准售价（镜像采购进价范式） ----------
    private void mockSalesMember() {
        when(authzService.isSuperAdmin()).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptMember(AuthzService.DEPT_PURCHASE)).thenReturn(false);
        when(authzService.isDeptMember(AuthzService.DEPT_SALES)).thenReturn(true);
    }

    @Test
    void update_salesMemberEditsProductSalePriceOnly() {
        mockSalesMember();
        BaseGoods product = new BaseGoods();
        product.setId(9L);
        product.setType(GoodsService.GOODS_TYPE_PRODUCT);
        when(baseGoodsMapper.selectById(9L)).thenReturn(product);

        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setSalePrice(new BigDecimal("199.00"));
        service.update(9L, dto);

        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        verify(baseGoodsMapper).updateById(cap.capture());
        assertEquals(new BigDecimal("199.00"), cap.getValue().getSalePrice());
    }

    @Test
    void update_salesMemberCannotEditMaterial() {
        mockSalesMember();
        BaseGoods material = new BaseGoods();
        material.setId(9L);
        material.setType(GoodsService.GOODS_TYPE_MATERIAL);
        when(baseGoodsMapper.selectById(9L)).thenReturn(material);

        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setSalePrice(new BigDecimal("199.00"));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(9L, dto));
        assertTrue(ex.getMessage().contains("销售部门仅可编辑成品售价"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).updateById(any(BaseGoods.class));
    }

    @Test
    void update_salesMemberSalePriceMustBePositive() {
        mockSalesMember();
        BaseGoods product = new BaseGoods();
        product.setId(9L);
        product.setType(GoodsService.GOODS_TYPE_PRODUCT);
        when(baseGoodsMapper.selectById(9L)).thenReturn(product);

        GoodsSaveDTO dto = new GoodsSaveDTO(); // salePrice 为空
        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(9L, dto));
        assertTrue(ex.getMessage().contains("售价必须大于0"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).updateById(any(BaseGoods.class));
    }

    // ---------- D77/ADR-0009：超管真只读——守卫为业务写方法首语句，先于一切校验/落库 ----------
    @Test
    void create_superAdminForbidden_guardRunsFirst() {
        doThrow(BusinessException.forbidden("超级管理员为只读审计角色，不可执行业务写操作"))
                .when(authzService).requireNotSuperAdminForBusinessWrite();

        GoodsSaveDTO dto = new GoodsSaveDTO();
        dto.setGoodsName("x");
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("只读审计角色"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    // ---------- D68：生产/财务等无写权限部门仍被拦截 ----------
    @Test
    void update_productionMemberForbidden() {
        when(authzService.isSuperAdmin()).thenReturn(false);
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);
        when(authzService.isDeptMember(AuthzService.DEPT_PURCHASE)).thenReturn(false);
        when(authzService.isDeptMember(AuthzService.DEPT_SALES)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.update(9L, new GoodsSaveDTO()));
        assertTrue(ex.getMessage().contains("仅仓储管理员、采购部门或销售部门可编辑"), "实际: " + ex.getMessage());
    }

    // ==================== D109 未知物料供应商匹配 ====================

    private BaseGoods unmatchedMaterial() {
        BaseGoods g = new BaseGoods();
        g.setId(50L);
        g.setType(GoodsService.GOODS_TYPE_MATERIAL);
        g.setGoodsName("未知芯片");
        g.setSpec("X1");
        g.setSupplierId(GoodsService.DEFAULT_SUPPLIER_ID);
        return g;
    }

    private BizPurchaseRequestDetail detailWithRemark(String remark) {
        return detailWithRemark(700L, 100L, remark);
    }

    private BizPurchaseRequestDetail detailWithRemark(long detailId, long requestId, String remark) {
        BizPurchaseRequestDetail d = new BizPurchaseRequestDetail();
        d.setId(detailId);
        d.setGoodsId(50L);
        d.setRequestId(requestId);
        d.setArrivalRemark(remark);
        return d;
    }

    private BizPurchaseRequest purchaseRequest(long id, String no, LocalDateTime created) {
        BizPurchaseRequest r = new BizPurchaseRequest();
        r.setId(id);
        r.setRequestNo(no);
        r.setCreateTime(created);
        return r;
    }

    /** 默认命中来源：明细 700 属申请单 100（PR-100，9/1 建单） */
    private void stubRemarkSource(String remark) {
        when(purchaseRequestDetailMapper.selectList(any()))
                .thenReturn(java.util.List.of(detailWithRemark(remark)));
        when(purchaseRequestMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(purchaseRequest(100L, "PR-100",
                        LocalDateTime.of(2026, 9, 1, 10, 0))));
    }

    private BaseSupplier supplier(long id, String name) {
        BaseSupplier s = new BaseSupplier();
        s.setId(id);
        s.setSupplierName(name);
        return s;
    }

    @Test
    void extractSupplierName_rules() {
        assertEquals("华强电子", GoodsService.extractSupplierName("华强电子/顺丰到付"));
        assertEquals("华强电子", GoodsService.extractSupplierName("华强电子／顺丰到付"), "全角斜杠同样截断");
        assertEquals("华强电子", GoodsService.extractSupplierName(" 华强电子 /顺丰 "), "trim 后取段");
        assertEquals("华强电子", GoodsService.extractSupplierName("华强电子/顺丰/到付"), "多个斜杠取第一段");
        assertEquals("华强电子", GoodsService.extractSupplierName("华强电子"), "无斜杠取整串");
        assertNull(GoodsService.extractSupplierName(null));
        assertNull(GoodsService.extractSupplierName("   "));
        assertNull(GoodsService.extractSupplierName("/顺丰到付"), "斜杠前为空返回 null");
        assertNull(GoodsService.extractSupplierName("／顺丰到付"), "全角斜杠前为空返回 null");
        assertEquals("华强电子", GoodsService.extractSupplierName("　华强电子/顺丰"), "前导全角空格须规整");
        assertEquals("华强电子", GoodsService.extractSupplierName(" 华强电子/顺丰"), "前导 NBSP 须规整");
        assertEquals("华强电子", GoodsService.extractSupplierName("　华强电子　"), "无斜杠时两端全角空格也规整");
    }

    @Test
    void matchSupplier_happyPath_writesBackAndReturnsVO() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("华强电子/顺丰到付");
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of(supplier(20L, "华强电子")));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        SupplierMatchVO vo = service.matchSupplier(50L);

        assertEquals(20L, vo.getSupplierId());
        assertEquals("华强电子", vo.getSupplierName());
        assertEquals("华强电子/顺丰到付", vo.getSourceRemark());
        assertEquals("PR-100", vo.getSourceRequestNo(), "VO 须带来源申请单号");
        assertEquals("未知芯片", vo.getGoodsName());
        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        // 回写走条件 update wrapper，而非整行 updateById
        verify(baseGoodsMapper).update(cap.capture(), any());
        assertNull(cap.getValue(), "条件更新不应夹带实体参数");
        verify(baseGoodsMapper, never()).updateById(any());
    }

    @Test
    void matchSupplier_nonWarehouseAdmin_forbidden() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(false);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("仅仓储管理员"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_product_notSupported() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        BaseGoods product = new BaseGoods();
        product.setId(51L);
        product.setType(GoodsService.GOODS_TYPE_PRODUCT);
        product.setSupplierId(GoodsService.DEFAULT_SUPPLIER_ID);
        when(baseGoodsMapper.selectById(51L)).thenReturn(product);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(51L));
        assertTrue(ex.getMessage().contains("成品不参与"), "实际: " + ex.getMessage());
        verify(purchaseRequestDetailMapper, never()).selectList(any());
    }

    @Test
    void matchSupplier_alreadyBound_rejected() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        BaseGoods g = unmatchedMaterial();
        g.setSupplierId(20L);
        when(baseGoodsMapper.selectById(50L)).thenReturn(g);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("已绑定供应商"), "实际: " + ex.getMessage());
        verify(purchaseRequestDetailMapper, never()).selectList(any());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_noArrivalRemark_guidesPurchaseFill() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(java.util.List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("到货备注"), "实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("直接在物料管理"), "须告知仓储兜底出路，实际: " + ex.getMessage());
        verify(purchaseRequestMapper, never()).selectBatchIds(any());
        verify(baseSupplierMapper, never()).selectList(any());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_blankBeforeSlash_rejectedWithRawTextAndRequestNo() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("/货到付款");

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("/货到付款"), "应回显原文案，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("格式不正确"), "实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PR-100"), "应指明来源单据，实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_supplierNotFound_retryableWithoutWrite() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("华强电子");
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("查无此名"), "实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("华强电子"), "实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("建档"), "应引导采购建档，实际: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("PR-100"), "应指明来源单据，实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_duplicateSupplierNames_rejected() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("华强电子");
        when(baseSupplierMapper.selectList(any()))
                .thenReturn(java.util.List.of(supplier(20L, "华强电子"), supplier(21L, "华强电子")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("多家"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_placeholderDefaultSupplier_rejectedWithoutWrite() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("系统默认供应商/待定");
        when(baseSupplierMapper.selectList(any()))
                .thenReturn(java.util.List.of(supplier(GoodsService.DEFAULT_SUPPLIER_ID, "系统默认供应商")));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("占位供应商"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    @Test
    void matchSupplier_concurrentManualEdit_losesAndThrows() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        stubRemarkSource("华强电子");
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of(supplier(20L, "华强电子")));
        when(baseGoodsMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("刷新"), "实际: " + ex.getMessage());
    }

    @Test
    void matchSupplier_picksNewestRequest_thenNewestDetail() {
        // 三张备注：旧单 R100（即便被就地"修正"也不优先）、新单 R101 两条明细取 id 大者
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        when(purchaseRequestDetailMapper.selectList(any())).thenReturn(java.util.List.of(
                detailWithRemark(700L, 100L, "供应商A/旧单"),
                detailWithRemark(701L, 101L, "供应商B/新单老明细"),
                detailWithRemark(702L, 101L, "供应商C/新单新明细")));
        when(purchaseRequestMapper.selectBatchIds(any())).thenReturn(java.util.List.of(
                purchaseRequest(100L, "PR-100", LocalDateTime.of(2026, 9, 1, 10, 0)),
                purchaseRequest(101L, "PR-101", LocalDateTime.of(2026, 9, 5, 10, 0))));
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of(supplier(30L, "供应商C")));
        when(baseGoodsMapper.update(any(), any())).thenReturn(1);

        SupplierMatchVO vo = service.matchSupplier(50L);

        assertEquals("供应商C", vo.getSupplierName());
        assertEquals("PR-101", vo.getSourceRequestNo());
        assertEquals("供应商C/新单新明细", vo.getSourceRemark());
    }

    @Test
    void matchSupplier_remarksOnlyOnDeletedRequests_treatedAsNoRemark() {
        when(authzService.isDeptAdmin(AuthzService.DEPT_WAREHOUSE)).thenReturn(true);
        when(baseGoodsMapper.selectById(50L)).thenReturn(unmatchedMaterial());
        when(purchaseRequestDetailMapper.selectList(any()))
                .thenReturn(java.util.List.of(detailWithRemark(700L, 100L, "华强电子")));
        // 申请单已逻辑删除：selectBatchIds 查不到
        when(purchaseRequestMapper.selectBatchIds(any())).thenReturn(java.util.List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.matchSupplier(50L));
        assertTrue(ex.getMessage().contains("到货备注"), "实际: " + ex.getMessage());
        verify(baseSupplierMapper, never()).selectList(any());
        verify(baseGoodsMapper, never()).update(any(), any());
    }

    // ---------- D121：销售端「+新品」快速建品（ADR-0017） ----------

    private QuickProductDTO quickDto() {
        QuickProductDTO dto = new QuickProductDTO();
        dto.setGoodsName(" 智能炒锅 XT1 ");
        dto.setSpec(" 5L ");
        dto.setUnit(" 台 ");
        dto.setSalePrice(new BigDecimal("1999.00"));
        return dto;
    }

    private BaseGoods product(long id, String name, int status) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setType(GoodsService.GOODS_TYPE_PRODUCT);
        g.setGoodsName(name);
        g.setStatus(status);
        g.setStock(0);
        g.setUnit("台");
        g.setSalePrice(new BigDecimal("1888"));
        return g;
    }

    // ---------- D123：物料「最新供应商」----------

    private BaseGoods materialWithSupplier(long id, String name, long supplierId) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setType(GoodsService.GOODS_TYPE_MATERIAL);
        g.setGoodsName(name);
        g.setStatus(1);
        g.setStock(50);
        g.setUnit("个");
        g.setSupplierId(supplierId);
        return g;
    }

    private Page<BaseGoods> pageOf(java.util.List<BaseGoods> records) {
        Page<BaseGoods> page = new Page<>(1, 10);
        page.setRecords(records);
        page.setTotal(records.size());
        return page;
    }

    @Test
    void page_materialWithLatestPurchase_fillsLatestSupplier() {
        BaseGoods steel = materialWithSupplier(29L, "钢板", 5L);
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(pageOf(java.util.List.of(steel)));
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of(supplier(5L, "华东钢业")));
        when(baseSupplierMapper.selectBatchIds(any())).thenReturn(java.util.List.of(supplier(9L, "北方特钢")));
        BizPurchaseMapper.LatestPurchaseSupplier latest =
                new BizPurchaseMapper.LatestPurchaseSupplier();
        latest.setGoodsId(29L);
        latest.setSupplierId(9L);
        when(bizPurchaseMapper.latestValidSuppliers(any())).thenReturn(java.util.List.of(latest));

        GoodsVO vo = service.page(new GoodsQueryDTO()).getRecords().get(0);

        assertEquals("北方特钢", vo.getLatestSupplierName());
        assertEquals(Boolean.FALSE, vo.getLatestSupplierDefault());
    }

    @Test
    void page_materialNoPurchaseRecord_fallsBackToBoundSupplierWithDefaultTag() {
        BaseGoods steel = materialWithSupplier(29L, "钢板", 5L);
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(pageOf(java.util.List.of(steel)));
        when(baseSupplierMapper.selectList(any())).thenReturn(java.util.List.of(supplier(5L, "华东钢业")));
        when(bizPurchaseMapper.latestValidSuppliers(any())).thenReturn(java.util.List.of());

        GoodsVO vo = service.page(new GoodsQueryDTO()).getRecords().get(0);

        assertEquals("华东钢业", vo.getLatestSupplierName());
        assertEquals(Boolean.TRUE, vo.getLatestSupplierDefault());
    }

    @Test
    void page_product_skipsLatestSupplierFill() {
        BaseGoods machine = product(40L, "整机", 1);
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(pageOf(java.util.List.of(machine)));

        GoodsVO vo = service.page(new GoodsQueryDTO()).getRecords().get(0);

        assertNull(vo.getLatestSupplierName());
        assertNull(vo.getLatestSupplierDefault());
        verify(bizPurchaseMapper, never()).latestValidSuppliers(any());
    }

    // ---------- D129：批量最新供应商端点（采购申请全链参考列 + 到货预填绑定值） ----------

    @Test
    void computeLatestSuppliers_latestWinsAndExposesBinding() {
        when(baseGoodsMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(materialWithSupplier(29L, "钢板", 5L),
                        product(40L, "整机", 1)));
        when(baseSupplierMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(supplier(9L, "北方特钢"), supplier(5L, "华东钢业")));
        BizPurchaseMapper.LatestPurchaseSupplier latest = new BizPurchaseMapper.LatestPurchaseSupplier();
        latest.setGoodsId(29L);
        latest.setSupplierId(9L);
        when(bizPurchaseMapper.latestValidSuppliers(any())).thenReturn(java.util.List.of(latest));

        java.util.Map<Long, org.example.back.vo.GoodsLatestSupplierVO> result =
                service.computeLatestSuppliers(java.util.List.of(29L, 40L));

        org.example.back.vo.GoodsLatestSupplierVO info = result.get(29L);
        assertEquals(9L, info.getSupplierId());
        assertEquals("北方特钢", info.getSupplierName());
        assertEquals(Boolean.FALSE, info.getIsDefault());
        assertEquals(5L, info.getBindingSupplierId());
        assertFalse(result.containsKey(40L), "成品行真实命中 type 过滤，不入结果");
    }

    @Test
    void computeLatestSuppliers_rejectsNonGoodsDeptMember() {
        doThrow(new BusinessException("仅仓储、采购、生产或销售部门可访问物料资料"))
                .when(authzService).requireAnyDeptMemberOrSuperAdmin(anyString(),
                        anyString(), anyString(), anyString(), anyString());

        BusinessException ex = assertThrows(BusinessException.class,
                () -> service.computeLatestSuppliers(java.util.List.of(29L)));
        assertTrue(ex.getMessage().contains("仅仓储、采购、生产或销售"), ex.getMessage());
        verify(baseGoodsMapper, never()).selectBatchIds(any());
    }

    @Test
    void computeLatestSuppliers_fallsBackToBindingWithDefaultTag() {
        when(baseGoodsMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(materialWithSupplier(29L, "钢板", 5L)));
        when(baseSupplierMapper.selectBatchIds(any()))
                .thenReturn(java.util.List.of(supplier(5L, "华东钢业")));
        when(bizPurchaseMapper.latestValidSuppliers(any())).thenReturn(java.util.List.of());

        java.util.Map<Long, org.example.back.vo.GoodsLatestSupplierVO> result =
                service.computeLatestSuppliers(java.util.List.of(29L));

        org.example.back.vo.GoodsLatestSupplierVO info = result.get(29L);
        assertEquals(5L, info.getSupplierId());
        assertEquals("华东钢业", info.getSupplierName());
        assertEquals(Boolean.TRUE, info.getIsDefault());
        assertEquals(5L, info.getBindingSupplierId());
    }

    @Test
    void computeLatestSuppliers_emptyInputReturnsEmptyMap() {
        assertTrue(service.computeLatestSuppliers(java.util.List.of()).isEmpty());
    }

    @Test
    void quickCreate_appliesProductDefaultsAndTrims() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of()); // 无同名成品
        when(baseGoodsMapper.selectCount(any())).thenReturn(0L);                 // 无同名物料
        when(baseGoodsMapper.insert(any(BaseGoods.class))).thenAnswer(inv -> {
            inv.getArgument(0, BaseGoods.class).setId(66L);
            return 1;
        });

        QuickProductVO vo = service.quickCreateProduct(quickDto());

        assertFalse(vo.getExisting());
        assertEquals(66L, vo.getId());
        assertFalse(vo.getHasBom());
        assertEquals("智能炒锅 XT1", vo.getName());
        assertEquals("5L", vo.getSpec());
        assertEquals("台", vo.getUnit());
        ArgumentCaptor<BaseGoods> cap = ArgumentCaptor.forClass(BaseGoods.class);
        verify(baseGoodsMapper).insert(cap.capture());
        BaseGoods g = cap.getValue();
        assertEquals(GoodsService.GOODS_TYPE_PRODUCT, g.getType());
        assertEquals("智能炒锅 XT1", g.getGoodsName());
        assertEquals("5L", g.getSpec());
        assertEquals("台", g.getUnit());
        assertEquals(new BigDecimal("1999.00"), g.getSalePrice());
        assertEquals(GoodsService.DEFAULT_SUPPLIER_ID, g.getSupplierId());
        assertEquals("成品", g.getCategory());
        assertEquals(0, g.getStock());
        assertEquals(0, g.getWarningStock());
        assertEquals(1, g.getStatus());
        assertNotNull(g.getGoodsCode());
    }

    @Test
    void quickCreate_sameNameEnabledProduct_returnsExistingWithoutInsert() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of(product(9L, "智能炒锅 XT1", 1)));
        when(bizBomMapper.selectCount(any())).thenReturn(1L); // 已建档 BOM → hasBom=true（D112 口径）

        QuickProductVO vo = service.quickCreateProduct(quickDto());

        assertTrue(vo.getExisting());
        assertEquals(9L, vo.getId());
        assertTrue(vo.getHasBom());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    @Test
    void quickCreate_sameNameDisabledProduct_rejected() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of(product(9L, "智能炒锅 XT1", 0)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.quickCreateProduct(quickDto()));
        assertTrue(ex.getMessage().contains("停用"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    @Test
    void quickCreate_materialNameConflict_rejected() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of()); // 无同名成品
        when(baseGoodsMapper.selectCount(any())).thenReturn(1L);                 // 有同名物料

        BusinessException ex = assertThrows(BusinessException.class, () -> service.quickCreateProduct(quickDto()));
        assertTrue(ex.getMessage().contains("物料"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    @Test
    void quickCreate_superAdminWriteForbidden() {
        doThrow(BusinessException.forbidden("超管不可写")).when(authzService).requireNotSuperAdminForBusinessWrite();

        assertThrows(BusinessException.class, () -> service.quickCreateProduct(quickDto()));
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    @Test
    void quickCreate_nonSalesMemberForbidden() {
        doThrow(BusinessException.forbidden("仅销售部门")).when(authzService)
                .requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_SALES), anyString());

        assertThrows(BusinessException.class, () -> service.quickCreateProduct(quickDto()));
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    @Test
    void quickCreate_negativePrice_rejected() {
        QuickProductDTO dto = quickDto();
        dto.setSalePrice(new BigDecimal("-1"));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.quickCreateProduct(dto));
        assertTrue(ex.getMessage().contains("售价"), "实际: " + ex.getMessage());
        verify(baseGoodsMapper, never()).insert(any(BaseGoods.class));
    }

    // ---------- D126：成品售价可见性（非销售部门且非超管脱敏，page/options/getById 读链路） ----------

    @Test
    void page_nonSalesMember_productSalePriceNulled_materialUntouched() {
        BaseGoods machine = product(40L, "整机", 1);
        BaseGoods steel = materialWithSupplier(29L, "钢板", 5L);
        steel.setPurchasePrice(new BigDecimal("52.00"));
        steel.setSalePrice(new BigDecimal("9.90"));
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(pageOf(java.util.List.of(machine, steel)));
        when(bizPurchaseMapper.latestValidSuppliers(any())).thenReturn(java.util.List.of()); // D123 链路：无最新供应商记录
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(false); // 仓储/采购视角

        java.util.List<GoodsVO> records = service.page(new GoodsQueryDTO()).getRecords();

        assertNull(records.get(0).getSalePrice(), "成品售价应抹除");
        assertEquals(new BigDecimal("9.90"), records.get(1).getSalePrice(), "物料无售价脱敏概念，原值保留");
        assertEquals(new BigDecimal("52.00"), records.get(1).getPurchasePrice(), "物料进价口径不变");
    }

    @Test
    void page_salesMember_seesProductSalePrice() {
        BaseGoods machine = product(40L, "整机", 1);
        when(baseGoodsMapper.selectPage(any(), any())).thenReturn(pageOf(java.util.List.of(machine)));
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);

        GoodsVO vo = service.page(new GoodsQueryDTO()).getRecords().get(0);

        assertEquals(new BigDecimal("1888"), vo.getSalePrice());
    }

    @Test
    void getById_nonSalesMember_productSalePriceNulled() {
        when(baseGoodsMapper.selectById(40L)).thenReturn(product(40L, "整机", 1));
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(false);

        assertNull(service.getById(40L).getSalePrice());
    }

    @Test
    void getById_salesMember_seesProductSalePrice() {
        when(baseGoodsMapper.selectById(40L)).thenReturn(product(40L, "整机", 1));
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);

        assertEquals(new BigDecimal("1888"), service.getById(40L).getSalePrice());
    }

    @Test
    void options_nonSalesMember_productOptionSalePriceNulled() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of(product(40L, "整机", 1)));
        when(bizBomMapper.selectList(any())).thenReturn(java.util.List.of());
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(false);

        GoodsOptionVO vo = service.options("product", null).get(0);

        assertNull(vo.getSalePrice(), "选项接口成品售价应抹除");
    }

    @Test
    void options_salesMember_seesProductOptionSalePrice() {
        when(baseGoodsMapper.selectList(any())).thenReturn(java.util.List.of(product(40L, "整机", 1)));
        when(bizBomMapper.selectList(any())).thenReturn(java.util.List.of());
        when(authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)).thenReturn(true);

        GoodsOptionVO vo = service.options("product", null).get(0);

        assertEquals(new BigDecimal("1888"), vo.getSalePrice());
    }
}
