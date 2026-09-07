package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.ProductionOrderSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProductionOrderServiceTest {

    @Mock private BizProductionOrderMapper orderMapper;
    @Mock private BizBomMapper bomMapper;
    @Mock private BizBomDetailMapper bomDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private BizPickListMapper pickListMapper;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;

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

    @Test
    void computeShortageForOrder_throwsWhenOrderNotFound() {
        when(orderMapper.selectById(999L)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.computeShortageForOrder(999L));
    }

    // ---------- D60：四态——未绑定物料行 = unknown（未知物料，首次出现） ----------
    @Test
    void computeShortageForOrder_unboundBomRowMarksUnknown() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizBom bom = new BizBom();
        bom.setId(1L);
        when(bomMapper.selectOne(any())).thenReturn(bom);

        BizBomDetail unbound = new BizBomDetail();
        unbound.setId(13L);
        unbound.setBomId(1L);
        unbound.setGoodsId(null); // 未在仓库建档 → 未知物料
        unbound.setComponentName("新轴承");
        unbound.setSpec("M8");
        unbound.setMaterial("不锈钢");
        unbound.setRemark("急件");
        unbound.setIsReference(0);
        unbound.setQuantity(BigDecimal.valueOf(2));
        when(bomDetailMapper.selectList(any())).thenReturn(List.of(unbound));

        List<KitShortageVO> shortage = service.computeShortageForOrder(7L);

        assertEquals(1, shortage.size());
        KitShortageVO line = shortage.get(0);
        assertEquals("unknown", line.getLineStatus());
        assertEquals("未知物料", line.getLineStatusText());
        assertNull(line.getGoodsId());
        assertEquals("新轴承", line.getGoodsName());
        assertEquals("M8", line.getSpec());
        assertEquals("不锈钢", line.getMaterial());
        assertEquals("急件", line.getRemark());
    }

    // ---------- D60：四态——已绑定但库存0 = block（区别于 unknown） ----------
    @Test
    void computeShortageForOrder_zeroStockBoundRowMarksBlock() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizBom bom = new BizBom();
        bom.setId(1L);
        when(bomMapper.selectOne(any())).thenReturn(bom);

        BizBomDetail bound = new BizBomDetail();
        bound.setId(12L);
        bound.setBomId(1L);
        bound.setGoodsId(51L);
        bound.setComponentName("板1");
        bound.setIsReference(0);
        bound.setQuantity(BigDecimal.valueOf(2));
        when(bomDetailMapper.selectList(any())).thenReturn(List.of(bound));

        BaseGoods g51 = new BaseGoods();
        g51.setId(51L);
        g51.setGoodsName("板1");
        g51.setStock(0);
        when(baseGoodsMapper.selectById(51L)).thenReturn(g51);

        List<KitShortageVO> shortage = service.computeShortageForOrder(7L);

        assertEquals(1, shortage.size());
        assertEquals("block", shortage.get(0).getLineStatus());
        assertEquals("严重缺料", shortage.get(0).getLineStatusText());
        assertEquals(51L, shortage.get(0).getGoodsId());
    }

    // ---------- D60：建单存在 block/unknown → 通知采购，摘要带规格与【新物料】 ----------
    @Test
    void create_sendsKitShortageNoticeWithSpec_whenBlock() {
        ProductionOrderSaveDTO dto = new ProductionOrderSaveDTO();
        dto.setGoodsId(29L);
        dto.setQuantity(2);

        BaseGoods product = new BaseGoods();
        product.setId(29L);
        product.setGoodsName("成品A");
        product.setUnit("台");
        product.setType("product");
        when(baseGoodsMapper.selectById(29L)).thenReturn(product);

        BizBom bom = new BizBom();
        bom.setId(1L);
        when(bomMapper.selectOne(any())).thenReturn(bom);

        // 已绑定库存0（block，带规格）+ 未绑定（unknown）两行
        BizBomDetail bound = new BizBomDetail();
        bound.setId(12L);
        bound.setBomId(1L);
        bound.setGoodsId(51L);
        bound.setComponentName("板1");
        bound.setSpec("M8");
        bound.setIsReference(0);
        bound.setQuantity(BigDecimal.valueOf(2));
        BizBomDetail unbound = new BizBomDetail();
        unbound.setId(13L);
        unbound.setBomId(1L);
        unbound.setGoodsId(null);
        unbound.setComponentName("新轴承");
        unbound.setIsReference(0);
        unbound.setQuantity(BigDecimal.valueOf(1));
        when(bomDetailMapper.selectList(any())).thenReturn(List.of(bound, unbound));

        BaseGoods g51 = new BaseGoods();
        g51.setId(51L);
        g51.setGoodsName("板1");
        g51.setStock(0);
        when(baseGoodsMapper.selectById(51L)).thenReturn(g51);

        // insert 回填 id，供随后 selectById 取回保存单
        BizProductionOrder[] holder = new BizProductionOrder[1];
        when(orderMapper.insert(any(BizProductionOrder.class))).thenAnswer(inv -> {
            BizProductionOrder o = inv.getArgument(0);
            o.setId(7L);
            holder[0] = o;
            return 1;
        });
        when(orderMapper.selectById(7L)).thenAnswer(inv -> holder[0]);

        service.create(dto);

        // unknown 阻断 → kitStatus=block → 发齐套预警，摘要含规格与新物料标注
        ArgumentCaptor<String> summaryCap = ArgumentCaptor.forClass(String.class);
        verify(messageService).sendKitShortageToPurchaseAdmins(anyString(), eq("成品A"), summaryCap.capture(), eq(7L));
        String summary = summaryCap.getValue();
        assertTrue(summary.contains("板1（M8）"), "缺口摘要应带规格, 实际: " + summary);
        assertTrue(summary.contains("新轴承【新物料】"), "未知物料行应带【新物料】标注, 实际: " + summary);
    }

    @Test
    void start_blocksWhenNoPick() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);
        when(pickListMapper.selectList(any())).thenReturn(List.of());

        BusinessException ex = assertThrows(BusinessException.class, () -> service.start(7L));
        assertTrue(ex.getMessage().contains("尚未全额出库"));
    }

    @Test
    void start_blocksWhenPickPending() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizPickList pending = new BizPickList();
        pending.setId(1L);
        pending.setStatus(PickListService.STATUS_PENDING); // 1 待发料
        when(pickListMapper.selectList(any())).thenReturn(List.of(pending));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.start(7L));
        assertTrue(ex.getMessage().contains("尚未全额出库"));
    }

    @Test
    void start_setsInProgressWhenAllIssued() {
        BizProductionOrder order = new BizProductionOrder();
        order.setId(7L);
        order.setGoodsId(29L);
        order.setQuantity(2);
        order.setStatus(BizProductionOrder.STATUS_PENDING);
        when(orderMapper.selectById(7L)).thenReturn(order);

        BizPickList issued = new BizPickList();
        issued.setId(1L);
        issued.setStatus(PickListService.STATUS_ISSUED); // 2 已发料
        when(pickListMapper.selectList(any())).thenReturn(List.of(issued));
        when(orderMapper.updateById(any())).thenReturn(1);

        service.start(7L);

        ArgumentCaptor<BizProductionOrder> captor =
                ArgumentCaptor.forClass(BizProductionOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals(BizProductionOrder.STATUS_IN_PROGRESS, captor.getValue().getStatus());
    }
}
