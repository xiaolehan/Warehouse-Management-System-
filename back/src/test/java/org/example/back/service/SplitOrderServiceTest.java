package org.example.back.service;

import org.example.back.dto.LoginResponse;
import org.example.back.dto.SplitReturnSubmitDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizSplitOrder;
import org.example.back.entity.BizSplitOrderDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSplitOrderDetailMapper;
import org.example.back.mapper.BizSplitOrderMapper;
import org.example.back.vo.SplitOrderVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话 68 / D138+D140：拆分退料应退量快照 + 拆分单详情回显已退量。 */
@ExtendWith(MockitoExtension.class)
class SplitOrderServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizSplitOrder.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizSplitOrderDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                BizPickListDetail.class);
    }

    @Mock private BizSplitOrderMapper splitOrderMapper;
    @Mock private BizSplitOrderDetailMapper splitOrderDetailMapper;
    @Mock private BizSalesMapper bizSalesMapper;
    @Mock private BizSalesDetailMapper bizSalesDetailMapper;
    @Mock private BizProductionOrderMapper bizProductionOrderMapper;
    @Mock private BizPickListMapper bizPickListMapper;
    @Mock private BizPickListDetailMapper bizPickListDetailMapper;
    @Mock private BizBomMapper bomMapper;
    @Mock private BizBomDetailMapper bomDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;

    @InjectMocks private SplitOrderService service;

    @Test
    void submitReturn_writesExpectedQuantityFromBomSnapshot() {
        BizSplitOrder split = new BizSplitOrder();
        split.setId(5L);
        split.setSplitNo("CF-0001");
        split.setStatus(BizSplitOrder.STATUS_SPLITTING);
        when(splitOrderMapper.selectById(5L)).thenReturn(split);
        BizSplitOrderDetail snap = new BizSplitOrderDetail();
        snap.setGoodsId(50L);
        snap.setRequiredQuantity(4);
        when(splitOrderDetailMapper.selectList(any())).thenReturn(List.of(snap));
        when(authzService.hasDeptAdminOrSuperAdminAccess(AuthzService.DEPT_PRODUCTION)).thenReturn(true);
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);
        BaseGoods goods = new BaseGoods();
        goods.setId(50L);
        goods.setGoodsName("螺丝");
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods));
        when(splitOrderMapper.update(any(), any())).thenReturn(1);

        SplitReturnSubmitDTO dto = new SplitReturnSubmitDTO();
        SplitReturnSubmitDTO.SplitReturnItemDTO item = new SplitReturnSubmitDTO.SplitReturnItemDTO();
        item.setGoodsId(50L);
        item.setQuantity(2);
        item.setDiffReason("搬运破损2个");
        dto.setItems(List.of(item));

        service.submitReturn(5L, dto);

        ArgumentCaptor<BizPickListDetail> cap = ArgumentCaptor.forClass(BizPickListDetail.class);
        verify(bizPickListDetailMapper).insert(cap.capture());
        assertEquals(4, cap.getValue().getExpectedQuantity()); // D138：应退量 = BOM 需求量快照
        assertEquals(2, cap.getValue().getQuantity());
        assertEquals("搬运破损2个", cap.getValue().getDiffReason());
    }

    @Test
    void getById_enrichesReturnedQuantityFromReturnPickDetails() {
        BizSplitOrder split = new BizSplitOrder();
        split.setId(5L);
        split.setSplitNo("CF-0001");
        split.setReturnPickListId(88L);
        when(splitOrderMapper.selectById(5L)).thenReturn(split);
        BizSplitOrderDetail snap = new BizSplitOrderDetail();
        snap.setGoodsId(50L);
        snap.setRequiredQuantity(4);
        when(splitOrderDetailMapper.selectList(any())).thenReturn(List.of(snap));
        BizPickList pick = new BizPickList();
        pick.setId(88L);
        pick.setPickNo("TL-0009");
        when(bizPickListMapper.selectById(88L)).thenReturn(pick);
        BizPickListDetail row = new BizPickListDetail();
        row.setGoodsId(50L);
        row.setQuantity(2);
        row.setDiffReason("搬运破损2个");
        when(bizPickListDetailMapper.selectList(any())).thenReturn(List.of(row));

        SplitOrderVO vo = service.getById(5L);

        assertEquals("TL-0009", vo.getReturnPickNo());
        // D140：拆分单详情按物料回显退料明细的已退量/差异备注
        assertEquals(2, vo.getDetails().get(0).getReturnedQuantity());
        assertEquals("搬运破损2个", vo.getDetails().get(0).getReturnDiffReason());
    }
}
