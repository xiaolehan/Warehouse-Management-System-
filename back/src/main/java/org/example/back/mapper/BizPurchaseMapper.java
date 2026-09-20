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

		/**
		 * D110/review：多成品最近有效进价批量预取（口径同 latestValidUnitPrice，
		 * 窗口函数取每成品 operation_time 最新一行），销售建单按行快照成本用。
		 */
		@Select("""
				<script>
				SELECT t.goods_id AS goodsId, t.unit_price AS unitPrice
				FROM (
					SELECT p.goods_id, p.unit_price,
					       ROW_NUMBER() OVER (PARTITION BY p.goods_id ORDER BY p.operation_time DESC, p.id DESC) AS rn
					FROM biz_purchase p
					WHERE p.is_deleted = 0
						AND p.biz_status = 1
						AND p.confirm_status = 3
						AND p.operation_time <![CDATA[<=]]> #{bizTime}
						AND p.goods_id IN
						<foreach collection="goodsIds" item="gid" open="(" separator="," close=")">#{gid}</foreach>
				) t
				WHERE t.rn = 1
				</script>
				""")
		List<LatestPurchasePrice> latestValidUnitPrices(@Param("goodsIds") java.util.Collection<Long> goodsIds,
																										@Param("bizTime") LocalDateTime bizTime);

		/** 最新有效进价行（goodsId + unitPrice，MyBatis 按别名映射） */
		class LatestPurchasePrice {
			private Long goodsId;
			private BigDecimal unitPrice;

			public Long getGoodsId() {
				return goodsId;
			}

			public void setGoodsId(Long goodsId) {
				this.goodsId = goodsId;
			}

			public BigDecimal getUnitPrice() {
				return unitPrice;
			}

			public void setUnitPrice(BigDecimal unitPrice) {
				this.unitPrice = unitPrice;
			}
		}

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
