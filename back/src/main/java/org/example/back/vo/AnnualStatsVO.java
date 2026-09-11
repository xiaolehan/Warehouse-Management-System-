package org.example.back.vo;

import java.math.BigDecimal;

/**
 * 年度经营统计行（ADR-0008）：按自然年汇总采购总支出/销售总营收/销售成本/毛利/毛利率。
 * 毛利率在销售额 <= 0 时为 null（前端显示「—」）。
 */
public class AnnualStatsVO {

    private Integer year;
    private BigDecimal purchaseAmount;
    private BigDecimal salesAmount;
    private BigDecimal salesCost;
    private BigDecimal grossProfit;
    private BigDecimal grossProfitRate;

    public Integer getYear() {
        return year;
    }

    public void setYear(Integer year) {
        this.year = year;
    }

    public BigDecimal getPurchaseAmount() {
        return purchaseAmount;
    }

    public void setPurchaseAmount(BigDecimal purchaseAmount) {
        this.purchaseAmount = purchaseAmount;
    }

    public BigDecimal getSalesAmount() {
        return salesAmount;
    }

    public void setSalesAmount(BigDecimal salesAmount) {
        this.salesAmount = salesAmount;
    }

    public BigDecimal getSalesCost() {
        return salesCost;
    }

    public void setSalesCost(BigDecimal salesCost) {
        this.salesCost = salesCost;
    }

    public BigDecimal getGrossProfit() {
        return grossProfit;
    }

    public void setGrossProfit(BigDecimal grossProfit) {
        this.grossProfit = grossProfit;
    }

    public BigDecimal getGrossProfitRate() {
        return grossProfitRate;
    }

    public void setGrossProfitRate(BigDecimal grossProfitRate) {
        this.grossProfitRate = grossProfitRate;
    }
}
