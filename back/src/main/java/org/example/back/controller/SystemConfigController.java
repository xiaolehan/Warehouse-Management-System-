package org.example.back.controller;

import org.example.back.common.annotation.PreventDuplicateSubmit;
import org.example.back.common.result.Result;
import org.example.back.entity.SysConfig;
import org.example.back.service.SysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 系统参数（仅超管可写，阈值读取放开供建单前端提示）。
 */
@RestController
@RequestMapping("/system/configs")
public class SystemConfigController {

    @Autowired
    private SysConfigService sysConfigService;

    /** 系统参数列表（仅超管）。 */
    @GetMapping
    public Result<List<SysConfig>> list() {
        return Result.success(sysConfigService.listAll());
    }

    /** 读取价格偏离阈值（任意已登录用户可读，销售建单前端提示用）。返回比例小数，如 0.05。 */
    @GetMapping("/price-deviation-threshold")
    public Result<BigDecimal> getPriceDeviationThreshold() {
        return Result.success(sysConfigService.getPriceDeviationThreshold());
    }

    /** 更新价格偏离阈值（仅超管）。请求体 {"value": 0.05}，value 为比例小数。 */
    @PutMapping("/price-deviation-threshold")
    @PreventDuplicateSubmit(message = "请勿重复提交阈值修改请求")
    public Result<BigDecimal> updatePriceDeviationThreshold(@RequestBody Map<String, Object> body) {
        BigDecimal threshold = parseDecimal(body == null ? null : body.get("value"));
        return Result.success(sysConfigService.updatePriceDeviationThreshold(threshold));
    }

    private BigDecimal parseDecimal(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return new BigDecimal(value.toString().trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
