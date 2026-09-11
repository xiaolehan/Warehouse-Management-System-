package org.example.back.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.back.common.exception.BusinessException;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.example.back.vo.AnnualStatsVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * 年度经营统计（ADR-0008）：
 * 采购总支出 = 进货单 biz_purchase(已入库严口径) - 采购退货(已退货严口径)，按入库确认/完成时间归年。
 * 注意：采购申请「确认入库」会逐明细写入 biz_purchase（PurchaseRequestService.confirmReceive →
 * PurchaseService.createInternal），biz_purchase 是唯一入库交易表，已覆盖采购申请渠道，
 * 不可再对 biz_purchase_request_detail 求和（否则采购申请渠道金额翻倍）。
 * 销售总营收/销售成本 = 销售单(严口径) - 销售退货(严口径)，按 operation_time 归年；
 * 毛利 = 销售额 - 销售成本（已实现销售毛利，非收支差）；毛利率在销售额 <= 0 时为 null。
 */
@Service
public class AnnualStatsService {

    private static final String[] EXPORT_HEADERS = {"年份", "采购总支出", "销售总营收", "销售成本", "毛利", "毛利率(%)"};

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BizSalesReturnMapper bizSalesReturnMapper;

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;

    @Autowired
    private AuthzService authzService;

    public List<AnnualStatsVO> getAnnualStats() {
        requireFinanceAnnualStatsAccess();

        Map<Integer, BigDecimal> salesMap = toYearMap(bizSalesMapper.yearlyValidSalesAmount());
        subtract(salesMap, toYearMap(bizSalesReturnMapper.yearlyValidReturnAmount()));

        Map<Integer, BigDecimal> costMap = toYearMap(bizSalesMapper.yearlyValidSalesCost());
        subtract(costMap, toYearMap(bizSalesReturnMapper.yearlyValidReturnCost()));

        Map<Integer, BigDecimal> purchaseMap = toYearMap(bizPurchaseMapper.yearlyValidPurchaseAmount());
        subtract(purchaseMap, toYearMap(bizPurchaseReturnMapper.yearlyValidPurchaseReturnAmount()));

        Set<Integer> years = new TreeSet<>(Comparator.reverseOrder());
        years.addAll(salesMap.keySet());
        years.addAll(costMap.keySet());
        years.addAll(purchaseMap.keySet());

        List<AnnualStatsVO> result = new java.util.ArrayList<>();
        for (Integer year : years) {
            BigDecimal salesAmount = defaultAmount(salesMap.get(year));
            BigDecimal salesCost = defaultAmount(costMap.get(year));
            BigDecimal grossProfit = salesAmount.subtract(salesCost);

            AnnualStatsVO vo = new AnnualStatsVO();
            vo.setYear(year);
            vo.setPurchaseAmount(defaultAmount(purchaseMap.get(year)));
            vo.setSalesAmount(salesAmount);
            vo.setSalesCost(salesCost);
            vo.setGrossProfit(grossProfit);
            vo.setGrossProfitRate(calculateRate(grossProfit, salesAmount));
            result.add(vo);
        }
        return result;
    }

    /** 导出 xlsx（POI 范式同 BOM 导出），内容与页面表格同口径同列 */
    public byte[] exportAnnualStats() throws IOException {
        List<AnnualStatsVO> rows = getAnnualStats(); // 内部已做权限校验

        try (XSSFWorkbook wb = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("年度经营统计");

            Row header = sheet.createRow(0);
            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                header.createCell(i).setCellValue(EXPORT_HEADERS[i]);
            }

            int rowIndex = 1;
            for (AnnualStatsVO vo : rows) {
                Row row = sheet.createRow(rowIndex++);
                row.createCell(0).setCellValue(vo.getYear());
                setNumber(row, 1, vo.getPurchaseAmount());
                setNumber(row, 2, vo.getSalesAmount());
                setNumber(row, 3, vo.getSalesCost());
                setNumber(row, 4, vo.getGrossProfit());
                if (vo.getGrossProfitRate() == null) {
                    row.createCell(5).setCellValue("—");
                } else {
                    setNumber(row, 5, vo.getGrossProfitRate());
                }
            }

            for (int i = 0; i < EXPORT_HEADERS.length; i++) {
                sheet.autoSizeColumn(i);
            }

            wb.write(out);
            return out.toByteArray();
        }
    }

    private void setNumber(Row row, int column, BigDecimal value) {
        Cell cell = row.createCell(column);
        cell.setCellValue(defaultAmount(value).doubleValue());
    }

    private Map<Integer, BigDecimal> toYearMap(List<BizSalesMapper.YearAmountAgg> rows) {
        Map<Integer, BigDecimal> map = new HashMap<>();
        for (BizSalesMapper.YearAmountAgg row : rows) {
            if (row.getStatYear() != null) {
                map.merge(row.getStatYear(), defaultAmount(row.getAmount()), BigDecimal::add);
            }
        }
        return map;
    }

    private void subtract(Map<Integer, BigDecimal> target, Map<Integer, BigDecimal> source) {
        // 不能用 Map.merge：key 不存在时 merge 会原样放入正值（退货冲减需 0 - amount）
        source.forEach((year, amount) ->
                target.compute(year, (y, cur) -> (cur == null ? BigDecimal.ZERO : cur).subtract(amount)));
    }

    /** 销售额 <= 0 时毛利率无意义，返回 null（前端显示「—」） */
    private BigDecimal calculateRate(BigDecimal grossProfit, BigDecimal salesAmount) {
        if (salesAmount == null || salesAmount.compareTo(BigDecimal.ZERO) <= 0) {
            return null;
        }
        return grossProfit
                .divide(salesAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));
    }

    private BigDecimal defaultAmount(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private void requireFinanceAnnualStatsAccess() {
        if (authzService.isSuperAdmin()) {
            throw BusinessException.forbidden("年度经营统计仅财务部门管理员可访问");
        }
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_FINANCE, "仅财务部门管理员可访问年度经营统计");
    }
}
