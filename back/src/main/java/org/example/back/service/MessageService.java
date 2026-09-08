package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.MessageQueryDTO;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysMessage;
import org.example.back.entity.SysUser;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysMessageMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.MessageVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

@Service
public class MessageService {

    private static final int MESSAGE_UNREAD = 0;
    private static final int MESSAGE_READ = 1;
    private static final int USER_STATUS_ENABLED = 1;
    private static final String ROLE_ADMIN = "admin";
    private static final DateTimeFormatter MESSAGE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    @Autowired
    private SysMessageMapper sysMessageMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysDeptMapper sysDeptMapper;

    @Autowired
    private AuthzService authzService;

    public PageResult<MessageVO> page(MessageQueryDTO queryDTO) {
        requireMessageAccess();
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getRecipientUserId, currentUser.getId())
                .eq(queryDTO.getRead() != null, SysMessage::getIsRead, Boolean.TRUE.equals(queryDTO.getRead()) ? MESSAGE_READ : MESSAGE_UNREAD)
                .orderByDesc(SysMessage::getCreateTime)
                .orderByDesc(SysMessage::getId);

        Page<SysMessage> page = sysMessageMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<MessageVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public Long unreadCount() {
        requireMessageAccess();
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getRecipientUserId, currentUser.getId())
                .eq(SysMessage::getIsRead, MESSAGE_UNREAD);
        return sysMessageMapper.selectCount(wrapper);
    }

    public void markRead(Long id) {
        requireMessageAccess();
        SysMessage message = requireOwnedMessage(id);
        if (Integer.valueOf(MESSAGE_READ).equals(message.getIsRead())) {
            return;
        }
        message.setIsRead(MESSAGE_READ);
        message.setReadTime(LocalDateTime.now());
        sysMessageMapper.updateById(message);
    }

    public void markAllRead() {
        requireMessageAccess();
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getRecipientUserId, currentUser.getId())
                .eq(SysMessage::getIsRead, MESSAGE_UNREAD);

        SysMessage updateEntity = new SysMessage();
        updateEntity.setIsRead(MESSAGE_READ);
        updateEntity.setReadTime(LocalDateTime.now());
        sysMessageMapper.update(updateEntity, wrapper);
    }

    public void deleteAllRead() {
        requireMessageAccess();
        LoginResponse.UserInfoVO currentUser = authzService.currentUser();
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getRecipientUserId, currentUser.getId())
                .eq(SysMessage::getIsRead, MESSAGE_READ);
        sysMessageMapper.delete(wrapper);
    }

    public void sendNewEmployeePasswordReminder(String employeeName, Long deptId, String operatorLabel) {
        sendToDeptAdmins(
                deptId,
                "新员工密码设置提醒",
                String.format(
                        Locale.ROOT,
                        "您有新员工%s加入，需要设置新密码（若不设置，将使用默认密码123456），操作人：%s。",
                        safeEmployeeName(employeeName),
                        safeOperatorLabel(operatorLabel)
                )
        );
    }

    public void sendEmployeePasswordChangedReminder(String employeeName, Long deptId, String operatorLabel) {
        sendToDeptAdmins(
                deptId,
                "员工密码变更提醒",
                String.format(
                        Locale.ROOT,
                        "您的员工%s的密码已被修改，如有疑问，请联系操作人，操作人：%s。",
                        safeEmployeeName(employeeName),
                        safeOperatorLabel(operatorLabel)
                )
        );
    }

    public void sendEmployeeTransferReminders(String employeeName, Long oldDeptId, Long newDeptId, String operatorLabel) {
        if (oldDeptId == null || newDeptId == null || oldDeptId.equals(newDeptId)) {
            return;
        }
        SysDept oldDept = sysDeptMapper.selectById(oldDeptId);
        SysDept newDept = sysDeptMapper.selectById(newDeptId);
        if (oldDept != null && newDept != null) {
            sendToDeptAdmins(
                    oldDeptId,
                    "员工调离提醒",
                    String.format(
                            Locale.ROOT,
                            "您的员工%s已调离并将前往%s，操作人：%s。",
                            safeEmployeeName(employeeName),
                            newDept.getDeptName(),
                            safeOperatorLabel(operatorLabel)
                    )
            );
            sendToDeptAdmins(
                    newDeptId,
                    "员工调入提醒",
                    String.format(
                            Locale.ROOT,
                            "您已新增从%s调入的员工%s，操作人：%s。",
                            oldDept.getDeptName(),
                            safeEmployeeName(employeeName),
                            safeOperatorLabel(operatorLabel)
                    )
            );
        }
    }

    public void sendEmployeeLeftReminder(String employeeName, Long deptId, String operatorLabel) {
        sendToDeptAdmins(
                deptId,
                "员工离职提醒",
                String.format(
                        Locale.ROOT,
                        "您的员工%s已离职，请及时处理相关事宜，操作人：%s。",
                        safeEmployeeName(employeeName),
                        safeOperatorLabel(operatorLabel)
                )
        );
    }

    public void sendEmployeeDisabledReminder(String employeeName, Long deptId, String operatorLabel) {
        sendToDeptAdmins(
                deptId,
                "员工账号禁用提醒",
                String.format(
                        Locale.ROOT,
                        "您的员工%s已被禁用系统账户，请及时处理相关事宜，操作人：%s。",
                        safeEmployeeName(employeeName),
                        safeOperatorLabel(operatorLabel)
                )
        );
    }

    public void sendEmployeeDeletedReminder(String employeeName, Long deptId, String operatorLabel) {
        sendToDeptAdmins(
                deptId,
                "员工账号删除提醒",
                String.format(
                        Locale.ROOT,
                        "您的员工%s已被删除系统账户，请及时处理相关事宜，操作人：%s。",
                        safeEmployeeName(employeeName),
                        safeOperatorLabel(operatorLabel)
                )
        );
    }

    public void sendWorkRequirementOverdueToEmployee(Long userId, String requirementContent, LocalDateTime endTime) {
        sendToUser(
                userId,
                "工作要求已超时",
                String.format(
                        Locale.ROOT,
                        "你的工作要求“%s”已超过截止时间%s，请尽快处理并提交执行结果。",
                        safeRequirementSummary(requirementContent),
                        formatMessageTime(endTime)
                )
        );
    }

    public void sendWorkRequirementOverdueToDeptAdmins(Long deptId, String employeeName, String requirementContent, LocalDateTime endTime) {
        sendToDeptAdmins(
                deptId,
                "员工工作要求超时提醒",
                String.format(
                        Locale.ROOT,
                        "员工%s的工作要求“%s”已超时，截止时间%s，请及时关注处理进度。",
                        safeEmployeeName(employeeName),
                        safeRequirementSummary(requirementContent),
                        formatMessageTime(endTime)
                )
        );
    }

    /**
     * 销售下单后通知仓储管理员有待确认出库的销售单。
     */
    public void sendSalesPendingConfirmToWarehouseAdmins(String salesNo, String customerName, String applicantName, Long salesId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String customer = StringUtils.hasText(customerName) ? customerName : "未填写";
        String applicant = StringUtils.hasText(applicantName) ? applicantName : "销售员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认销售出库",
                String.format(
                        Locale.ROOT,
                        "销售单 %s 已由 %s 下单（客户：%s），请尽快确认出库。",
                        salesNo, applicant, customer
                ),
                "sales",
                salesId
        );
    }

    /**
     * 销售退货建单后通知仓储管理员有待确认入库的退货单。
     */
    public void sendSalesReturnPendingConfirmToWarehouseAdmins(String returnNo, String applicantName, Long returnId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String applicant = StringUtils.hasText(applicantName) ? applicantName : "销售员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认销售退货入库",
                String.format(
                        Locale.ROOT,
                        "销售退货单 %s 已由 %s 提交，请尽快确认入库。",
                        returnNo, applicant
                ),
                "sales_return",
                returnId
        );
    }

    /**
     * 按业务单据撤销未读待办消息（单据被删除/作废/已处理时调用，避免悬挂通知）。
     */
    public void revokeUnreadByBiz(String bizType, Long bizId) {
        if (!StringUtils.hasText(bizType) || bizId == null) {
            return;
        }
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getBizType, bizType)
                .eq(SysMessage::getBizId, bizId)
                .eq(SysMessage::getIsRead, MESSAGE_UNREAD);
        sysMessageMapper.delete(wrapper);
    }

    /**
     * 按收件部门+业务单据撤销未读待办消息（单据终态时，仅清除指定部门已不可操作的待办，
     * 保留发给其他部门（如生产/销售）的反馈通知）。用于驳回场景：既清掉仓储"待出库"，
     * 又不误删刚发送给申请方的"驳回/失败"通知。
     */
    public void revokeUnreadByBizAndDeptCode(String bizType, Long bizId, String deptCode) {
        Long deptId = resolveDeptIdByCode(deptCode);
        if (!StringUtils.hasText(bizType) || bizId == null || deptId == null) {
            return;
        }
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getBizType, bizType)
                .eq(SysMessage::getBizId, bizId)
                .eq(SysMessage::getRecipientDeptId, deptId)
                .eq(SysMessage::getIsRead, MESSAGE_UNREAD);
        sysMessageMapper.delete(wrapper);
    }
    public boolean hasUnreadBizMessage(String bizType, Long bizId) {
        if (!StringUtils.hasText(bizType) || bizId == null) {
            return false;
        }
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getBizType, bizType)
                .eq(SysMessage::getBizId, bizId)
                .eq(SysMessage::getIsRead, MESSAGE_UNREAD);
        return sysMessageMapper.selectCount(wrapper) > 0;
    }

    /**
     * 生产领料失败（驳回/缺料）反馈销售管理员（绑 biz_type=pick_list，对齐 D21 范式）。
     * REQUIRES_NEW：发料缺料场景下领料事务将回滚，反馈消息需独立提交以免丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickListFailureToSalesAdmins(String pickNo, String reason, Long pickListId) {
        Long salesDeptId = resolveDeptIdByCode(AuthzService.DEPT_SALES);
        if (salesDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                salesDeptId,
                "生产领料失败反馈",
                String.format(Locale.ROOT, "领料单 %s 反馈失败：%s。请关注相关订单履约。", pickNo, reason),
                "pick_list",
                pickListId
        );
    }

    /**
     * 仓储确认出库成功 → 通知生产端已可开工（绑 biz_type=pick_list，对齐 D21 范式）。
     */
    public void sendPickIssuedToProductionAdmins(String pickNo, String orderNo, Long pickId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "生产领料已出库",
                String.format(Locale.ROOT,
                        "领料单 %s（生产任务单 %s）已由仓储确认出库，现可开工。",
                        pickNo, orderNo == null ? "-" : orderNo),
                "pick_list",
                pickId);
    }

    /**
     * D62：生产单物料齐套（补料入库确认后缺口清零）→ 通知生产部管理员可申请领料。
     * 绑 biz_type=production_order；作废/报废单由调用方守卫不发，作废时随 D60 预警一并撤回未读。
     */
    public void sendKitCompleteToProductionAdmins(String orderNo, String goodsName, Integer quantity, String requestNo, Long orderId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "物料已齐套可领料",
                String.format(Locale.ROOT,
                        "生产任务单 %s（成品 %s×%d）所需物料已全部入库齐套（补料单 %s 已入库），请前往生产任务单详情申请领料。",
                        orderNo == null ? "-" : orderNo,
                        goodsName == null ? "-" : goodsName,
                        quantity == null ? 0 : quantity,
                        requestNo == null ? "-" : requestNo),
                "production_order",
                orderId);
    }

    /**
     * 生产领料出库失败(缺料/驳回) → 通知生产端（绑 biz_type=pick_list，对齐 D21 范式）。
     * REQUIRES_NEW：发料缺料场景下领料事务将回滚，反馈消息需独立提交以免丢失。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendPickIssueFailedToProductionAdmins(String pickNo, String reason, Long pickId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "生产领料出库失败",
                String.format(Locale.ROOT, "领料单 %s 出库失败：%s", pickNo, reason),
                "pick_list",
                pickId);
    }

    /**
     * 仓储创建采购申请单后通知采购管理员有待处理的采购申请。
     */
    public void sendPurchaseRequestToPurchaseAdmins(String requestNo, String applicantName, Long requestId) {
        Long purchaseDeptId = resolveDeptIdByCode(AuthzService.DEPT_PURCHASE);
        if (purchaseDeptId == null) {
            return;
        }
        String applicant = StringUtils.hasText(applicantName) ? applicantName : "仓储管理员";
        sendToDeptAdminsWithBiz(
                purchaseDeptId,
                "待处理采购申请",
                String.format(
                        Locale.ROOT,
                        "采购申请单 %s 已由 %s 提交，请尽快认领处理。",
                        requestNo, applicant
                ),
                "purchase_request",
                requestId
        );
    }

    /**
     * 采购到货后通知仓储管理员有待确认的采购入库。
     */
    public void sendPurchaseRequestArrivedToWarehouseAdmins(String requestNo, String operatorName, Long requestId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认采购入库",
                String.format(
                        Locale.ROOT,
                        "采购申请单 %s 已由 %s 到货，请尽快确认入库。",
                        requestNo, operator
                ),
                "purchase_request",
                requestId
        );
    }

    /**
     * D61 采购认领后向来源申请人推送行级到货摘要（生产补料→生产部管理员，仓储建单→仓储部管理员）。
     * biz 绑定 purchase_request/requestId：认领时 process() 先 revoke 旧待处理消息再发本条，
     * 后续终态（入库/驳回/撤销）由既有 revokeUnreadByBiz 调用点统一回收未读。
     */
    public void sendPurchaseRequestClaimedToSourceApplicant(String requestNo, String operatorName,
                                                            String sourceType, String arrivalSummary, Long requestId) {
        String deptCode = PurchaseRequestService.SOURCE_PRODUCTION.equals(sourceType)
                ? AuthzService.DEPT_PRODUCTION : AuthzService.DEPT_WAREHOUSE;
        Long deptId = resolveDeptIdByCode(deptCode);
        if (deptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                deptId,
                "采购申请单已认领",
                String.format(Locale.ROOT,
                        "采购申请单 %s 已由 %s 认领，预计到货：%s",
                        requestNo, operator, arrivalSummary == null ? "-" : arrivalSummary),
                "purchase_request",
                requestId
        );
    }

    /**
     * D42 生产任务单齐套预警：确认任务单时若发现物料缺口，通知采购管理员采购补料。
     */
    public void sendKitShortageToPurchaseAdmins(String orderNo, String goodsName, String shortageSummary, Long orderId) {
        Long purchaseDeptId = resolveDeptIdByCode(AuthzService.DEPT_PURCHASE);
        if (purchaseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                purchaseDeptId,
                "生产齐套预警-待采购",
                String.format(
                        Locale.ROOT,
                        "生产任务单 %s（成品：%s）存在物料缺口，请采购补料。缺口明细：%s",
                        orderNo == null ? "-" : orderNo,
                        goodsName == null ? "-" : goodsName,
                        shortageSummary == null ? "-" : shortageSummary
                ),
                "production_order",
                orderId
        );
    }

    /**
     * 采购到货后通知仓储管理员有待确认的进货入库。
     */
    public void sendPurchaseArrivedToWarehouseAdmins(String purchaseNo, String operatorName, Long purchaseId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认进货入库",
                String.format(
                        Locale.ROOT,
                        "进货单 %s 已由 %s 到货，请尽快确认入库。",
                        purchaseNo, operator
                ),
                "purchase",
                purchaseId
        );
    }

    /**
     * 采购发起商品退货后通知仓储管理员有待确认的退货出库。
     */
    public void sendPurchaseReturnPendingConfirmToWarehouseAdmins(String returnNo, String operatorName, Long returnId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认商品退货出库",
                String.format(
                        Locale.ROOT,
                        "商品退货单 %s 已由 %s 发起，请尽快确认出库。",
                        returnNo, operator
                ),
                "purchase_return",
                returnId
        );
    }

    /**
     * 生产端提交退料申请 → 通知仓储管理员确认回流入库。
     */
    public void sendPickReturnPendingToWarehouseAdmins(String pickNo, String orderNo, Long pickId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认生产退料入库",
                String.format(Locale.ROOT,
                        "退料单 %s（生产任务单 %s）已由生产端提交，请确认入库。",
                        pickNo, orderNo),
                "pick_list",
                pickId);
    }

    /**
     * 生产端提交领料申请 → 通知仓储管理员确认出库。
     */
    public void sendPickPendingToWarehouseAdmins(String pickNo, String orderNo, Long pickId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认生产领料出库",
                String.format(Locale.ROOT,
                        "领料单 %s（生产任务单 %s）已由生产端提交，请确认出库。",
                        pickNo, orderNo),
                "pick_list",
                pickId);
    }

    /**
     * 销售价偏离标准售价超阈值时通知超级管理员审批（绑 biz_type=sales，对齐 D21 范式）。
     * 超管 dept_id 为空，不能走部门广播，按 role=salesadmin 单点投递。
     */
    public void sendPriceDeviationToSuperAdmin(String salesNo, String operatorName, java.math.BigDecimal deviationPct, Long salesId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getRole, AuthzService.ROLE_SUPERADMIN)
                .eq(SysUser::getStatus, USER_STATUS_ENABLED)
                .last("LIMIT 1");
        SysUser superadmin = sysUserMapper.selectOne(wrapper);
        if (superadmin == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "销售管理员";
        long pct = deviationPct == null ? 0L : deviationPct.multiply(java.math.BigDecimal.valueOf(100))
                .setScale(0, java.math.RoundingMode.HALF_UP).longValue();
        SysMessage message = new SysMessage();
        message.setRecipientUserId(superadmin.getId());
        message.setRecipientDeptId(superadmin.getDeptId());
        message.setTitle("待审批价格偏离销售单");
        message.setContent(String.format(Locale.ROOT,
                "销售单 %s 由 %s 提交，销售价偏离标准售价 %d%%，超 5%% 阈值，请审批后仓储方可确认出库。",
                salesNo, operator, pct));
        message.setIsRead(MESSAGE_UNREAD);
        message.setBizType("sales");
        message.setBizId(salesId);
        sysMessageMapper.insert(message);
    }

    private Long resolveDeptIdByCode(String deptCode) {
        if (!StringUtils.hasText(deptCode)) {
            return null;
        }
        LambdaQueryWrapper<SysDept> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysDept::getDeptCode, deptCode).last("LIMIT 1");
        SysDept dept = sysDeptMapper.selectOne(wrapper);
        return dept == null ? null : dept.getId();
    }

    private void sendToDeptAdmins(Long deptId, String title, String content) {
        sendToDeptAdminsWithBiz(deptId, title, content, null, null);
    }

    private void sendToDeptAdminsWithBiz(Long deptId, String title, String content, String bizType, Long bizId) {
        if (deptId == null || !StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            return;
        }

        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getRole, ROLE_ADMIN)
                .eq(SysUser::getDeptId, deptId)
                .eq(SysUser::getStatus, USER_STATUS_ENABLED)
                .orderByAsc(SysUser::getId);

        List<SysUser> recipients = sysUserMapper.selectList(wrapper);
        for (SysUser recipient : recipients) {
            SysMessage message = new SysMessage();
            message.setRecipientUserId(recipient.getId());
            message.setRecipientDeptId(deptId);
            message.setTitle(title.trim());
            message.setContent(content.trim());
            message.setIsRead(MESSAGE_UNREAD);
            message.setBizType(bizType);
            message.setBizId(bizId);
            sysMessageMapper.insert(message);
        }
    }

    private void sendToUser(Long userId, String title, String content) {
        if (userId == null || !StringUtils.hasText(title) || !StringUtils.hasText(content)) {
            return;
        }
        SysUser recipient = sysUserMapper.selectById(userId);
        if (recipient == null || !Integer.valueOf(USER_STATUS_ENABLED).equals(recipient.getStatus())) {
            return;
        }
        SysMessage message = new SysMessage();
        message.setRecipientUserId(recipient.getId());
        message.setRecipientDeptId(recipient.getDeptId());
        message.setTitle(title.trim());
        message.setContent(content.trim());
        message.setIsRead(MESSAGE_UNREAD);
        sysMessageMapper.insert(message);
    }

    private void requireMessageAccess() {
        authzService.currentUser();
    }

    private SysMessage requireOwnedMessage(Long id) {
        SysMessage message = sysMessageMapper.selectById(id);
        if (message == null) {
            throw BusinessException.notFound("消息不存在");
        }
        Long currentUserId = authzService.currentUser().getId();
        if (!currentUserId.equals(message.getRecipientUserId())) {
            throw BusinessException.forbidden("无权限操作该消息");
        }
        return message;
    }

    private MessageVO toVO(SysMessage message) {
        MessageVO vo = new MessageVO();
        vo.setId(message.getId());
        vo.setTitle(message.getTitle());
        vo.setContent(message.getContent());
        vo.setRead(Integer.valueOf(MESSAGE_READ).equals(message.getIsRead()));
        vo.setReadTime(message.getReadTime());
        vo.setCreateTime(message.getCreateTime());
        return vo;
    }

    private String safeEmployeeName(String employeeName) {
        return StringUtils.hasText(employeeName) ? employeeName.trim() : "员工";
    }

    private String safeOperatorLabel(String operatorLabel) {
        return StringUtils.hasText(operatorLabel) ? operatorLabel.trim() : "系统";
    }

    private String safeRequirementSummary(String requirementContent) {
        if (!StringUtils.hasText(requirementContent)) {
            return "工作要求";
        }
        String normalized = requirementContent.trim().replaceAll("\\s+", " ");
        return normalized.length() > 24 ? normalized.substring(0, 24) + "..." : normalized;
    }

    private String formatMessageTime(LocalDateTime time) {
        return time == null ? "前" : "（" + MESSAGE_TIME_FORMATTER.format(time) + "）前";
    }
}