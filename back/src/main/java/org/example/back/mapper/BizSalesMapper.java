package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.back.entity.BizSales;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
/**
 * 销售记录 Mapper 接口,数据库操作接口
 * D110 头行结构：金额/数量/成本/商品维度统计全部下沉到 biz_sales_detail 行聚合（JOIN 头表供时间/状态过滤）。
 */
public interface BizSalesMapper extends BaseMapper<BizSales> {
	@Select("SELECT MIN(operation_time) FROM biz_sales WHERE is_deleted = 0 AND confirm_status = 2")
	LocalDateTime minOperationTime();

	@Select("SELECT MAX(operation_time) FROM biz_sales WHERE is_deleted = 0 AND confirm_status = 2")
	LocalDateTime maxOperationTime();

	@Select("SELECT MIN(operation_time) FROM biz_sales WHERE is_deleted = 0 AND biz_status = 1 AND confirm_status = 2")
	LocalDateTime minValidOperationTime();

	@Select("SELECT MAX(operation_time) FROM biz_sales WHERE is_deleted = 0 AND biz_status = 1 AND confirm_status = 2")
	LocalDateTime maxValidOperationTime();

	@Select("""
			<script>
			SELECT COALESCE(SUM(d.total_price), 0)
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			</script>
			""")
	BigDecimal sumSalesAmount(@Param("startTime") LocalDateTime startTime,
							  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(SUM(d.quantity), 0)
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			</script>
			""")
	Long sumSalesQuantity(@Param("startTime") LocalDateTime startTime,
						  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(SUM(d.total_price), 0)
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			</script>
			""")
	BigDecimal sumValidSalesAmount(@Param("startTime") LocalDateTime startTime,
								  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(SUM(d.quantity), 0)
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			</script>
			""")
	Long sumValidSalesQuantity(@Param("startTime") LocalDateTime startTime,
							  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(SUM(COALESCE(d.cost_total_price, 0)), 0)
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			</script>
			""")
	BigDecimal sumEstimatedSalesCost(@Param("startTime") LocalDateTime startTime,
								 @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT d.goods_name AS name, SUM(d.quantity) AS quantity
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY d.goods_id, d.goods_name
			ORDER BY quantity DESC
			LIMIT 5
			</script>
			""")
	List<TopGoodsAgg> topGoods(@Param("startTime") LocalDateTime startTime,
							   @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌') AS name,
			       SUM(d.total_price) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			LEFT JOIN base_goods bg ON d.goods_id = bg.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.confirm_status = 2
			  AND (bg.is_deleted = 0 OR bg.id IS NULL)
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌')
			ORDER BY amount DESC
			</script>
			""")
	List<BrandAmountAgg> brandSalesAmount(@Param("startTime") LocalDateTime startTime,
										  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT DATE(s.operation_time) AS stat_date,
			       SUM(d.total_price) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY DATE(s.operation_time)
			ORDER BY DATE(s.operation_time)
			</script>
			""")
	List<DailyAmountAgg> dailySalesAmount(@Param("startTime") LocalDateTime startTime,
										  @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌') AS name,
			       SUM(d.total_price - COALESCE(d.cost_total_price, 0)) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			LEFT JOIN base_goods bg ON d.goods_id = bg.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY COALESCE(NULLIF(TRIM(bg.brand), ''), '未标注品牌')
			</script>
			""")
	List<BrandAmountAgg> brandGrossProfitPart(@Param("startTime") LocalDateTime startTime,
								   @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT DATE(s.operation_time) AS stat_date,
			       SUM(d.total_price) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY DATE(s.operation_time)
			ORDER BY DATE(s.operation_time)
			</script>
			""")
	List<DailyAmountAgg> dailyValidSalesAmount(@Param("startTime") LocalDateTime startTime,
								   @Param("endTime") LocalDateTime endTime);

	@Select("""
			<script>
			SELECT DATE(s.operation_time) AS stat_date,
			       SUM(COALESCE(d.cost_total_price, 0)) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			  AND s.operation_time <![CDATA[>=]]> #{startTime}
			  AND s.operation_time <![CDATA[<]]> #{endTime}
			GROUP BY DATE(s.operation_time)
			ORDER BY DATE(s.operation_time)
			</script>
			""")
	List<DailyAmountAgg> dailyEstimatedSalesCost(@Param("startTime") LocalDateTime startTime,
									 @Param("endTime") LocalDateTime endTime);

	// ============================== 年度经营统计（ADR-0008） ==============================

	@Select("""
			SELECT YEAR(s.operation_time) AS stat_year, SUM(d.total_price) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			GROUP BY YEAR(s.operation_time)
			""")
	List<YearAmountAgg> yearlyValidSalesAmount();

	@Select("""
			SELECT YEAR(s.operation_time) AS stat_year, SUM(COALESCE(d.cost_total_price, 0)) AS amount
			FROM biz_sales_detail d
			JOIN biz_sales s ON d.sales_id = s.id
			WHERE s.is_deleted = 0 AND d.is_deleted = 0
			  AND s.biz_status = 1
			  AND s.confirm_status = 2
			GROUP BY YEAR(s.operation_time)
			""")
	List<YearAmountAgg> yearlyValidSalesCost();

	class YearAmountAgg {
		private Integer statYear;
		private BigDecimal amount;

		public Integer getStatYear() {
			return statYear;
		}

		public void setStatYear(Integer statYear) {
			this.statYear = statYear;
		}

		public BigDecimal getAmount() {
			return amount;
		}

		public void setAmount(BigDecimal amount) {
			this.amount = amount;
		}
	}

	class TopGoodsAgg {
		private String name;
		private Long quantity;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Long getQuantity() {
			return quantity;
		}

		public void setQuantity(Long quantity) {
			this.quantity = quantity;
		}
	}

	class BrandAmountAgg {
		private String name;
		private BigDecimal amount;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public BigDecimal getAmount() {
			return amount;
		}

		public void setAmount(BigDecimal amount) {
			this.amount = amount;
		}
	}

	class DailyAmountAgg {
		private String statDate;
		private BigDecimal amount;

		public String getStatDate() {
			return statDate;
		}

		public void setStatDate(String statDate) {
			this.statDate = statDate;
		}

		public BigDecimal getAmount() {
			return amount;
		}

		public void setAmount(BigDecimal amount) {
			this.amount = amount;
		}
	}
}
