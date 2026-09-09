package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.dto.LoginResponse;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizProductionOrderStep;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizProductionOrderStepMapper;
import org.example.back.vo.ProductionStepVO;
import org.example.back.vo.QcStateVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产工序打卡（D64）：7 道人工装配工序的完成留痕。
 * 打卡不是流程闸口（质检仍是唯一质量闸口）；第 6/8/10 道由质检/入库实时推导，不落库。
 * 撤销限打卡本人或生产管理员；仅生产中/待入库状态可打卡与撤销。
 */
@Service
public class ProductionStepService {

    /** 人工装配工序序号（落库可打卡） */
    public static final List<Integer> MANUAL_STEP_NOS = List.of(1, 2, 3, 4, 5, 7, 9);

    /** 10 道生产工序定稿文案（D64，快照/初始化/展示共用；数组顺序即序号） */
    public static final String[] PROCESS_STEPS = {
        "磁性材料装配",                        // 1
        "底座结构组装",                        // 2
        "手柄机构装配",                        // 3
        "PCB板焊接及安装",                     // 4
        "程序烧录",                            // 5
        "首次测试",                            // 6（质检驱动）
        "屏蔽壳安装",                          // 7
        "成品测试",                            // 8（质检驱动）
        "发合格证、条码、标签、配件及包装",     // 9
        "成品入库"                             // 10（入库驱动）
    };

    @Autowired
    private BizProductionOrderStepMapper stepMapper;

    @Autowired
    private BizProductionOrderMapper orderMapper;

    @Autowired
    private QcService qcService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private AuthService authService;

