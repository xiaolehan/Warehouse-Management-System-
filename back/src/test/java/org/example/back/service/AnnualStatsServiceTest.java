package org.example.back.service;

import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.example.back.common.exception.BusinessException;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizPurchaseReturnMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.mapper.BizSalesReturnMapper;
import org.example.back.vo.AnnualStatsVO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnualStatsServiceTest {

    @Mock
    private BizSalesMapper bizSalesMapper;

    @Mock
    private BizSalesReturnMapper bizSalesReturnMapper;

    @Mock
    private BizPurchaseMapper bizPurchaseMapper;

    @Mock
    private BizPurchaseReturnMapper bizPurchaseReturnMapper;

    @Mock
    private AuthzService authzService;

    @InjectMocks
    private AnnualStatsService annualStatsService;

    private static BizSalesMapper.YearAmountAgg row(int year, String amount) {
        BizSalesMapper.YearAmountAgg agg = new BizSalesMapper.YearAmountAgg();
        agg.setStatYear(year);
        agg.setAmount(new BigDecimal(amount));
        return agg;
    }

    /** 默认全部空，单测按需覆盖其中几条 */
    private void stubAllEmpty() {
        when(bizSalesMapper.yearlyValidSalesAmount()).thenReturn(List.of());
        when(bizSalesMapper.yearlyValidSalesCost()).thenReturn(List.of());
        when(bizSalesReturnMapper.yearlyValidReturnAmount()).thenReturn(List.of());
        when(bizSalesReturnMapper.yearlyValidReturnCost()).thenReturn(List.of());
        when(bizPurchaseMapper.yearlyValidPurchaseAmount()).thenReturn(List.of());
        when(bizPurchaseReturnMapper.yearlyValidPurchaseReturnAmount()).thenReturn(List.of());
    }

    @Test
    void getAnnualStats_shouldNetPurchaseReturnsAndComputeProfit() {
        // 采购额只取 biz_purchase 单一来源：采购申请确认入库已逐明细写 biz_purchase
        // （PurchaseRequestService.confirmReceive → createInternal），不可再加采购申请明细（会翻倍）
        when(bizSalesMapper.yearlyValidSalesAmount()).thenReturn(List.of(row(2024, "1000"), row(2025, "500")));
        when(bizSalesMapper.yearlyValidSalesCost()).thenReturn(List.of(row(2024, "600"), row(2025, "200")));
        when(bizSalesReturnMapper.yearlyValidReturnAmount()).thenReturn(List.of(row(2025, "100")));
        when(bizSalesReturnMapper.yearlyValidReturnCost()).thenReturn(List.of(row(2025, "40")));
        when(bizPurchaseMapper.yearlyValidPurchaseAmount()).thenReturn(List.of(row(2024, "300"), row(2025, "150")));
        when(bizPurchaseReturnMapper.yearlyValidPurchaseReturnAmount()).thenReturn(List.of(row(2025, "30")));

        List<AnnualStatsVO> stats = annualStatsService.getAnnualStats();

        assertEquals(2, stats.size());

        // 年份倒序：2025 在前
        AnnualStatsVO y2025 = stats.get(0);
        assertEquals(2025, y2025.getYear());
        assertAmount("400", y2025.getSalesAmount());      // 500 - 100（退货冲减）
        assertAmount("160", y2025.getSalesCost());        // 200 - 40
        assertAmount("240", y2025.getGrossProfit());      // 400 - 160
        assertAmount("60.0000", y2025.getGrossProfitRate());
        assertAmount("120", y2025.getPurchaseAmount());   // 150 - 30（采购退货冲减）

        AnnualStatsVO y2024 = stats.get(1);
        assertEquals(2024, y2024.getYear());
        assertAmount("1000", y2024.getSalesAmount());
        assertAmount("600", y2024.getSalesCost());
        assertAmount("400", y2024.getGrossProfit());
        assertAmount("40.0000", y2024.getGrossProfitRate());
        assertAmount("300", y2024.getPurchaseAmount());
    }

    @Test
    void getAnnualStats_shouldAttributeReturnsToTheirOwnYear() {
        // 2024 销售、2025 才发生退货：2024 不受冲减，退货冲到 2025（允许出现负值行）
        when(bizSalesMapper.yearlyValidSalesAmount()).thenReturn(List.of(row(2024, "1000")));
        when(bizSalesMapper.yearlyValidSalesCost()).thenReturn(List.of(row(2024, "600")));
        when(bizSalesReturnMapper.yearlyValidReturnAmount()).thenReturn(List.of(row(2025, "100")));
        when(bizSalesReturnMapper.yearlyValidReturnCost()).thenReturn(List.of(row(2025, "40")));
        when(bizPurchaseMapper.yearlyValidPurchaseAmount()).thenReturn(List.of());
        when(bizPurchaseReturnMapper.yearlyValidPurchaseReturnAmount()).thenReturn(List.of());

        List<AnnualStatsVO> stats = annualStatsService.getAnnualStats();

        assertEquals(2, stats.size());
        AnnualStatsVO y2025 = stats.get(0);
        assertAmount("-100", y2025.getSalesAmount());
        assertAmount("-40", y2025.getSalesCost());
        assertAmount("-60", y2025.getGrossProfit());
        assertNull(y2025.getGrossProfitRate()); // 销售额 <= 0 时毛利率无意义

        AnnualStatsVO y2024 = stats.get(1);
        assertAmount("1000", y2024.getSalesAmount());
        assertAmount("600", y2024.getSalesCost());
    }

    @Test
    void getAnnualStats_shouldIncludePurchaseOnlyYearWithZeroSalesAndNullRate() {
        stubAllEmpty();
        when(bizPurchaseMapper.yearlyValidPurchaseAmount()).thenReturn(List.of(row(2023, "80")));

        List<AnnualStatsVO> stats = annualStatsService.getAnnualStats();

        assertEquals(1, stats.size());
        AnnualStatsVO y2023 = stats.get(0);
        assertEquals(2023, y2023.getYear());
        assertAmount("80", y2023.getPurchaseAmount());
        assertAmount("0", y2023.getSalesAmount());
        assertAmount("0", y2023.getSalesCost());
        assertAmount("0", y2023.getGrossProfit());
        assertNull(y2023.getGrossProfitRate());
    }

    @Test
    void getAnnualStats_shouldReturnEmptyListWhenNoData() {
        stubAllEmpty();

        List<AnnualStatsVO> stats = annualStatsService.getAnnualStats();

        assertTrue(stats.isEmpty());
    }

    @Test
    void getAnnualStats_shouldRejectSuperAdmin() {
        when(authzService.isSuperAdmin()).thenReturn(true);

        assertThrows(BusinessException.class, () -> annualStatsService.getAnnualStats());
    }

    @Test
    void getAnnualStats_shouldPropagateAuthzRejection() {
        doThrow(BusinessException.forbidden("仅财务部门管理员可访问年度经营统计"))
                .when(authzService).requireDeptAdminOrSuperAdmin(anyString(), anyString());

        assertThrows(BusinessException.class, () -> annualStatsService.getAnnualStats());
    }

    @Test
    void exportAnnualStats_shouldBuildXlsxWithSameRows() throws Exception {
        stubAllEmpty();
        when(bizSalesMapper.yearlyValidSalesAmount()).thenReturn(List.of(row(2024, "1000")));
        when(bizSalesMapper.yearlyValidSalesCost()).thenReturn(List.of(row(2024, "600")));

        byte[] bytes = annualStatsService.exportAnnualStats();

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals(2, sheet.getLastRowNum() + 1); // 表头 + 1 行数据
            assertEquals(2024, (int) sheet.getRow(1).getCell(0).getNumericCellValue());
            assertEquals(1000.0, sheet.getRow(1).getCell(2).getNumericCellValue(), 0.001);
        }
    }

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "expected " + expected + " but was " + actual);
    }
}
