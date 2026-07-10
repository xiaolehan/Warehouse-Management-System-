package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import jakarta.annotation.PostConstruct;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.SysConfig;
import org.example.back.mapper.SysConfigMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * 系统参数服务（D30 兑现：价格偏离阈值等可配置参数，替代硬编码常量）。
 * 阈值读频繁（每次建销售单都读），用 volatile 内存缓存 + 启动加载，更新时同步刷新。
 */
@Service
public class SysConfigService {

    public static final String KEY_PRICE_DEVIATION_THRESHOLD = "price_deviation_threshold";

    /** 兜底默认阈值（参数表缺失或解析失败时使用）。 */
    private static final BigDecimal DEFAULT_PRICE_DEVIATION_THRESHOLD = new BigDecimal("0.05");

    @Autowired
    private SysConfigMapper sysConfigMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    private volatile BigDecimal priceDeviationThresholdCache;

    @PostConstruct
    public void init() {
        reloadPriceDeviationThreshold();
    }

    /**
     * 读取价格偏离阈值（任意已登录上下文可用，供 SalesService 建单判定与前端提示）。
     */
    public BigDecimal getPriceDeviationThreshold() {
        BigDecimal value = priceDeviationThresholdCache;
        if (value != null) {
            return value;
        }
        return reloadPriceDeviationThreshold();
    }

    /**
     * 系统参数列表（仅超管）。
     */
    public List<SysConfig> listAll() {
        authzService.requireSuperAdmin("仅超级管理员可查看系统参数");
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.orderByAsc(SysConfig::getId);
        return sysConfigMapper.selectList(wrapper);
    }

    /**
     * 更新价格偏离阈值（仅超管）。value 为比例小数，0 < value < 1。
     */
    public BigDecimal updatePriceDeviationThreshold(BigDecimal value) {
        authzService.requireSuperAdmin("仅超级管理员可配置价格偏离阈值");
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0 || value.compareTo(BigDecimal.ONE) >= 0) {
            throw BusinessException.validateFail("价格偏离阈值需为 0~1 之间的小数（如 0.05 表示 5%）");
        }
        LoginResponse.UserInfoVO user = requireLoginUser();
        BigDecimal normalized = value.setScale(4, RoundingMode.HALF_UP);
        LambdaUpdateWrapper<SysConfig> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(SysConfig::getConfigKey, KEY_PRICE_DEVIATION_THRESHOLD)
                .set(SysConfig::getConfigValue, normalized.toPlainString())
                .set(SysConfig::getUpdaterId, user.getId())
                .set(SysConfig::getUpdaterName, user.getRealName());
        int rows = sysConfigMapper.update(null, wrapper);
        if (rows != 1) {
            throw BusinessException.notFound("价格偏离阈值参数不存在");
        }
        priceDeviationThresholdCache = normalized;
        return normalized;
    }

    private BigDecimal reloadPriceDeviationThreshold() {
        LambdaQueryWrapper<SysConfig> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysConfig::getConfigKey, KEY_PRICE_DEVIATION_THRESHOLD);
        SysConfig config = sysConfigMapper.selectOne(wrapper);
        BigDecimal value = parseThreshold(config);
        priceDeviationThresholdCache = value;
        return value;
    }

    private BigDecimal parseThreshold(SysConfig config) {
        if (config == null || config.getConfigValue() == null) {
            return DEFAULT_PRICE_DEVIATION_THRESHOLD;
        }
        try {
            BigDecimal parsed = new BigDecimal(config.getConfigValue().trim());
            if (parsed.compareTo(BigDecimal.ZERO) <= 0 || parsed.compareTo(BigDecimal.ONE) >= 0) {
                return DEFAULT_PRICE_DEVIATION_THRESHOLD;
            }
            return parsed;
        } catch (NumberFormatException ex) {
            return DEFAULT_PRICE_DEVIATION_THRESHOLD;
        }
    }

    private LoginResponse.UserInfoVO requireLoginUser() {
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        if (user == null || user.getId() == null) {
            throw BusinessException.unauthorized("用户未登录");
        }
        return user;
    }
}
