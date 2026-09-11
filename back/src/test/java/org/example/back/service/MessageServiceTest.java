package org.example.back.service;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.MessageQueryDTO;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysMessage;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysMessageMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.MessageVO;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                SysMessage.class);
    }

    @Mock private SysMessageMapper sysMessageMapper;
    @Mock private SysUserMapper sysUserMapper;
    @Mock private SysDeptMapper sysDeptMapper;
    @Mock private AuthzService authzService;

    @InjectMocks private MessageService service;

    // ---------- 消息点击跳转：page() 须透出 bizType/bizId 供前端映射业务页 ----------
    @Test
    void page_exposesBizTypeAndBizId() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        when(authzService.currentUser()).thenReturn(user);

        SysMessage message = new SysMessage();
        message.setId(100L);
        message.setRecipientUserId(9L);
        message.setTitle("采购申请待处理");
        message.setContent("PR2609xxxx 待认领");
        message.setIsRead(0);
        message.setBizType("purchase_request");
        message.setBizId(22L);
        message.setCreateTime(LocalDateTime.now());

        Page<SysMessage> p = new Page<>(1, 10);
        p.setRecords(List.of(message));
        when(sysMessageMapper.selectPage(any(), any())).thenReturn(p);

        PageResult<MessageVO> result = service.page(new MessageQueryDTO());

        assertEquals(1, result.getRecords().size());
        MessageVO vo = result.getRecords().get(0);
        assertEquals("purchase_request", vo.getBizType());
        assertEquals(22L, vo.getBizId());
    }

    @Test
    void page_keepsBizFieldsNullForUnboundMessage() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(9L);
        when(authzService.currentUser()).thenReturn(user);

        SysMessage message = new SysMessage();
        message.setId(101L);
        message.setRecipientUserId(9L);
        message.setTitle("系统通知");
        message.setIsRead(1);
        message.setCreateTime(LocalDateTime.now());

        Page<SysMessage> p = new Page<>(1, 10);
        p.setRecords(List.of(message));
        when(sysMessageMapper.selectPage(any(), any())).thenReturn(p);

        PageResult<MessageVO> result = service.page(new MessageQueryDTO());

        assertEquals(1, result.getRecords().size());
        assertNull(result.getRecords().get(0).getBizType());
        assertNull(result.getRecords().get(0).getBizId());
    }

    // ---------- D75：消息跳转目标 target_route 落库 ----------

    @Test
    void sendKitComplete_writesTargetRoute() {
        SysDept dept = new SysDept();
        dept.setId(3L);
        when(sysDeptMapper.selectOne(any())).thenReturn(dept);
        SysUser admin = new SysUser();
        admin.setId(21L);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(admin));

        service.sendKitCompleteToProductionAdmins("PO-1", "PTO153", 2, "PR-1", 7L);

        ArgumentCaptor<SysMessage> captor = ArgumentCaptor.forClass(SysMessage.class);
        verify(sysMessageMapper).insert(captor.capture());
        assertEquals("/business/production-order", captor.getValue().getTargetRoute());
    }

    @Test
    void sendReadyToShip_writesTargetRoute() {
        // 精准到人 helper（sendToUserWithBiz）同样须落 target_route
        SysUser recipient = new SysUser();
        recipient.setId(9L);
        recipient.setStatus(1);
        when(sysUserMapper.selectById(9L)).thenReturn(recipient);

        service.sendSalesReadyToShipToUser(9L, "XS-1", "PTO153", 2, 55L);

        ArgumentCaptor<SysMessage> captor = ArgumentCaptor.forClass(SysMessage.class);
        verify(sysMessageMapper).insert(captor.capture());
        assertEquals("/business/sales", captor.getValue().getTargetRoute());
    }

    @Test
    void sendPriceDeviation_writesVoidApprovalRoute() {
        SysUser superadmin = new SysUser();
        superadmin.setId(1L);
        when(sysUserMapper.selectOne(any())).thenReturn(superadmin);

        service.sendPriceDeviationToSuperAdmin("XS-1", "销售甲", new java.math.BigDecimal("0.08"), 55L);

        ArgumentCaptor<SysMessage> captor = ArgumentCaptor.forClass(SysMessage.class);
        verify(sysMessageMapper).insert(captor.capture());
        assertEquals("/system/void-approval", captor.getValue().getTargetRoute());
    }
}
