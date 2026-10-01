package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 销售取消守卫（需求一/ADR-0019，Q19/Q20a；会话 58 扩展触发源）：
 * 关联销售「已取消」的生产任务单（行终止 / 销售单作废 / 销售单删除三条通道同口径），
 * 冻结资源消耗动作——领料/补料/开工/完工报工/质检记录及处置/工序打卡/成品入库申请及确认；
 * 放行退料、终止任务、查看。生产侧闭环走既有手动终止流程（ADR-0005），完成后回执建单销售本人。
 * 采购侧半闸门：冻结认领/到货提交等"发起类"动作，放行确认入库等"收尾类"动作。
 *
 * 行解析（ADR-0013）：salesDetailId 直查，旧行为 NULL 时按 (sales_order_id, goods_id) 唯一解析
 * （uk_sales_goods 保证同成品一单只一行）；行已随销售单删除等场景解析不到 → 视作废口径处理。
 * 仅注入 Mapper（不依赖业务 Service），冻结点服务注入本守卫，避免循环依赖。
 */
@Service
public class SalesTerminateGuard {

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;

    @Autowired
    private BizSalesMapper bizSalesMapper;

    /**
     * 冻结信息：frozen=true 时 reason 为短文案（供前端标识/异常消息拼接），
     * 行终止场景附终止留痕（原因/时间/操作人）供前端 tooltip。
     */
    public static class FreezeInfo {
        private final boolean frozen;
        private final String reason;
        private final String terminateReason;
        private final LocalDateTime terminateTime;
        private final String terminatorName;

        public FreezeInfo(boolean frozen, String reason, String terminateReason,
                          LocalDateTime terminateTime, String terminatorName) {
            this.frozen = frozen;
            this.reason = reason;
            this.terminateReason = terminateReason;
            this.terminateTime = terminateTime;
            this.terminatorName = terminatorName;
        }

        public boolean isFrozen() {
            return frozen;
        }

        public String getReason() {
            return reason;
        }

        public String getTerminateReason() {
            return terminateReason;
        }

        public LocalDateTime getTerminateTime() {
            return terminateTime;
        }

        public String getTerminatorName() {
            return terminatorName;
        }
    }

    private static final FreezeInfo NOT_FROZEN = new FreezeInfo(false, null, null, null, null);

    /**
     * 生产动作入口守卫（按任务单 id）：任务单不存在时放行，交由调用方既有 requireEntity 报错。
     */
    public void ensureSalesLineActive(Long productionOrderId) {
        BizProductionOrder order = bizProductionOrderMapper.selectById(productionOrderId);
        ensureSalesLineActive(order);
    }

    /**
     * 生产动作入口守卫（任务单已加载处直接调用）：关联销售已取消（任一通道）→ 拦截并提示生产走终止流程。
     */
    public void ensureSalesLineActive(BizProductionOrder order) {
        FreezeInfo info = freezeInfo(order);
        if (!info.isFrozen()) {
            return;
        }
        throw BusinessException.validateFail("该任务单" + info.getReason()
                + "，禁止继续消耗资源，请在生产任务单列表执行终止操作（退料不受影响）");
    }

    /**
     * 冻结判定（三条取消通道同口径）：
     * 未关联销售单 → 不冻结；销售单已删除 → 冻结；销售单已作废/已冲抵 → 冻结；
     * 销售单已终止（全行终止）或所解析明细行 terminate_status=2 → 冻结（附行终止留痕）。
     */
    public FreezeInfo freezeInfo(BizProductionOrder order) {
        if (order == null || order.getSalesOrderId() == null) {
            return NOT_FROZEN;
        }
        BizSales sales = bizSalesMapper.selectById(order.getSalesOrderId());
        if (sales == null) {
            return new FreezeInfo(true, "关联的销售单已取消", null, null, null);
        }
        Integer bizStatus = sales.getBizStatus();
        if (bizStatus != null && bizStatus == BizSales.BIZ_STATUS_VOIDED) {
            return new FreezeInfo(true, "关联的销售单已作废", null, null, null);
        }
        if (bizStatus != null && bizStatus == BizSales.BIZ_STATUS_RED_FLUSH) {
            return new FreezeInfo(true, "关联的销售单已冲抵", null, null, null);
        }
        BizSalesDetail line = resolveLinkedLine(order);
        if (isLineTerminated(line)) {
            return new FreezeInfo(true, "关联的销售明细行已终止",
                    line.getTerminateReason(), line.getTerminateTime(), line.getTerminatorName());
        }
        if (bizStatus != null && bizStatus == BizSales.BIZ_STATUS_TERMINATED) {
            // 全单已终止但行解析不到（旧行已删等）：按行终止口径兜底冻结
            return new FreezeInfo(true, "关联的销售明细行已终止", null, null, null);
        }
        return NOT_FROZEN;
    }

    /**
     * 明细行终止判断（行已加载处直接调用）。
     */
    public boolean isLineTerminated(BizSalesDetail line) {
        return line != null && line.getTerminateStatus() != null
                && line.getTerminateStatus() == BizSalesDetail.TERMINATE_TERMINATED;
    }

    /**
     * 任务单关联销售明细行是否已终止（terminate_status=2）。未关联销售单/行已不存在 → false。
     */
    public boolean isLineTerminated(BizProductionOrder order) {
        return isLineTerminated(resolveLinkedLine(order));
    }

    /**
     * 行解析：salesDetailId 直查优先，NULL 时按 (salesOrderId, goodsId) 解析；未关联销售单返回 null。
     * 需求二开放给生产侧服务使用（终止回执需要行数量）。
     */
    public BizSalesDetail resolveLinkedLine(BizProductionOrder order) {
        if (order == null || order.getSalesOrderId() == null) {
            return null;
        }
        if (order.getSalesDetailId() != null) {
            return bizSalesDetailMapper.selectById(order.getSalesDetailId());
        }
        if (order.getGoodsId() == null) {
            return null;
        }
        return bizSalesDetailMapper.selectOne(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, order.getSalesOrderId())
                .eq(BizSalesDetail::getGoodsId, order.getGoodsId())
                .last("LIMIT 1"));
    }
}
