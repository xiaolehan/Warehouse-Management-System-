package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.example.back.entity.BizPurchaseReturn;

import java.util.List;

public interface BizPurchaseReturnMapper extends BaseMapper<BizPurchaseReturn> {

    // ============================== 年度经营统计（ADR-0008） ==============================

    /** 已退货单按退货完成时间归年冲减（complete_time 缺失的历史行回退 operation_time） */
    @Select("""
            SELECT YEAR(COALESCE(complete_time, operation_time)) AS stat_year, SUM(total_price) AS amount
            FROM biz_purchase_return
            WHERE is_deleted = 0
              AND biz_status = 1
              AND confirm_status = 3
            GROUP BY YEAR(COALESCE(complete_time, operation_time))
            """)
    List<BizSalesMapper.YearAmountAgg> yearlyValidPurchaseReturnAmount();
}
