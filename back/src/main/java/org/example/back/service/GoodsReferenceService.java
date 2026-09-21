package org.example.back.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProduction;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionQc;
import org.example.back.entity.BizPurchaseDetail;
import org.example.back.entity.BizPurchaseRequestDetail;
import org.example.back.entity.BizPurchaseReturnDetail;
import org.example.back.entity.BizSalesDetail;
import org.example.back.entity.BizSalesReturnDetail;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.mapper.BizPurchaseDetailMapper;
import org.example.back.mapper.BizPurchaseRequestDetailMapper;
import org.example.back.mapper.BizPurchaseReturnDetailMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesReturnDetailMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * D66：成品主数据删除安全口径——「库存为 0 且未被任何单据引用」才允许删除。
 * 引用面：有效 BOM 主表/生产任务单/生产入库/销售/销售退货/商品进货/商品退货/采购申请明细/领料明细/质检记录/BOM 明细。
 * 被 BomService（BOM 删除级联清理）与 GoodsService（成品手工删除守卫）共用，避免相互依赖。
 * 注意：BOM 删除级联先软删 BOM 再调本检查，@TableLogic 已过滤，不会自我拦截。
 */
@Service
public class GoodsReferenceService {

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;
    @Autowired
    private BizProductionMapper bizProductionMapper;
    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;
    @Autowired
    private BizSalesReturnDetailMapper bizSalesReturnDetailMapper;
    @Autowired
    private BizPurchaseDetailMapper bizPurchaseDetailMapper;
    @Autowired
    private BizPurchaseReturnDetailMapper bizPurchaseReturnDetailMapper;
    @Autowired
    private BizPurchaseRequestDetailMapper bizPurchaseRequestDetailMapper;
    @Autowired
    private BizPickListDetailMapper bizPickListDetailMapper;
    @Autowired
    private BizProductionQcMapper bizProductionQcMapper;
    @Autowired
    private BizBomDetailMapper bizBomDetailMapper;
    @Autowired
    private BizBomMapper bizBomMapper;

    /** 成品主档是否可安全删除（库存为 0 且无任何单据引用） */
    public boolean isProductDeletable(Long goodsId, Integer stock) {
        return (stock == null || stock == 0) && !hasAnyDocumentReference(goodsId);
    }

    /** D66：该成品名下未完结生产任务单数（删 BOM 软保护口径，状态集见 BizProductionOrder.UNFINISHED_STATUSES） */
    public long countUnfinishedOrders(Long goodsId) {
        return bizProductionOrderMapper.selectCount(Wrappers.<BizProductionOrder>lambdaQuery()
                .eq(BizProductionOrder::getGoodsId, goodsId)
                .in(BizProductionOrder::getStatus, BizProductionOrder.UNFINISHED_STATUSES));
    }

    public boolean hasAnyDocumentReference(Long goodsId) {
        return bizBomMapper.selectCount(Wrappers.<BizBom>lambdaQuery().eq(BizBom::getGoodsId, goodsId)) > 0
                || bizProductionOrderMapper.selectCount(Wrappers.<BizProductionOrder>lambdaQuery().eq(BizProductionOrder::getGoodsId, goodsId)) > 0
                || bizProductionMapper.selectCount(Wrappers.<BizProduction>lambdaQuery().eq(BizProduction::getGoodsId, goodsId)) > 0
                || bizSalesDetailMapper.selectCount(Wrappers.<BizSalesDetail>lambdaQuery().eq(BizSalesDetail::getGoodsId, goodsId)) > 0
                || bizSalesReturnDetailMapper.selectCount(Wrappers.<BizSalesReturnDetail>lambdaQuery().eq(BizSalesReturnDetail::getGoodsId, goodsId)) > 0
                || bizPurchaseDetailMapper.selectCount(Wrappers.<BizPurchaseDetail>lambdaQuery().eq(BizPurchaseDetail::getGoodsId, goodsId)) > 0
                || bizPurchaseReturnDetailMapper.selectCount(Wrappers.<BizPurchaseReturnDetail>lambdaQuery().eq(BizPurchaseReturnDetail::getGoodsId, goodsId)) > 0
                || bizPurchaseRequestDetailMapper.selectCount(Wrappers.<BizPurchaseRequestDetail>lambdaQuery().eq(BizPurchaseRequestDetail::getGoodsId, goodsId)) > 0
                || bizPickListDetailMapper.selectCount(Wrappers.<BizPickListDetail>lambdaQuery().eq(BizPickListDetail::getGoodsId, goodsId)) > 0
                || bizProductionQcMapper.selectCount(Wrappers.<BizProductionQc>lambdaQuery().eq(BizProductionQc::getGoodsId, goodsId)) > 0
                || bizBomDetailMapper.selectCount(Wrappers.<BizBomDetail>lambdaQuery().eq(BizBomDetail::getGoodsId, goodsId)) > 0;
    }
}
