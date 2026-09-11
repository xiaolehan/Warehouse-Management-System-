package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.back.entity.BizPurchase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface BizPurchaseMapper extends BaseMapper<BizPurchase> {

		@Select("""
						<script>
						SELECT p.unit_price
						FROM biz_purchase p
						WHERE p.goods_id = #{goodsId}
							AND p.is_deleted = 0
							AND p.biz_status = 1
							AND p.confirm_status = 3
							AND p.operation_time <![CDATA[<=]]> #{bizTime}
						ORDER BY p.operation_time DESC, p.id DESC
						LIMIT 1
						</script>
						""")
		BigDecimal latestValidUnitPrice(@Param("goodsId") Long goodsId,
																		@Param("bizTime") LocalDateTime bizTime);

		// ============================== 年度经营统计（ADR-0008） ==============================

		/** 已入库进货单按入库确认时间归年（confirm_time 缺失的历史行回退 operation_time） */
		@Select("""
						SELECT YEAR(COALESCE(confirm_time, operation_time)) AS stat_year, SUM(total_price) AS amount
						FROM biz_purchase
						WHERE is_deleted = 0
							AND biz_status = 1
							AND confirm_status = 3
						GROUP BY YEAR(COALESCE(confirm_time, operation_time))
						""")
		List<BizSalesMapper.YearAmountAgg> yearlyValidPurchaseAmount();
}
