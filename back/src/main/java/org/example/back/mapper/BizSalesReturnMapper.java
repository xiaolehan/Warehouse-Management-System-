package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.back.entity.BizSalesReturn;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
/**
 * 销售退货记录 Mapper 接口,数据库操作接口
 * D110 头行结构：金额/数量/成本/品牌维度统计全部下沉到 biz_sales_return_detail 行聚合（JOIN 头表供时间/状态过滤）。
 */
public interface BizSalesReturnMapper extends BaseMapper<BizSalesReturn> {

		@Select("""
						<script>
						SELECT COALESCE(SUM(rd.total_price), 0)
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						</script>
						""")
		BigDecimal sumReturnAmount(@Param("startTime") LocalDateTime startTime,
															 @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT COALESCE(SUM(rd.quantity), 0)
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						</script>
						""")
		Long sumReturnQuantity(@Param("startTime") LocalDateTime startTime,
													 @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT COALESCE(SUM(rd.total_price), 0)
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						</script>
						""")
		BigDecimal sumValidReturnAmount(@Param("startTime") LocalDateTime startTime,
												 @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT COALESCE(SUM(rd.quantity), 0)
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						</script>
						""")
		Long sumValidReturnQuantity(@Param("startTime") LocalDateTime startTime,
										  @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT COALESCE(SUM(COALESCE(rd.cost_total_price, 0)), 0)
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						</script>
						""")
		BigDecimal sumEstimatedReturnCost(@Param("startTime") LocalDateTime startTime,
											  @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌') AS name,
						       SUM(-rd.total_price + COALESCE(rd.cost_total_price, 0)) AS amount
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						LEFT JOIN base_goods bg ON rd.goods_id = bg.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						GROUP BY COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌')
						</script>
						""")
		List<BizSalesMapper.BrandAmountAgg> brandGrossProfitPart(@Param("startTime") LocalDateTime startTime,
													 @Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT DATE(r.operation_time) AS stat_date,
						       SUM(rd.total_price) AS amount
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						GROUP BY DATE(r.operation_time)
						ORDER BY DATE(r.operation_time)
						</script>
						""")
		List<BizSalesMapper.DailyAmountAgg> dailyValidReturnAmount(@Param("startTime") LocalDateTime startTime,
														@Param("endTime") LocalDateTime endTime);

		@Select("""
						<script>
						SELECT DATE(r.operation_time) AS stat_date,
						       SUM(COALESCE(rd.cost_total_price, 0)) AS amount
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
							AND r.operation_time <![CDATA[>=]]> #{startTime}
							AND r.operation_time <![CDATA[<]]> #{endTime}
						GROUP BY DATE(r.operation_time)
						ORDER BY DATE(r.operation_time)
						</script>
						""")
		List<BizSalesMapper.DailyAmountAgg> dailyEstimatedReturnCost(@Param("startTime") LocalDateTime startTime,
														 @Param("endTime") LocalDateTime endTime);

		// ============================== 年度经营统计（ADR-0008） ==============================

		@Select("""
						SELECT YEAR(r.operation_time) AS stat_year, SUM(rd.total_price) AS amount
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
						GROUP BY YEAR(r.operation_time)
						""")
		List<BizSalesMapper.YearAmountAgg> yearlyValidReturnAmount();

		@Select("""
						SELECT YEAR(r.operation_time) AS stat_year, SUM(COALESCE(rd.cost_total_price, 0)) AS amount
						FROM biz_sales_return_detail rd
						JOIN biz_sales_return r ON rd.return_id = r.id
						WHERE r.is_deleted = 0 AND rd.is_deleted = 0
							AND r.biz_status = 1
							AND r.confirm_status = 2
						GROUP BY YEAR(r.operation_time)
						""")
		List<BizSalesMapper.YearAmountAgg> yearlyValidReturnCost();
}
