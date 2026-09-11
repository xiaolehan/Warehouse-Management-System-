package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionReturnCreateDTO;
import org.example.back.dto.ProductionReturnItemDTO;
import org.example.back.dto.ProductionTerminateDTO;
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
import org.example.back.vo.ProductionReturnableVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

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
        dup.eq(BizPickList::getProductionOrderId, orderId)
                .eq(BizPickList::getPickType, PickListService.TYPE_PICK);
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

        // D63：建单时快照物料主数据规格/材质/备注，单据留档（历史行展示由 PickListService 兜底实时读）
        Map<Long, BaseGoods> goodsMap = loadGoodsSnapshot(items.stream()
                .map(ProductionPickItemVO::getGoodsId)
                .filter(Objects::nonNull)
                .distinct()
                .toList());

        int sortNo = 0;
        for (ProductionPickItemVO item : items) {
            BizPickListDetail det = new BizPickListDetail();
            det.setPickListId(pick.getId());
            det.setGoodsId(item.getGoodsId());
            det.setGoodsName(item.getGoodsName());
            det.setQuantity(item.getQuantity());
            applyGoodsSnapshot(det, goodsMap.get(item.getGoodsId()));
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
        if (findOpenReturn(orderId) != null) {
            throw BusinessException.validateFail("该生产任务单已有待确认的退料单");
        }

        List<ProductionReturnItemDTO> items = dto.getItems().stream()
                .filter(i -> i.getGoodsId() != null && i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
        if (items.isEmpty()) {
            throw BusinessException.validateFail("无有效退料明细行");
        }

        String remark = "生产任务单 " + order.getOrderNo() + " 退料";
        if (dto.getRemark() != null && !dto.getRemark().isEmpty()) {
            remark += "：" + dto.getRemark();
        }
        insertReturnList(order, items, remark);
    }

    /** 与 createReturn 防堆叠口径一致：RETURN 且状态非 已完成/已驳回 → 视为进行中退料单 */
    private BizPickList findOpenReturn(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .eq(BizPickList::getPickType, PickListService.TYPE_RETURN)
                .notIn(BizPickList::getStatus, PickListService.STATUS_DONE, PickListService.STATUS_REJECTED)
                .orderByDesc(BizPickList::getId)
                .last("LIMIT 1");
        return pickListMapper.selectOne(w);
    }

    /** 生成 RETURN 退料单（待发料）+ 明细（D63 主数据快照）+ 通知仓储确认入库；调用方负责状态/堆叠守卫 */
    private void insertReturnList(BizProductionOrder order, List<ProductionReturnItemDTO> items, String remark) {
        LoginResponse.UserInfoVO user = authService.getUserInfo();
        BizPickList pick = new BizPickList();
        pick.setPickNo(CodeGenerator.pickListNo());
        pick.setPickType(PickListService.TYPE_RETURN);
        pick.setStatus(PickListService.STATUS_PENDING);
        pick.setProductionOrderId(order.getId());
        pick.setApplicantId(user.getId());
        pick.setApplicantName(user.getRealName());
        pick.setRemark(remark);
        pickListMapper.insert(pick);

        int sortNo = 0;
        for (ProductionReturnItemDTO item : items) {
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
            applyGoodsSnapshot(det, goods);
            det.setSortNo(sortNo++);
            pickListDetailMapper.insert(det);
        }

        messageService.sendPickReturnPendingToWarehouseAdmins(pick.getPickNo(), order.getOrderNo(), pick.getId());
    }

    // ============================== 手动终止（D73） ==============================

    /**
     * D73：手动终止生产任务单（销售取消等外部原因中途停单；区别于作废=单据不该存在）。
     * 仅生产管理员；待生产/生产中/待入库可终止，终态不可逆；原因必填（审计留痕）。
     * 同事务按提交的退料明细生成 RETURN 退料单（待发料，仓储确认后回流入库）；
     * 已有进行中退料单时跳过自动生成（前端已提示人工核对覆盖）；无明细则仅终止。
     */
    @Transactional(rollbackFor = Exception.class)
    public void terminate(Long orderId, ProductionTerminateDTO dto) {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION, "仅生产研发部管理员可终止生产任务单");
        BizProductionOrder order = productionOrderMapper.selectById(orderId);
        if (order == null) {
            throw BusinessException.notFound("生产任务单不存在");
        }
        if (order.getStatus() == null || !BizProductionOrder.UNFINISHED_STATUSES.contains(order.getStatus())) {
            throw BusinessException.validateFail("仅待生产/生产中/待入库状态可终止");
        }
        if (dto == null) {
            throw BusinessException.validateFail("终止原因不能为空");
        }
        String reason = dto.getReason();
        if (reason == null || reason.trim().isEmpty()) {
            throw BusinessException.validateFail("终止原因不能为空");
        }
        // 待出库领料单未收口：仓储仍可能发料给一张死单，先人工收口（撤销/驳回）再终止
        ensureNoPendingPick(orderId);

        List<ProductionReturnItemDTO> items = dto.getItems() == null ? List.of() : dto.getItems().stream()
                .filter(i -> i.getGoodsId() != null && i.getQuantity() != null && i.getQuantity() > 0)
                .toList();
        if (!items.isEmpty()) {
            ensureWithinReturnable(orderId, items);
        }

        // 终态化 + 原因留痕 + 撤未读（含"关联销售单已取消"通知——已处理完毕）
        order.setStatus(BizProductionOrder.STATUS_TERMINATED);
        order.setRemark(appendRemark(order.getRemark(), "终止原因: " + reason.trim()));
        productionOrderMapper.updateById(order);
        messageService.revokeUnreadByBiz("production_order", orderId);

        // Q6 定案：已有进行中退料单 → 跳过自动生成
        if (!items.isEmpty() && findOpenReturn(orderId) == null) {
            insertReturnList(order, items, "生产任务单 " + order.getOrderNo() + " 终止退料：" + reason.trim());
        }
    }

    /**
     * D73：终止弹窗「已领未退」预览 = PICK/SUPPLY（已发料/已完成）− RETURN（已发料/已完成），按物料分组取净额>0。
     * 生产成员可读（与领料/退料一致）。
     */
    @Transactional(readOnly = true)
    public ProductionReturnableVO computeReturnablePreview(Long orderId) {
        requireProductionMember();
        ProductionReturnableVO vo = new ProductionReturnableVO();
        vo.setItems(computeNetReturnableItems(orderId));
        BizPickList open = findOpenReturn(orderId);
        vo.setHasOpenReturn(open != null);
        vo.setOpenReturnPickNo(open == null ? null : open.getPickNo());
        return vo;
    }

    private void ensureNoPendingPick(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .in(BizPickList::getPickType, PickListService.TYPE_PICK, PickListService.TYPE_SUPPLY)
                .eq(BizPickList::getStatus, PickListService.STATUS_PENDING);
        if (pickListMapper.selectCount(w) > 0) {
            throw BusinessException.validateFail("存在待出库的领料单，请先由申请人撤销或仓储驳回后再终止");
        }
    }

    /** 服务端兜底：提交退料量不得超过「已领未退」净额，防多退导致库存虚增 */
    private void ensureWithinReturnable(Long orderId, List<ProductionReturnItemDTO> items) {
        Map<Long, Integer> returnable = new HashMap<>();
        Map<Long, String> names = new HashMap<>();
        for (ProductionPickItemVO vo : computeNetReturnableItems(orderId)) {
            returnable.put(vo.getGoodsId(), vo.getQuantity());
            names.put(vo.getGoodsId(), vo.getGoodsName());
        }
        for (ProductionReturnItemDTO item : items) {
            Integer max = returnable.get(item.getGoodsId());
            if (max == null) {
                throw BusinessException.validateFail(
                        "物料[id=" + item.getGoodsId() + "]不在该任务单已领未退清单内，不可退");
            }
            if (item.getQuantity() > max) {
                throw BusinessException.validateFail(
                        "物料[" + names.get(item.getGoodsId()) + "]退料数量超过已领未退（可退 " + max + "）");
            }
        }
    }

    private List<ProductionPickItemVO> computeNetReturnableItems(Long orderId) {
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId)
                .in(BizPickList::getStatus, PickListService.STATUS_ISSUED, PickListService.STATUS_DONE);
        List<BizPickList> lists = pickListMapper.selectList(w);
        if (lists.isEmpty()) {
            return List.of();
        }
        List<Long> outIds = lists.stream()
                .filter(p -> !PickListService.TYPE_RETURN.equals(p.getPickType()))
                .map(BizPickList::getId).toList();
        List<Long> inIds = lists.stream()
                .filter(p -> PickListService.TYPE_RETURN.equals(p.getPickType()))
                .map(BizPickList::getId).toList();
        Map<Long, Integer> net = new LinkedHashMap<>();
        Map<Long, String> names = new LinkedHashMap<>();
        addDetailSums(outIds, net, names, 1);
        addDetailSums(inIds, net, names, -1);
        List<Long> goodsIds = net.entrySet().stream()
                .filter(e -> e.getValue() > 0).map(Map.Entry::getKey).toList();
        Map<Long, BaseGoods> goodsMap = loadGoodsSnapshot(goodsIds);
        List<ProductionPickItemVO> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> e : net.entrySet()) {
            if (e.getValue() <= 0) {
                continue;
            }
            ProductionPickItemVO item = new ProductionPickItemVO();
            item.setGoodsId(e.getKey());
            item.setGoodsName(names.get(e.getKey()));
            BaseGoods g = goodsMap.get(e.getKey());
            if (g != null) {
                item.setSpec(g.getSpec());
                item.setMaterial(g.getMaterial());
            }
            item.setQuantity(e.getValue());
            items.add(item);
        }
        return items;
    }

    private void addDetailSums(List<Long> pickListIds, Map<Long, Integer> net, Map<Long, String> names, int sign) {
        if (pickListIds.isEmpty()) {
            return;
        }
        LambdaQueryWrapper<BizPickListDetail> w = new LambdaQueryWrapper<>();
        w.in(BizPickListDetail::getPickListId, pickListIds);
        for (BizPickListDetail d : pickListDetailMapper.selectList(w)) {
            if (d.getGoodsId() == null || d.getQuantity() == null) {
                continue;
            }
            net.merge(d.getGoodsId(), sign * d.getQuantity(), Integer::sum);
            names.putIfAbsent(d.getGoodsId(), d.getGoodsName());
        }
    }

    private String appendRemark(String oldRemark, String suffix) {
        if (oldRemark == null || oldRemark.isBlank()) {
            return suffix;
        }
        return oldRemark + " | " + suffix;
    }

    public List<PickListVO> listByOrder(Long orderId) {
        requireProductionMember();
        LambdaQueryWrapper<BizPickList> w = new LambdaQueryWrapper<>();
        w.eq(BizPickList::getProductionOrderId, orderId).orderByDesc(BizPickList::getId);
        return pickListMapper.selectList(w).stream().map(this::toVO).toList();
    }

    /** D63：按物料主数据批量取规格/材质/描述，用于建单时快照。 */
    private Map<Long, BaseGoods> loadGoodsSnapshot(List<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        return baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
    }

    private void applyGoodsSnapshot(BizPickListDetail det, BaseGoods goods) {
        if (goods == null) {
            return;
        }
        det.setSpec(goods.getSpec());
        det.setMaterial(goods.getMaterial());
        det.setRemark(goods.getDescription());
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