    private void requireStepAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可操作工序打卡",
                AuthzService.DEPT_PRODUCTION
        );
    }

    /** 建单时初始化 7 道人工工序实例行（未完成态） */
    @Transactional(rollbackFor = Exception.class)
    public void initStepsForOrder(Long orderId) {
        for (int i = 0; i < PROCESS_STEPS.length; i++) {
            int stepNo = i + 1;
            if (!MANUAL_STEP_NOS.contains(stepNo)) {
                continue;
            }
            BizProductionOrderStep step = new BizProductionOrderStep();
            step.setOrderId(orderId);
            step.setStepNo(stepNo);
            step.setStepName(PROCESS_STEPS[i]);
            step.setStatus(BizProductionOrderStep.STATUS_UNDONE);
            stepMapper.insert(step);
        }
    }

    /** 打卡：人工工序标记完成（记录打卡人/时间） */
    @Transactional(rollbackFor = Exception.class)
    public void complete(Long orderId, int stepNo) {
        requireStepAccess();
        BizProductionOrder order = requireOrder(orderId);
        ensureOperableStatus(order);
        BizProductionOrderStep step = requireManualStep(orderId, stepNo);
        if (step.getStatus() != null && step.getStatus() == BizProductionOrderStep.STATUS_DONE) {
            throw BusinessException.validateFail("该工序已完成打卡，无需重复操作");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        step.setStatus(BizProductionOrderStep.STATUS_DONE);
        step.setOperatorId(user.getId());
        step.setOperatorName(user.getRealName());
        step.setOperateTime(LocalDateTime.now());
        stepMapper.updateById(step);
    }

    /** 撤销打卡：本人或生产管理员；状态回退并清空打卡人（update wrapper 显式置 null） */
    @Transactional(rollbackFor = Exception.class)
    public void revoke(Long orderId, int stepNo) {
        requireStepAccess();
        BizProductionOrder order = requireOrder(orderId);
        ensureOperableStatus(order);
        BizProductionOrderStep step = requireManualStep(orderId, stepNo);
        if (step.getStatus() == null || step.getStatus() != BizProductionOrderStep.STATUS_DONE) {
            throw BusinessException.validateFail("该工序未打卡，无需撤销");
        }
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        boolean self = user.getId() != null && user.getId().equals(step.getOperatorId());
        if (!self) {
            authzService.requireDeptAdminOrSuperAdmin(
                    AuthzService.DEPT_PRODUCTION,
                    "仅打卡本人或生产研发部管理员可撤销打卡"
            );
        }
        LambdaUpdateWrapper<BizProductionOrderStep> uw = new LambdaUpdateWrapper<>();
        uw.eq(BizProductionOrderStep::getId, step.getId())
                .set(BizProductionOrderStep::getStatus, BizProductionOrderStep.STATUS_UNDONE)
                .set(BizProductionOrderStep::getOperatorId, null)
                .set(BizProductionOrderStep::getOperatorName, null)
                .set(BizProductionOrderStep::getOperateTime, null);
        stepMapper.update(null, uw);
    }

    /**
     * 合并 10 道工序展示行：人工行读步骤实例，第 6/8 道由质检状态推导、第 10 道由订单状态推导。
     * 历史完结单（无实例）或已作废单返回 null，前端回落静态快照文字。
     */
    public List<ProductionStepVO> listSteps(BizProductionOrder order) {
        if (order.getStatus() != null && order.getStatus() == BizProductionOrder.STATUS_VOIDED) {
            return null;
        }
        List<BizProductionOrderStep> rows = listByOrder(order.getId());
        if (rows.isEmpty()) {
            return null;
        }
        Map<Integer, BizProductionOrderStep> byNo = rows.stream()
                .collect(Collectors.toMap(BizProductionOrderStep::getStepNo, Function.identity()));
        boolean operableStatus = order.getStatus() != null
                && (order.getStatus() == BizProductionOrder.STATUS_IN_PROGRESS
                    || order.getStatus() == BizProductionOrder.STATUS_AWAIT_QC);

        QcStateVO qc = qcService.buildState(order);

        List<ProductionStepVO> result = new ArrayList<>(PROCESS_STEPS.length);
        for (int i = 0; i < PROCESS_STEPS.length; i++) {
            int stepNo = i + 1;
            ProductionStepVO vo = new ProductionStepVO();
            vo.setStepNo(stepNo);
            vo.setStepName(PROCESS_STEPS[i]);
            if (MANUAL_STEP_NOS.contains(stepNo)) {
                applyManualLine(vo, byNo.get(stepNo), operableStatus);
            } else if (stepNo == 6) {
                applyQcLine(vo, qc.getFirstStatus());
            } else if (stepNo == 8) {
                applyQcLine(vo, qc.getFinalStatus());
            } else {
                applyReceiptLine(vo, order.getStatus());
            }
            result.add(vo);
        }
        return result;
    }

    private void applyManualLine(ProductionStepVO vo, BizProductionOrderStep row, boolean operableStatus) {
        vo.setType("manual");
        boolean done = row != null && row.getStatus() != null
                && row.getStatus() == BizProductionOrderStep.STATUS_DONE;
        vo.setDone(done);
        if (done) {
            vo.setStatusText("已完成");
            vo.setTagType("success");
            vo.setOperatorName(row.getOperatorName());
            vo.setOperateTime(row.getOperateTime());
        } else {
            vo.setStatusText("未完成");
            vo.setTagType("info");
        }
        vo.setOperable(operableStatus && !done);
        vo.setRevocable(operableStatus && done);
    }

    private void applyQcLine(ProductionStepVO vo, String status) {
        vo.setType("qc");
        if (status == null || "untested".equals(status)) {
            vo.setDone(false);
            vo.setStatusText("未测");
            vo.setTagType("info");
        } else if ("ok".equals(status)) {
            vo.setDone(true);
            vo.setStatusText("已完成（合格）");
            vo.setTagType("success");
        } else if ("ng".equals(status)) {
            vo.setDone(false);
            vo.setStatusText("NG-待处置");
            vo.setTagType("danger");
        } else if ("rework".equals(status)) {
            vo.setDone(false);
            vo.setStatusText("返工-待重测");
            vo.setTagType("warning");
        } else {
            vo.setDone(false);
            vo.setStatusText("已报废");
            vo.setTagType("danger");
        }
        vo.setOperable(false);
        vo.setRevocable(false);
    }

    private void applyReceiptLine(ProductionStepVO vo, Integer status) {
        vo.setType("receipt");
        if (status != null && status == BizProductionOrder.STATUS_DONE) {
            vo.setDone(true);
            vo.setStatusText("已完成（已入库）");
            vo.setTagType("success");
        } else if (status != null && status == BizProductionOrder.STATUS_AWAIT_QC) {
            vo.setDone(false);
            vo.setStatusText("待入库（质检合格）");
            vo.setTagType("warning");
        } else if (status != null && status == BizProductionOrder.STATUS_SCRAPPED) {
            vo.setDone(false);
            vo.setStatusText("已报废");
            vo.setTagType("danger");
        } else {
            vo.setDone(false);
            vo.setStatusText("未完成");
            vo.setTagType("info");
        }
        vo.setOperable(false);
        vo.setRevocable(false);
    }

    // ============================== 私有：校验与工具 ==============================

    private BizProductionOrder requireOrder(Long id) {
        BizProductionOrder order = orderMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        return order;
    }

    private void ensureOperableStatus(BizProductionOrder order) {
        int st = order.getStatus() == null ? 0 : order.getStatus();
        if (st == BizProductionOrder.STATUS_IN_PROGRESS || st == BizProductionOrder.STATUS_AWAIT_QC) {
            return;
        }
        if (st == BizProductionOrder.STATUS_PENDING) {
            throw BusinessException.validateFail("订单尚未开工，暂不能工序打卡");
        }
        if (st == BizProductionOrder.STATUS_DONE) {
            throw BusinessException.validateFail("订单已完成，工序已固化，不能再打卡/撤销");
        }
        if (st == BizProductionOrder.STATUS_VOIDED) {
            throw BusinessException.validateFail("订单已作废，不能再打卡/撤销");
        }
        if (st == BizProductionOrder.STATUS_SCRAPPED) {
            throw BusinessException.validateFail("订单已报废，不能再打卡/撤销");
        }
        throw BusinessException.validateFail("当前状态不能工序打卡");
    }

    private BizProductionOrderStep requireManualStep(Long orderId, int stepNo) {
        if (!MANUAL_STEP_NOS.contains(stepNo)) {
            throw BusinessException.validateFail("该工序由质检记录/生产入库自动更新，不支持打卡");
        }
        LambdaQueryWrapper<BizProductionOrderStep> w = new LambdaQueryWrapper<>();
        w.eq(BizProductionOrderStep::getOrderId, orderId)
                .eq(BizProductionOrderStep::getStepNo, stepNo);
        BizProductionOrderStep step = stepMapper.selectOne(w);
        if (step == null) {
            throw BusinessException.validateFail("工序记录不存在（历史单未初始化工序实例）");
        }
        return step;
    }

    private List<BizProductionOrderStep> listByOrder(Long orderId) {
        LambdaQueryWrapper<BizProductionOrderStep> w = new LambdaQueryWrapper<>();
        w.eq(BizProductionOrderStep::getOrderId, orderId)
                .orderByAsc(BizProductionOrderStep::getStepNo);
        return stepMapper.selectList(w);
    }
}
