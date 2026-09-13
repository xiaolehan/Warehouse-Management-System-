package org.example.back.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Select;
import org.example.back.entity.BizStocktake;

import java.util.List;
import java.util.Map;

public interface BizStocktakeMapper extends BaseMapper<BizStocktake> {

    /**
     * 每个商品最近一次「审核生效」的盘点时间（仅已盘行参与，D81 派生口径）
     */
    @Select("SELECT d.goods_id AS goodsId, MAX(s.review_time) AS lastTime " +
            "FROM biz_stocktake_detail d " +
            "JOIN biz_stocktake s ON d.stocktake_id = s.id " +
            "WHERE s.status = 3 AND s.is_deleted = 0 AND d.is_deleted = 0 AND d.actual_qty IS NOT NULL " +
            "GROUP BY d.goods_id")
    List<Map<String, Object>> selectLastStocktakeTimes();
}
