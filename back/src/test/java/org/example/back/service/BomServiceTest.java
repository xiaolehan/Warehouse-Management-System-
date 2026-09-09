package org.example.back.service;

import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.apache.ibatis.session.Configuration;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.vo.BomDeleteCheckVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BomServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new Configuration(), "test");
        TableInfoHelper.initTableInfo(assistant, BizBom.class);
        TableInfoHelper.initTableInfo(assistant, BizBomDetail.class);
        TableInfoHelper.initTableInfo(assistant, BaseGoods.class);
    }

    @Mock private BizBomMapper bizBomMapper;
    @Mock private BizBomDetailMapper bizBomDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private AuthzService authzService;
    @Mock private GoodsReferenceService goodsReferenceService;
    @Mock private WorkRequirementAttachmentStorageService storageService;

    @InjectMocks private BomService service;

    private BizBom bom(long id, long goodsId, String goodsName) {
        BizBom b = new BizBom();
        b.setId(id);
        b.setGoodsId(goodsId);
        b.setGoodsName(goodsName);
        b.setBomCode(goodsName + "-BOM");
        return b;
    }

    private BaseGoods product(long id, String name, int stock) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setType("product");
        g.setGoodsName(name);
        g.setStock(stock);
        return g;
    }

    // ---------- D66：删除前检查 ----------
    @Test
    void deleteCheck_returnsUnfinishedOrderCount() {
        when(bizBomMapper.selectById(1L)).thenReturn(bom(1, 5, "PTO200"));
        when(goodsReferenceService.countUnfinishedOrders(5L)).thenReturn(2L);

        BomDeleteCheckVO result = service.deleteCheck(1L);

        assertEquals(Long.valueOf(2L), result.getUnfinishedOrderCount());
        assertEquals("PTO200", result.getGoodsName());
    }


    // ---------- D66：未完结任务单软保护——不确认（force=false）则拒绝删除 ----------
    @Test
    void delete_blockedByUnfinishedOrders() {
        when(bizBomMapper.selectById(1L)).thenReturn(bom(1, 5, "PTO200"));
        when(goodsReferenceService.countUnfinishedOrders(5L)).thenReturn(3L);

        BusinessException ex = assertThrows(BusinessException.class, () -> service.delete(1L, false));
        assertTrue(ex.getMessage().contains("未完结生产任务单"), "实际: " + ex.getMessage());
        verify(bizBomMapper, never()).deleteById(1L);
    }

    // ---------- D66：级联清理——孤儿成品随 BOM 一并软删 ----------
    @Test
    void delete_cascadeCleansOrphanProduct() {
        when(bizBomMapper.selectById(1L)).thenReturn(bom(1, 5, "PTO200"));
        when(goodsReferenceService.countUnfinishedOrders(5L)).thenReturn(0L);
        when(baseGoodsMapper.selectById(5L)).thenReturn(product(5, "PTO200", 0));
        when(goodsReferenceService.isProductDeletable(5L, 0)).thenReturn(true);

        String msg = service.delete(1L, false);

        assertTrue(msg.contains("已一并清理"), "实际: " + msg);
        verify(bizBomMapper).deleteById(1L);
        verify(baseGoodsMapper).deleteById(5L);
    }

    // ---------- D66：级联保留——成品有库存或被引用时保留主档 ----------
    @Test
    void delete_keepsProductWhenNotDeletable() {
        when(bizBomMapper.selectById(1L)).thenReturn(bom(1, 5, "PTO200"));
        when(goodsReferenceService.countUnfinishedOrders(5L)).thenReturn(0L);
        when(baseGoodsMapper.selectById(5L)).thenReturn(product(5, "PTO200", 7));
        when(goodsReferenceService.isProductDeletable(5L, 7)).thenReturn(false);

        String msg = service.delete(1L, false);

        assertTrue(msg.contains("保留"), "实际: " + msg);
        verify(bizBomMapper).deleteById(1L);
        verify(baseGoodsMapper, never()).deleteById(5L);
    }

    // ---------- D66：force 放行软保护，但被引用的成品仍保留 ----------
    @Test
    void delete_forceBypassesProtectionButKeepsReferencedProduct() {
        when(bizBomMapper.selectById(1L)).thenReturn(bom(1, 5, "PTO200"));
        when(goodsReferenceService.countUnfinishedOrders(5L)).thenReturn(3L);
        when(baseGoodsMapper.selectById(5L)).thenReturn(product(5, "PTO200", 0));
        when(goodsReferenceService.isProductDeletable(5L, 0)).thenReturn(false);

        String msg = service.delete(1L, true);

        verify(bizBomMapper).deleteById(1L);
        verify(baseGoodsMapper, never()).deleteById(5L);
        assertTrue(msg.contains("保留"), "实际: " + msg);
    }
}
