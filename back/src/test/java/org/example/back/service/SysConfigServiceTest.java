package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.SysConfig;
import org.example.back.mapper.SysConfigMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SysConfigServiceTest {

    @Mock
    private SysConfigMapper sysConfigMapper;

    @Mock
    private AuthService authService;

    @Mock
    private AuthzService authzService;

    @InjectMocks
    private SysConfigService sysConfigService;

    @org.junit.jupiter.api.BeforeAll
    static void initTableInfo() {
        // LambdaUpdateWrapper 解析 SysConfig::getConfigKey 需要 lambda 缓存
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(
                new org.apache.ibatis.builder.MapperBuilderAssistant(
                        new org.apache.ibatis.session.Configuration(), "test"),
                SysConfig.class);
    }

    @BeforeEach
    void stubExistingRow() {
        // 默认视为参数行存在且为 5%；各用例按需覆盖（非每个用例都读表，故 lenient）
        SysConfig row = new SysConfig();
        row.setConfigKey(SysConfigService.KEY_PRICE_DEVIATION_THRESHOLD);
        row.setConfigValue("0.05");
        org.mockito.Mockito.lenient().when(sysConfigMapper.selectOne(any())).thenReturn(row);
    }

    @Test
    void init_existingRow_skipsInsertAndLoadsValue() {
        SysConfig row = new SysConfig();
        row.setConfigValue("0.10");
        when(sysConfigMapper.selectOne(any())).thenReturn(row);

        sysConfigService.init();

        verify(sysConfigMapper, never()).insert(any());
        assertEquals(0, new BigDecimal("0.10").compareTo(sysConfigService.getPriceDeviationThreshold()));
    }

    @Test
    void init_missingRow_selfHealsWithDefaultFivePercent() {
        when(sysConfigMapper.selectOne(any())).thenReturn(null);

        sysConfigService.init();

        ArgumentCaptor<SysConfig> captor = ArgumentCaptor.forClass(SysConfig.class);
        verify(sysConfigMapper).insert(captor.capture());
        SysConfig inserted = captor.getValue();
        assertEquals(SysConfigService.KEY_PRICE_DEVIATION_THRESHOLD, inserted.getConfigKey());
        assertEquals("0.05", inserted.getConfigValue());
        assertEquals("销售价格偏离阈值", inserted.getConfigName());
        // 自愈后阈值立即可用
        assertEquals(0, new BigDecimal("0.05").compareTo(sysConfigService.getPriceDeviationThreshold()));
    }

    @Test
    void init_logicallyDeletedRow_revivesInsteadOfCrashing() {
        when(sysConfigMapper.selectOne(any())).thenReturn(null);
        when(sysConfigMapper.insert(any())).thenThrow(new DuplicateKeyException("uk_config_key"));
        when(sysConfigMapper.reviveLogicalDeletedRow(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(1);

        sysConfigService.init();

        verify(sysConfigMapper).reviveLogicalDeletedRow(
                SysConfigService.KEY_PRICE_DEVIATION_THRESHOLD, "0.05",
                "销售价格偏离阈值", "销售单价偏离标准售价超过此比例需超管审批(0.05=5%)");
        assertEquals(0, new BigDecimal("0.05").compareTo(sysConfigService.getPriceDeviationThreshold()));
    }

    @Test
    void init_selfHealFailure_doesNotBreakStartupAndUsesFallback() {
        when(sysConfigMapper.selectOne(any())).thenThrow(new RuntimeException("db down"));

        sysConfigService.init();

        assertEquals(0, new BigDecimal("0.05").compareTo(sysConfigService.getPriceDeviationThreshold()));
    }

    @Test
    void getThreshold_invalidStoredValue_fallsBackToDefault() {
        SysConfig row = new SysConfig();
        row.setConfigValue("not-a-number");
        when(sysConfigMapper.selectOne(any())).thenReturn(row);

        sysConfigService.init();

        assertEquals(0, new BigDecimal("0.05").compareTo(sysConfigService.getPriceDeviationThreshold()));
    }

    @Test
    void updateThreshold_rowMissing_throwsNotFound() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(99L);
        user.setRealName("超管");
        when(authService.getUserInfo()).thenReturn(user);
        when(sysConfigMapper.update(any(), any())).thenReturn(0);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> sysConfigService.updatePriceDeviationThreshold(new BigDecimal("0.10")));
        assertEquals("价格偏离阈值参数不存在", ex.getMessage());
    }
}
