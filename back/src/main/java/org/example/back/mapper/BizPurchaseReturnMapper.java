package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.example.back.entity.BizPurchaseReturn;

import java.util.List;

public interface BizPurchaseReturnMapper extends BaseMapper<BizPurchaseReturn> {

    // ============================== 年度经营统计（ADR-0008） ==============================

    /** D111：已退货按明细行金额合计，按退货完成时间归年冲减（complete_time 缺失回退 operation_time） */
    @Select("""
            SELECT YEAR(COALESCE(p.complete_time, p.operation_time)) AS stat_year, SUM(d.total_price) AS amount
            FROM biz_purchase_return_detail d
            JOIN biz_purchase_return p ON p.id = d.return_id
            WHERE d.is_deleted = 0
              AND p.is_deleted = 0
              AND p.biz_status = 1
              AND p.confirm_status = 3
            GROUP BY YEAR(COALESCE(p.complete_time, p.operation_time))
            """)
    List<BizSalesMapper.YearAmountAgg> yearlyValidPurchaseReturnAmount();
}
