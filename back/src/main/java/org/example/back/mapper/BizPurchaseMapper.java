package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.example.back.entity.BizPurchase;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public interface BizPurchaseMapper extends BaseMapper<BizPurchase> {

		// D111：进价最小记录粒度下沉到明细行——biz_purchase_detail JOIN biz_purchase
		@Select("""
						<script>
						SELECT d.unit_price
						FROM biz_purchase_detail d
						JOIN biz_purchase p ON p.id = d.purchase_id
						WHERE d.goods_id = #{goodsId}
							AND d.is_deleted = 0
							AND p.is_deleted = 0
							AND p.biz_status = 1
							AND p.confirm_status = 3
							AND p.operation_time <![CDATA[<=]]> #{bizTime}
						ORDER BY p.operation_time DESC, d.id DESC
						LIMIT 1
						</script>
						""")
		BigDecimal latestValidUnitPrice(@Param("goodsId") Long goodsId,
																		@Param("bizTime") LocalDateTime bizTime);

		/**
		 * D110/review、D111：多物料最近有效进价批量预取（口径同 latestValidUnitPrice，
		 * 窗口函数取每物料 operation_time 最新一明细行），销售建单按行快照成本用。
		 */
		@Select("""
				<script>
				SELECT t.goods_id AS goodsId, t.unit_price AS unitPrice
				FROM (
					SELECT d.goods_id, d.unit_price,
					       ROW_NUMBER() OVER (PARTITION BY d.goods_id ORDER BY p.operation_time DESC, d.id DESC) AS rn
					FROM biz_purchase_detail d
					JOIN biz_purchase p ON p.id = d.purchase_id
					WHERE d.is_deleted = 0
						AND p.is_deleted = 0
						AND p.biz_status = 1
						AND p.confirm_status = 3
						AND p.operation_time <![CDATA[<=]]> #{bizTime}
						AND d.goods_id IN
						<foreach collection="goodsIds" item="gid" open="(" separator="," close=")">#{gid}</foreach>
				) t
				WHERE t.rn = 1
				</script>
				""")
		List<LatestPurchasePrice> latestValidUnitPrices(@Param("goodsIds") java.util.Collection<Long> goodsIds,
																										@Param("bizTime") LocalDateTime bizTime);

		/**
		 * D123/D131：多物料「最新供应商」批量查询——最近一张 已入库+正常+记录了供应商 的进货明细行的
		 * 行级供应商（COALESCE 行级→头级：头级覆盖存量手动单，行级承载申请渠道单）。
		 * 口径同 latestValidUnitPrices（窗口函数按 operation_time 最新取一）；
		 * COALESCE 非空过滤掉完全无供应商信息的单据（不参与最新性）。
		 */
		@Select("""
				<script>
				SELECT t.goods_id AS goodsId, t.supplier_id AS supplierId
				FROM (
					SELECT d.goods_id, COALESCE(d.supplier_id, p.supplier_id) AS supplier_id,
					       ROW_NUMBER() OVER (PARTITION BY d.goods_id ORDER BY p.operation_time DESC, d.id DESC) AS rn
					FROM biz_purchase_detail d
					JOIN biz_purchase p ON p.id = d.purchase_id
					WHERE d.is_deleted = 0
						AND p.is_deleted = 0
						AND p.biz_status = 1
						AND p.confirm_status = 3
						AND COALESCE(d.supplier_id, p.supplier_id) IS NOT NULL
						AND d.goods_id IN
						<foreach collection="goodsIds" item="gid" open="(" separator="," close=")">#{gid}</foreach>
				) t
				WHERE t.rn = 1
				</script>
				""")
		List<LatestPurchaseSupplier> latestValidSuppliers(@Param("goodsIds") java.util.Collection<Long> goodsIds);

		/** 最新供应商行（goodsId + supplierId，MyBatis 按别名映射） */
		class LatestPurchaseSupplier {
			private Long goodsId;
			private Long supplierId;

			public Long getGoodsId() {
				return goodsId;
			}

			public void setGoodsId(Long goodsId) {
				this.goodsId = goodsId;
			}

			public Long getSupplierId() {
				return supplierId;
			}

			public void setSupplierId(Long supplierId) {
				this.supplierId = supplierId;
			}
		}

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

		/** D111：已入库进货按明细行金额合计，按入库确认时间归年（confirm_time 缺失回退 operation_time） */
		@Select("""
						SELECT YEAR(COALESCE(p.confirm_time, p.operation_time)) AS stat_year, SUM(d.total_price) AS amount
						FROM biz_purchase_detail d
						JOIN biz_purchase p ON p.id = d.purchase_id
						WHERE d.is_deleted = 0
							AND p.is_deleted = 0
							AND p.biz_status = 1
							AND p.confirm_status = 3
						GROUP BY YEAR(COALESCE(p.confirm_time, p.operation_time))
						""")
		List<BizSalesMapper.YearAmountAgg> yearlyValidPurchaseAmount();
}
