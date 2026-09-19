package org.example.back.vo;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** D102：物料进价历史行——有效已入库采购记录快照（进价列「历史」弹窗用） */
public class GoodsPurchaseHistoryVO {

    /** 进货单号 */
    private String purchaseNo;
    /** 进货单价 */
    private BigDecimal unitPrice;
    /** 入库数量 */
    private Integer quantity;
    /** 总金额 */
    private BigDecimal totalPrice;
    /** 进货（到货提交/新增）时间 */
    private LocalDateTime operationTime;
    /** 本次入库确认时间 */
    private LocalDateTime confirmTime;

    public String getPurchaseNo() {
        return purchaseNo;
    }

    public void setPurchaseNo(String purchaseNo) {
        this.purchaseNo = purchaseNo;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }

    public void setUnitPrice(BigDecimal unitPrice) {
        this.unitPrice = unitPrice;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public void setQuantity(Integer quantity) {
        this.quantity = quantity;
    }

    public BigDecimal getTotalPrice() {
        return totalPrice;
    }

    public void setTotalPrice(BigDecimal totalPrice) {
        this.totalPrice = totalPrice;
    }

    public LocalDateTime getOperationTime() {
        return operationTime;
    }

    public void setOperationTime(LocalDateTime operationTime) {
        this.operationTime = operationTime;
    }

    public LocalDateTime getConfirmTime() {
        return confirmTime;
    }

    public void setConfirmTime(LocalDateTime confirmTime) {
        this.confirmTime = confirmTime;
    }
}
