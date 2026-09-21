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

    // ==================== 消息跳转目标（D75）：前端路由字面值，与 front 路由表对齐 ====================
    private static final String ROUTE_SALES = "/business/sales";
    private static final String ROUTE_SALES_RETURN = "/business/sales-return";
    private static final String ROUTE_PURCHASE = "/business/purchase";
    private static final String ROUTE_PURCHASE_RETURN = "/business/purchase-return";
    private static final String ROUTE_PURCHASE_REQUEST = "/business/purchase-request";
    private static final String ROUTE_PICK_LIST = "/business/pick-list";
    private static final String ROUTE_PRODUCTION_ORDER = "/business/production-order";
    private static final String ROUTE_PRODUCTION = "/business/production";
    private static final String ROUTE_VOID_APPROVAL = "/system/void-approval";

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
                salesId,
                ROUTE_SALES);
    }

    /**
     * D70/D110：缺货销售单建单后通知生产管理员有销售需求待排产——缺货行按单汇总一条消息。
     * shortageDesc 为缺货行描述（如「PTO153×5（现存 2）、轴承×3（现存 0）」）。
     * 绑 biz_type=sales：销售单删除/作废/确认出库时随 D21 范式一并撤未读。
     */
    public void sendSalesDemandToProductionAdmins(String salesNo, String shortageDesc,
                                                  String customerName, String applicantName, Long salesId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        String customer = StringUtils.hasText(customerName) ? customerName : "未填写";
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "销售需求待排产",
                String.format(
                        Locale.ROOT,
                        "销售单 %s（缺货成品：%s；客户：%s；建单：%s）现货不足，请评估排产并在建生产任务单时关联该销售单。",
                        salesNo, shortageDesc, customer, applicantName
                ),
                "sales",
                salesId,
                ROUTE_PRODUCTION_ORDER);
    }

    /**
     * D70：关联生产任务单入库、销售单可发货时，通知建单销售本人（精准到人）。
     * goodsDesc 为成品描述（如「PTO153×5」，D110 汇总文案）。
     * 绑 biz_type=sales：销售单删除/作废/确认出库时随 D21 范式一并撤未读。
     */
    public void sendSalesReadyToShipToUser(Long userId, String salesNo, String goodsDesc, Long salesId) {
        sendToUserWithBiz(
                userId,
                "销售单可发货",
                String.format(
                        Locale.ROOT,
                        "您建的销售单 %s（成品 %s）对应生产任务单已完成入库，现货已可满足，请跟进仓储确认出库。",
                        salesNo, goodsDesc
                ),
                "sales",
                salesId,
                ROUTE_SALES);
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
                returnId,
                ROUTE_SALES_RETURN);
    }

    /**
     * D107：生产端提交成品入库申请 → 通知仓储管理员确认入库（不加库存，确认后才加）。
     * 绑 biz_type=production：确认/驳回/撤销申请时随 D21 范式一并撤未读。
     */
    public void sendProductionInboundPendingToWarehouseAdmins(String productionNo, String orderNo,
                                                              String goodsName, Integer quantity, Long productionId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                "待确认成品入库",
                String.format(
                        Locale.ROOT,
                        "生产任务单 %s 完工，成品 %s×%d 已提交入库申请（%s），请确认入库。确认前不会增加库存。",
                        orderNo, goodsName, quantity == null ? 0 : quantity, productionNo
                ),
                "production",
                productionId,
                ROUTE_PRODUCTION);
    }

    /**
     * D107：入库申请被仓储驳回 → 回执生产提交人（留痕通知，不随流程撤回）。
     */
    public void sendProductionInboundRejectedToUser(Long operatorId, String productionNo, String reason, Long productionId) {
        sendToUserWithBiz(
                operatorId,
                "成品入库申请已驳回",
                String.format(
                        Locale.ROOT,
                        "您提交的入库申请 %s 已被仓储驳回：%s。请核对后重新提交入库申请。",
                        productionNo, reason
                ),
                "production",
                productionId,
                ROUTE_PRODUCTION);
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
    /**
     * D87：按业务单据+标题白名单撤销未读消息。用于齐套类通知"撤旧发新"——
     * 同一 biz（production_order）下仅撤齐套类标题，不动「关联销售单已取消」(D73) 等其他通知。
     */
    public void revokeUnreadByBizAndTitles(String bizType, Long bizId, java.util.Collection<String> titles) {
        if (!StringUtils.hasText(bizType) || bizId == null || titles == null || titles.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<SysMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysMessage::getBizType, bizType)
                .eq(SysMessage::getBizId, bizId)
                .in(SysMessage::getTitle, titles)
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
                pickListId,
                ROUTE_SALES);
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
                pickId,
                ROUTE_PICK_LIST);
    }

    /** D87：齐套类通知标题——confirmReceive 重算时按标题白名单撤旧发新，保证互斥不堆积。 */
    public static final String TITLE_KIT_COMPLETE = "物料已齐套可领料";
    public static final String TITLE_KIT_INCOMPLETE = "补料部分到货仍缺料";
    public static final List<String> KIT_FAMILY_TITLES = List.of(TITLE_KIT_COMPLETE, TITLE_KIT_INCOMPLETE);

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
                TITLE_KIT_COMPLETE,
                String.format(Locale.ROOT,
                        "生产任务单 %s（成品 %s×%d）所需物料已全部入库齐套（补料单 %s 已入库），请前往生产任务单详情申请领料。",
                        orderNo == null ? "-" : orderNo,
                        goodsName == null ? "-" : goodsName,
                        quantity == null ? 0 : quantity,
                        requestNo == null ? "-" : requestNo),
                "production_order",
                orderId,
                ROUTE_PRODUCTION_ORDER);
    }

    /**
     * D87：补料单部分入库后生产单仍缺料 → 通知生产部管理员可继续补料（D86 已解锁再补）。
     * 与 sendKitCompleteToProductionAdmins 互斥（调用方先按 KIT_FAMILY_TITLES 撤旧再按结果发新）。
     * 绑 biz_type=production_order；守卫（生产单终态等）由调用方负责。
     */
    public void sendKitIncompleteToProductionAdmins(String orderNo, String goodsName, Integer quantity,
                                                    String requestNo, String shortageSummary, Long orderId) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                TITLE_KIT_INCOMPLETE,
                String.format(Locale.ROOT,
                        "生产任务单 %s（成品 %s×%d）的补料单 %s 已入库，但仍缺：%s。可前往生产任务单详情继续补料。",
                        orderNo == null ? "-" : orderNo,
                        goodsName == null ? "-" : goodsName,
                        quantity == null ? 0 : quantity,
                        requestNo == null ? "-" : requestNo,
                        StringUtils.hasText(shortageSummary) ? shortageSummary : "-"),
                "production_order",
                orderId,
                ROUTE_PRODUCTION_ORDER);
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
                pickId,
                ROUTE_PICK_LIST);
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
                requestId,
                ROUTE_PURCHASE_REQUEST);
    }

    /** D120：采购到货消息标题常量——驳回/撤回按此标题白名单撤回，不误撤认领等他条通知 */
    public static final String TITLE_PURCHASE_REQUEST_ARRIVED = "待确认采购入库";

    /**
     * D120 采购到货后通知仓储管理员有待确认的采购入库（按批：文案带批次号与本批行数）。
     */
    public void sendPurchaseRequestArrivedToWarehouseAdmins(String requestNo, String operatorName,
                                                             String batchLabel, int lineCount, Long requestId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "采购管理员";
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                TITLE_PURCHASE_REQUEST_ARRIVED,
                String.format(
                        Locale.ROOT,
                        "采购申请单 %s 第 %s 批已由 %s 到货（本批 %d 行），请尽快确认入库。",
                        requestNo, batchLabel, operator, lineCount
                ),
                "purchase_request",
                requestId,
                ROUTE_PURCHASE_REQUEST);
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
                requestId,
                ROUTE_PURCHASE_REQUEST);
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
                orderId,
                ROUTE_PURCHASE_REQUEST);
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
                purchaseId,
                ROUTE_PURCHASE);
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
                returnId,
                ROUTE_PURCHASE_RETURN);
    }

    /**
     * D73：销售单删除/作废生效后，其关联的未终态生产任务单 → 通知生产管理员手动终止并退料。
     * goodsDesc 为销售单成品行汇总描述（D110：如「PTO153×5、轴承×3」）。
     * 绑 biz_type=production_order + biz_id=生产单id：生产单终止/作废/报废/入库终态时随 D21 范式撤未读。
     */
    public void sendSalesCancelledToProductionAdmins(String salesNo, String goodsDesc,
                                                     String orderNo, Long orderId, String cancelAction) {
        Long productionDeptId = resolveDeptIdByCode(AuthzService.DEPT_PRODUCTION);
        if (productionDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                productionDeptId,
                "关联销售单已取消",
                String.format(Locale.ROOT,
                        "销售单 %s（成品 %s）已%s，其关联的生产任务单 %s 仍未完结。请评估后手动终止该任务单（终止时系统将预填已领未退物料供退料回库）。",
                        salesNo == null ? "-" : salesNo,
                        goodsDesc == null ? "-" : goodsDesc,
                        cancelAction == null ? "取消" : cancelAction,
                        orderNo == null ? "-" : orderNo),
                "production_order",
                orderId,
                ROUTE_PRODUCTION_ORDER);
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
                pickId,
                ROUTE_PICK_LIST);
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
                pickId,
                ROUTE_PICK_LIST);
    }

    /**
     * 销售价偏离标准售价超阈值时通知超级管理员审批（绑 biz_type=sales，对齐 D21 范式）。
     * deviationDesc 为偏离行描述（D110 决策④整单一笔：如「第1行 PTO153 偏离 20%；第3行 轴承 偏离 10%」）。
     * 超管 dept_id 为空，不能走部门广播，按 role=salesadmin 单点投递。
     */
    public void sendPriceDeviationToSuperAdmin(String salesNo, String operatorName, String deviationDesc, Long salesId) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(SysUser::getRole, AuthzService.ROLE_SUPERADMIN)
                .eq(SysUser::getStatus, USER_STATUS_ENABLED)
                .last("LIMIT 1");
        SysUser superadmin = sysUserMapper.selectOne(wrapper);
        if (superadmin == null) {
            return;
        }
        String operator = StringUtils.hasText(operatorName) ? operatorName : "销售管理员";
        SysMessage message = new SysMessage();
        message.setRecipientUserId(superadmin.getId());
        message.setRecipientDeptId(superadmin.getDeptId());
        message.setTitle("待审批价格偏离销售单");
        message.setContent(String.format(Locale.ROOT,
                "销售单 %s 由 %s 提交，销售价偏离标准售价：%s，超阈值，请审批后仓储方可确认出库。",
                salesNo, operator, deviationDesc == null ? "-" : deviationDesc));
        message.setIsRead(MESSAGE_UNREAD);
        message.setBizType("sales");
        message.setBizId(salesId);
        message.setTargetRoute(ROUTE_VOID_APPROVAL);
        sysMessageMapper.insert(message);
    }

    // ==================== 作废审批消息（D103） ====================

    /** D103：作废审批类消息标题——驳回时按标题精确撤回，不误伤同 biz 下其他待办消息 */
    public static final String TITLE_VOID_APPROVAL_PENDING = "待审批作废申请";

    /** D103：业务单据 biz_type → 列表页路由（结果回执的跳转目标），未知类型返回 null */
    public static String routeOfBizType(String bizType) {
        return switch (bizType == null ? "" : bizType) {
            case "purchase" -> ROUTE_PURCHASE;
            case "purchase_return" -> ROUTE_PURCHASE_RETURN;
            case "sales" -> ROUTE_SALES;
            case "sales_return" -> ROUTE_SALES_RETURN;
            default -> null;
        };
    }

    /**
     * D103：作废审批提交 → 通知仓储管理员审批（绑业务单 biz_type/biz_id，D21 范式）。
     * 通过时随业务 voidDocument 的 revokeUnreadByBiz 一并撤未读；驳回时由 ApprovalService 按标题撤回。
     */
    public void sendVoidApprovalPendingToWarehouseAdmins(String bizTypeLabel, String bizNo, String requesterName,
                                                         String bizType, Long bizId) {
        Long warehouseDeptId = resolveDeptIdByCode(AuthzService.DEPT_WAREHOUSE);
        if (warehouseDeptId == null) {
            return;
        }
        sendToDeptAdminsWithBiz(
                warehouseDeptId,
                TITLE_VOID_APPROVAL_PENDING,
                String.format(
                        Locale.ROOT,
                        "%s %s 已由 %s 提交作废申请，请前往作废审批处理。",
                        bizTypeLabel, bizNo, StringUtils.hasText(requesterName) ? requesterName : "申请人"
                ),
                bizType,
                bizId,
                ROUTE_VOID_APPROVAL);
    }

    /**
     * D103：作废审批结果回执 → 精准通知申请人（通过=单据已作废生效；驳回=维持原样）。
     * 绑业务单 biz：通过场景由 voidDocument 先撤未读、本消息后发故保留；驳回场景单据仍有效，回执留待已读。
     */
    public void sendVoidApprovalResultToRequester(Long requesterId, String bizTypeLabel, String bizNo,
                                                  boolean approved, String approverName, String remark,
                                                  String bizType, Long bizId, String targetRoute) {
        String title = approved ? "作废审批已通过" : "作废审批已驳回";
        String content = approved
                ? String.format(Locale.ROOT,
                        "您提交的 %s %s 作废申请已由 %s 审批通过，单据已作废生效，相关库存影响已在生效时处理。",
                        bizTypeLabel, bizNo, StringUtils.hasText(approverName) ? approverName : "仓储管理员")
                : String.format(Locale.ROOT,
                        "您提交的 %s %s 作废申请已由 %s 驳回，单据维持原状。%s",
                        bizTypeLabel, bizNo, StringUtils.hasText(approverName) ? approverName : "仓储管理员",
                        StringUtils.hasText(remark) ? "审批备注：" + remark : "");
        sendToUserWithBiz(requesterId, title, content, bizType, bizId, targetRoute);
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
        sendToDeptAdminsWithBiz(deptId, title, content, null, null, null);
    }

    private void sendToDeptAdminsWithBiz(Long deptId, String title, String content, String bizType, Long bizId, String targetRoute) {
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
            message.setTargetRoute(targetRoute);
            sysMessageMapper.insert(message);
        }
    }

    private void sendToUser(Long userId, String title, String content) {
        sendToUserWithBiz(userId, title, content, null, null, null);
    }

    /** 精准到人 + 业务绑定（D70 用到）：单据终态/撤销时按 biz 撤未读，避免悬挂通知 */
    private void sendToUserWithBiz(Long userId, String title, String content, String bizType, Long bizId, String targetRoute) {
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
        message.setBizType(bizType);
        message.setBizId(bizId);
        message.setTargetRoute(targetRoute);
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
        vo.setBizType(message.getBizType());
        vo.setBizId(message.getBizId());
        vo.setTargetRoute(message.getTargetRoute());
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