package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.dto.ProductionReturnItemDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizPickList;
import org.example.back.entity.BizPickListDetail;
import org.example.back.entity.BizProductionOrder;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizPickListDetailMapper;
import org.example.back.mapper.BizPickListMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.vo.PickListVO;
import org.example.back.vo.ProductionPickItemVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;

/**
 * 生产端领料：按生产任务单 BOM 全量申请领料，生成 PICK 领料单后交仓储确认出库。
 * 生产端不直接扣库存；扣库存的最终动作出库由 PickListService.issue（仓储）完成。
 */
@Service
public class ProductionPickService {

    @Autowired private BizPickListMapper pickListMapper;
    @Autowired private BizPickListDetailMapper pickListDetailMapper;
    @Autowired private BizProductionOrderMapper productionOrderMapper;
    @Autowired private BaseGoodsMapper baseGoodsMapper;
    @Autowired private AuthService authService;
    @Autowired private AuthzService authzService;
    @Autowired private MessageService messageService;
    @Autowired private ProductionOrderService productionOrderService;

    public void requireProductionMember() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部可申请生产领料", AuthzService.DEPT_PRODUCTION);
    }

    @Transactional(rollbackFor = Exception.class)
    public PickListVO createPick(Long orderId) {
        requireProductionMember();
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() != BizProductionOrder.STATUS_PENDING) {
            throw BusinessException.validateFail("仅待生产状态可申请领料");
        }
        LambdaQueryWrapper<BizPickList> dup = new LambdaQueryWrapper<>();
        dup.eq(BizPickList::getProductionOrderId, orderId);
        if (pickListMapper.selectCount(dup) > 0) {
            throw BusinessException.validateFail("该生产任务单已申请领料，请勿重复");
        }

        List<ProductionPickItemVO> items = productionOrderService.computePickItems(orderId);

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_PICK);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(orderId);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark("生产任务单 " + order.getOrderNo() + " 申请领料");
        try {
            pickListMapper.insert(pick);
        } catch (DuplicateKeyException e) {
            throw BusinessException.validateFail("该生产任务单已申请领料，请勿重复");
        }

        int sortNo = 0;
        for (ProductionPickItemVO item : items) {
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(item.getGoodsId());
            det.setGoodsName(item.getGoodsName());
            det.setQuantity(item.getQuantity());
            det.setSortNo(sortNo++);
            pickListDetailMapper.insert(det);
        }

        messageService.sendPickPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
        return toVO(pick);
    }

    /**
     * 生产端退料：按实际退回物料明细生成 RETURN 类型领料单，交仓储确认回流入库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createReturn(Long orderId, ProductionReturnCreateDTO dto) {
        requireProductionMember();
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() != BizProductionOrder.STATUS_IN_PROGRESS) {
            throw BusinessException.validateFail("仅生产中状态可退料");
        }
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("请选择退料明细");
        }

        // 防止堆叠：同一张生产任务单已有待处理的退料单则拒绝（已完成/已驳回的可重提）
        LambdaQueryWrapper<BizPickList> pendingRet = new LambdaQueryWrapper<>();
        pendingRet.eq(BizPickList::getProductionOrderId, orderId)
                .eq(BizPickList::getPickType, PickListService.TYPE_RETURN)
                .notIn(BizPickList::getStatus, PickListService.STATUS_DONE, PickListService.STATUS_REJECTED);
        if (pickListMapper.selectCount(pendingRet) > 0) {
            throw BusinessException.validateFail("该生产任务单已有待确认的退料单");
        }

        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(orderId);
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        String remark = "生产任务单 " + order.getOrderNo() + " 退料";
        if (dto.getRemark() != null && !dto.getRemark().isEmpty()) {
            remark += "：" + dto.getRemark();
        }
        pick.setRemark(remark);
        pickListMapper.insert(pick);

        int sortNo = 0;
        for (ProductionReturnItemDTO item : dto.getItems()) {
            if (item.getQuantity() == null || item.getQuantity() <= 0) {
                continue;
            }
            BaseGoods goods = baseGoodsMapper.selectById(item.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail(
                        String.format(Locale.ROOT, "物料不存在: id=%d", item.getGoodsId()));
            }
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(goods.getId());
            det.setGoodsName(goods.getGoodsName());
            det.setQuantity(item.getQuantity());
            det.setSortNo(sortNo++);
            pickListDetailMapper.insert(det);
        }
        if (sortNo == 0) {
            throw BusinessException.validateFail("无有效退料明细行");
        }

        messageService.sendPickReturnPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
    }

    public List<PickListVO> listByOrder(Long orderId) {
        requireProductionMember();
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId).orderByDesc(BizPickList::getId);
        return pickListMapper.selectList(w).stream().map(this::toVO).toList();
    }

    /**
     * 返回该生产单可领料明细（BOM×数量），用于前端"申请领料"弹窗前校验与展示。
     * 权限：仅生产研发部成员可查（与申请领料权限一致）。
     */
    public List<ProductionPickItemVO> editableItems(Long orderId) {
        requireProductionMember();
        return productionOrderService.computePickItems(orderId);
    }

    private String statusText(Integer status) {
        if (status == null) return null;
        return switch (status) {
            case 1 -> "待发料";
            case 2 -> "已发料";
            case 3 -> "已完成";
            case 4 -> "已驳回";
            default -> String.valueOf(status);
        };
    }

    private PickListVO toVO(BizPickList p) {
        PickListVO vo = new PickListVO();
        vo.setId(p.getId());
        vo.setPickNo(p.getPickNo());
        vo.setPickType(p.getPickType());
        vo.setPickTypeText(pickTypeText(p.getPickType()));
        vo.setStatus(p.getStatus());
        vo.setStatusText(statusText(p.getStatus()));
        vo.setApplicantId(p.getApplicantId());
        vo.setApplicantName(p.getApplicantName());
        vo.setRemark(p.getRemark());
        vo.setCreateTime(p.getCreateTime());
        return vo;
    }

    private String pickTypeText(String pickType) {
        if (PickListService.TYPE_PICK.equals(pickType)) return "领料";
        if (PickListService.TYPE_SUPPLY.equals(pickType)) return "补料";
        if (PickListService.TYPE_RETURN.equals(pickType)) return "退料";
        return pickType;
    }
}
