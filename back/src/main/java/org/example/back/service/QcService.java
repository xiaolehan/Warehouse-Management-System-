package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.QcDisposeDTO;
import org.example.back.dto.QcSaveDTO;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionQc;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizProductionQcMapper;
import org.example.back.vo.QcStateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 生产质检（D40）：首测/成品测两个测点，追加式记录。
 * 最新一条 OK → 测点通过；NG → 处置（返工 REWORK 后重测 / 报废 SCRAP）；
 * 两测点全部最新 OK 才允许生产入库。所有操作归生产研发部。
 */
@Service
public class QcService {

    @Autowired
    private BizProductionQcMapper qcMapper;

    @Autowired
    private BizProductionOrderMapper orderMapper;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private AuthService authService;

    @Autowired
    private MessageService messageService;

    // D107/review：@Lazy 打破与 ProductionOrderService 的字段注入循环（后者注入本服务做质检校验）
    @Autowired
    @Lazy
    private ProductionOrderService productionOrderService;

    private void requireQcAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可执行质检",
                AuthzService.DEPT_PRODUCTION
        );
    }

    /**
     * 录入一条测试结果。
     */
    @Transactional(rollbackFor = Exception.class)
    public void record(QcSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireQcAccess();
        BizProductionOrder order = requireOrder(dto.getOrderId());
        ensureTestable(order);
        String point = normalizePoint(dto.getTestPoint());
        String result = normalizeResult(dto.getResult());
        if (BizProductionQc.RESULT_NG.equals(result) && !hasText(dto.getReason())) {
            throw BusinessException.validateFail("NG 时必须填写不合格原因");
        }

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizProductionQc qc = new BizProductionQc();
        qc.setOrderId(order.getId());
        qc.setGoodsId(order.getGoodsId());
        qc.setGoodsName(order.getGoodsName());
        qc.setTestPoint(point);
        qc.setTesterId(user.getId());
        qc.setTesterName(user.getRealName());
        qc.setResult(result);
        qc.setReason(dto.getReason());
        qcMapper.insert(qc);

        // 两条测点全部最新 OK → 订单进入待入库（质检通过）
        QcStateVO state = buildState(order);
        if (Boolean.TRUE.equals(state.getPassed())) {
            updateOrderStatus(order, BizProductionOrder.STATUS_AWAIT_QC);
        }
    }

    /**
     * 处置一条 NG：返工后需重测；报废直接终结订单。
     */
    @Transactional(rollbackFor = Exception.class)
    public void dispose(QcDisposeDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireQcAccess();
        BizProductionOrder order = requireOrder(dto.getOrderId());
        ensureTestable(order);
        String point = normalizePoint(dto.getTestPoint());
        String disp = normalizeDispose(dto.getDisposition());

        BizProductionQc latestNg = latestNgOfPoint(order.getId(), point);
        if (latestNg == null) {
            throw BusinessException.validateFail("该测点没有待处置的不合格记录");
        }

        if (BizProductionQc.DISP_REWORK.equals(disp)) {
            setDisposition(latestNg, BizProductionQc.DISP_REWORK);
            // 返工：退回生产中等待重测
            updateOrderStatus(order, BizProductionOrder.STATUS_IN_PROGRESS);
            // D107/review：离开待入库态——自动关闭可能已提交的入库申请，防仓储确认一张返工单
            productionOrderService.closePendingInboundApplication(
                    order.getId(), "质检处置返工，入库申请自动关闭");
        } else {
            setDisposition(latestNg, BizProductionQc.DISP_SCRAP);
            // 报废：终结订单，撤销未读通知
            messageService.revokeUnreadByBiz("production_order", order.getId());
            updateOrderStatus(order, BizProductionOrder.STATUS_SCRAPPED);
            // D107/review：报废——自动关闭待确认入库申请（置系统驳回+撤仓储待办+回执生产）
            productionOrderService.closePendingInboundApplication(
                    order.getId(), "质检处置报废，入库申请自动关闭");
        }
    }

    /**
     * 生产入库前置校验：两条测点必须最新均为 OK。
     */
    public void ensurePassedForReceipt(Long orderId) {
        requireQcAccess();
        BizProductionOrder order = requireOrder(orderId);
        QcStateVO state = buildState(order);
        if (Boolean.TRUE.equals(state.getScrapped())) {
            throw BusinessException.validateFail("该订单已报废，无法生产入库");
        }
        if (!Boolean.TRUE.equals(state.getPassed())) {
            throw BusinessException.validateFail("首测与成品测均合格后，才允许生产入库");
        }
    }

    public QcStateVO snapshot(Long orderId) {
        requireQcAccess();
        BizProductionOrder order = requireOrder(orderId);
        return buildState(order);
    }

    // ============================== 状态推导 ==============================

    public QcStateVO buildState(BizProductionOrder order) {
        return buildStateFor(order, listByOrder(order.getId()));
    }

    /**
     * 批量构建多张任务单的质检状态（D64 质检进度列表修复）：
     * 一次 in 查询取回全部记录按单分组推导，避免列表页逐行 N 次查询。
     */
    public Map<Long, QcStateVO> buildStateBatch(List<BizProductionOrder> orders) {
        if (orders == null || orders.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizProductionQc> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BizProductionQc::getOrderId, orders.stream().map(BizProductionOrder::getId).toList())
                .orderByAsc(BizProductionQc::getId);
        List<BizProductionQc> all = qcMapper.selectList(wrapper);
        Map<Long, List<BizProductionQc>> byOrder = all.stream()
                .collect(Collectors.groupingBy(BizProductionQc::getOrderId));
        Map<Long, QcStateVO> result = new HashMap<>();
        for (BizProductionOrder order : orders) {
            result.put(order.getId(), buildStateFor(order, byOrder.getOrDefault(order.getId(), List.of())));
        }
        return result;
    }

    private QcStateVO buildStateFor(BizProductionOrder order, List<BizProductionQc> records) {
        PointState first = latestOfPoint(records, BizProductionQc.POINT_FIRST);
        PointState last = latestOfPoint(records, BizProductionQc.POINT_FINAL);

        boolean scrapped = order.getStatus() != null && order.getStatus() == BizProductionOrder.STATUS_SCRAPPED;
        boolean passed = !scrapped && first.isPassed() && last.isPassed();

        QcStateVO vo = new QcStateVO();
        vo.setOrderId(order.getId());
        vo.setScrapped(scrapped);
        vo.setPassed(passed);
        vo.setFirstStatus(first.status);
        vo.setFirstStatusText(first.statusText);
        vo.setFirstPassed(first.isPassed());
        vo.setFinalStatus(last.status);
        vo.setFinalStatusText(last.statusText);
        vo.setFinalPassed(last.isPassed());

        List<QcStateVO.QcRecordVO> vos = new ArrayList<>(records.size());
        for (BizProductionQc r : records) {
            QcStateVO.QcRecordVO rr = new QcStateVO.QcRecordVO();
            rr.setId(r.getId());
            rr.setTestPoint(r.getTestPoint());
            rr.setTestPointText(pointText(r.getTestPoint()));
            rr.setTesterName(r.getTesterName());
            rr.setResult(r.getResult());
            rr.setReason(r.getReason());
            rr.setDisposition(r.getDisposition());
            rr.setCreateTime(r.getCreateTime());
            vos.add(rr);
        }
        vo.setRecords(vos);
        return vo;
    }

    private static class PointState {
        String status;
        String statusText;
        boolean passed;

        boolean isPassed() {
            return passed;
        }
    }

    private PointState latestOfPoint(List<BizProductionQc> records, String point) {
        PointState ps = new PointState();
        ps.passed = false;
        // records 按 create_time 升序追加，最后一个命中的即该测点最新
        BizProductionQc latest = null;
        for (BizProductionQc r : records) {
            if (point.equals(r.getTestPoint())) {
                latest = r;
            }
        }
        if (latest == null) {
            ps.status = "untested";
            ps.statusText = "未测";
            return ps;
        }
        if (BizProductionQc.RESULT_OK.equals(latest.getResult())) {
            ps.passed = true;
            ps.status = "ok";
            ps.statusText = "合格";
        } else {
            String disp = latest.getDisposition();
            if (disp == null) {
                ps.status = "ng";
                ps.statusText = "NG-待处置";
            } else if (BizProductionQc.DISP_REWORK.equals(disp)) {
                ps.status = "rework";
                ps.statusText = "返工-待重测";
            } else {
                ps.status = "scrap";
                ps.statusText = "已报废";
            }
        }
        return ps;
    }

    private List<BizProductionQc> listByOrder(Long orderId) {
        LambdaQueryWrapper<BizProductionQc> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizProductionQc::getOrderId, orderId)
                .orderByAsc(BizProductionQc::getId);
        return qcMapper.selectList(wrapper);
    }

    /**
     * D114：报废任务单的终态原因取最近一条 NG 记录的不合格原因（报废处置无独立原因输入）。
     */
    public String latestNgReason(Long orderId) {
        BizProductionQc latestNg = null;
        for (BizProductionQc r : listByOrder(orderId)) {
            if (BizProductionQc.RESULT_NG.equals(r.getResult())) {
                latestNg = r; // records 升序，最后一个命中的即最新
            }
        }
        return latestNg == null ? null : latestNg.getReason();
    }

    private BizProductionQc latestNgOfPoint(Long orderId, String point) {
        List<BizProductionQc> records = listByOrder(orderId);
        BizProductionQc latest = null;
        for (BizProductionQc r : records) {
            if (point.equals(r.getTestPoint())) {
                latest = r;
            }
        }
        if (latest != null && BizProductionQc.RESULT_NG.equals(latest.getResult())
                && latest.getDisposition() == null) {
            return latest;
        }
        return null;
    }

    private void setDisposition(BizProductionQc ng, String disp) {
        LambdaUpdateWrapper<BizProductionQc> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizProductionQc::getId, ng.getId())
                .isNull(BizProductionQc::getDisposition)
                .set(BizProductionQc::getDisposition, disp);
        qcMapper.update(null, uw);
    }

    private void updateOrderStatus(BizProductionOrder order, int status) {
        if (order.getStatus() != null && order.getStatus() == status) {
            return;
        }
        LambdaUpdateWrapper<BizProductionOrder> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizProductionOrder::getId, order.getId())
                .set(BizProductionOrder::getStatus, status);
        orderMapper.update(null, uw);
        order.setStatus(status);
    }

    private void ensureTestable(BizProductionOrder order) {
        int st = order.getStatus() == null ? 0 : order.getStatus();
        // 生产中或待入库（质检中）可录测试/处置；已作废/已报废/已完成/待生产 不可
        if (st == BizProductionOrder.STATUS_IN_PROGRESS
                || st == BizProductionOrder.STATUS_AWAIT_QC) {
            return;
        }
        if (st == BizProductionOrder.STATUS_SCRAPPED) {
            throw BusinessException.validateFail("该订单已报废，无法质检");
        }
        if (st == BizProductionOrder.STATUS_VOIDED) {
            throw BusinessException.validateFail("该订单已作废，无法质检");
        }
        if (st == BizProductionOrder.STATUS_TERMINATED) {
            throw BusinessException.validateFail("该订单已终止，无法质检");
        }
        if (st == BizProductionOrder.STATUS_DONE) {
            throw BusinessException.validateFail("该订单已完成，无需质检");
        }
        throw BusinessException.validateFail("当前状态无法进行质检");
    }

    private BizProductionOrder requireOrder(Long id) {
        BizProductionOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        return order;
    }

    private String normalizePoint(String point) {
        if (point == null) {
            return null;
        }
        String p = point.trim().toLowerCase(Locale.ROOT);
        if (BizProductionQc.POINT_FIRST.equals(p)) {
            return BizProductionQc.POINT_FIRST;
        }
        if (BizProductionQc.POINT_FINAL.equals(p)) {
            return BizProductionQc.POINT_FINAL;
        }
        throw BusinessException.validateFail("测点必须是 first(首测) 或 final(成品测)");
    }

    private String normalizeResult(String result) {
        if (result == null) {
            throw BusinessException.validateFail("测试结果不能为空");
        }
        String r = result.trim().toUpperCase(Locale.ROOT);
        if (BizProductionQc.RESULT_OK.equals(r) || BizProductionQc.RESULT_NG.equals(r)) {
            return r;
        }
        throw BusinessException.validateFail("测试结果必须是 OK 或 NG");
    }

    private String normalizeDispose(String disp) {
        if (disp == null) {
            throw BusinessException.validateFail("处置方式不能为空");
        }
        String d = disp.trim().toUpperCase(Locale.ROOT);
        if (BizProductionQc.DISP_REWORK.equals(d) || BizProductionQc.DISP_SCRAP.equals(d)) {
            return d;
        }
        throw BusinessException.validateFail("处置方式必须是 REWORK 或 SCRAP");
    }

    private boolean hasText(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private String pointText(String point) {
        return BizProductionQc.POINT_FIRST.equals(point) ? "首测" : "成品测";
    }
}