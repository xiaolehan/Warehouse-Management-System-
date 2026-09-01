package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionDraftCreateDTO;
import org.example.back.entity.BizPurchaseRequest;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseRequestMapper;
import org.example.back.vo.KitShortageVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PurchaseRequestServiceTest {

    @Mock private BizPurchaseRequestMapper bizPurchaseRequestMapper;
    @Mock private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private MessageService messageService;
    @Mock private PurchaseService purchaseService;
    @Mock private ProductionOrderService productionOrderService;

    @InjectMocks private PurchaseRequestService service;

    @Test
    void createDraft_setsDraftStatusAndSendsWarehouseNotice() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("生产甲");
        when(authService.getUserInfo()).thenReturn(user);

        // 无既有草稿（幂等前置通过）
        when(bizPurchaseRequestMapper.selectList(any())).thenReturn(List.of());

        KitShortageVO line = new KitShortageVO();
        line.setBomDetailId(12L);
        line.setGoodsId(51L);
        line.setGoodsName("板1");
        line.setDeficit(BigDecimal.valueOf(3));
        when(productionOrderService.computeShortageForOrder(7L)).thenReturn(List.of(line));

        ProductionDraftCreateDTO dto = new ProductionDraftCreateDTO();
        dto.setProductionOrderId(7L);

        Long draftId = service.createDraft(dto);

        // 主单应为草稿状态 + 生产来源
        org.mockito.ArgumentCaptor<BizPurchaseRequest> captor =
                org.mockito.ArgumentCaptor.forClass(BizPurchaseRequest.class);
        org.mockito.Mockito.verify(bizPurchaseRequestMapper).insert(captor.capture());
        BizPurchaseRequest draft = captor.getValue();
        assertEquals(6, draft.getStatus());
        assertEquals("production", draft.getSourceType());
        assertEquals(7L, draft.getProductionOrderId());
        assertEquals(user.getId(), draft.getApplicantId());
    }
}
