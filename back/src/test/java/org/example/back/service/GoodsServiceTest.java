package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.GoodsSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class GoodsServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BaseGoods.class);
    }

    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BaseSupplierMapper baseSupplierMapper;
    @Mock private AuthzService authzService;

    @InjectMocks private GoodsService service;

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
}
