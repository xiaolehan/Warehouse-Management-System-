package org.example.back.service;

import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.entity.BaseGoods;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderServiceTest {

    @Mock private BizProductionOrderMapper orderMapper;
    @Mock private BizBomMapper bomMapper;
    @Mock private BizBomDetailMapper bomDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;

    @InjectMocks private ProductionOrderService service;

    @Test
    void computeShortageForOrder_returnsOnlyDeficitLinesWithBomDetailId() {
        // 待生产订单，成品 id=29，数量 2
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizBom bom = new BizBom();
        bom.setId(1L);
        when(bomMapper.selectOne(any())).thenReturn(bom);

        // 两行 BOM：螺丝需2×3=6 vs 库存10(够)，板1需2×2=4 vs 库存1(缺3)
        BizBomDetail screw = new BizBomDetail();
        screw.setId(11L); screw.setBomId(1L); screw.setGoodsId(50L);
        screw.setComponentName("螺丝"); screw.setIsReference(0);
        screw.setQuantity(BigDecimal.valueOf(3));
        BizBomDetail board = new BizBomDetail();
        board.setId(12L); board.setBomId(1L); board.setGoodsId(51L); board.setIsReference(0);
        board.setComponentName("板1"); board.setQuantity(BigDecimal.valueOf(2));
        when(bomDetailMapper.selectList(any())).thenReturn(List.of(screw, board));

        BaseGoods g50 = new BaseGoods(); g50.setId(50L); g50.setGoodsName("螺丝"); g50.setStock(10);
        BaseGoods g51 = new BaseGoods(); g51.setId(51L); g51.setGoodsName("板1"); g51.setStock(1);
        when(baseGoodsMapper.selectById(50L)).thenReturn(g50);
        when(baseGoodsMapper.selectById(51L)).thenReturn(g51);

        List<KitShortageVO> shortage = service.computeShortageForOrder(7L);

        // 只剩缺口行（板1），且带 bomDetailId
        assertEquals(1, shortage.size());
        assertEquals("板1", shortage.get(0).getGoodsName());
        assertEquals(12L, shortage.get(0).getBomDetailId());
        assertEquals(3, shortage.get(0).getDeficit().intValue());
    }
}
