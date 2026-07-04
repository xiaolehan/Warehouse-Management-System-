package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.ProductionQueryDTO;
import org.example.back.dto.ProductionSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizProduction;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizProductionMapper;
import org.example.back.vo.ProductionVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 生产入库：仓储管理员将自己生产的零件存入仓库（库存增加）。
 * 范式对齐进货（PurchaseService），但归属仓储部门、无供应商、生产单价可选、作废为仓储直接作废（不走跨部门审批）。
 */
@Service
public class ProductionService {

    @Autowired
    private BizProductionMapper bizProductionMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    private void requireProductionModuleAccess() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可访问生产入库模块");
    }

    public PageResult<ProductionVO> page(ProductionQueryDTO queryDTO) {
        requireProductionModuleAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizProduction> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getProductionNo()), BizProduction::getProductionNo, queryDTO.getProductionNo())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizProduction::getGoodsName, queryDTO.getGoodsName())
                .eq(queryDTO.getGoodsId() != null, BizProduction::getGoodsId, queryDTO.getGoodsId())
                .ge(startTime != null, BizProduction::getOperationTime, startTime)
                .lt(endTime != null, BizProduction::getOperationTime, endTime)
                .orderByDesc(BizProduction::getId);

        Page<BizProduction> page = bizProductionMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BaseGoods> goodsMap = buildGoodsMap(page.getRecords().stream().map(BizProduction::getGoodsId).collect(Collectors.toSet()));

        List<ProductionVO> records = page.getRecords().stream()
                .map(item -> toVO(item, goodsMap.get(item.getGoodsId())))
                .toList();

        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public ProductionVO getById(Long id) {
        requireProductionModuleAccess();
        BizProduction production = requireProduction(id);
        BaseGoods goods = baseGoodsMapper.selectById(production.getGoodsId());
        return toVO(production, goods);
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(ProductionSaveDTO dto) {
        requireProductionModuleAccess();
        validateQuantity(dto.getQuantity());

        BaseGoods goods = requireGoods(dto.getGoodsId());
        ensureGoodsEnabled(goods);
        BigDecimal unitPrice = resolveProductionUnitPrice(dto.getUnitPrice());
        LocalDateTime operationTime = dto.getOperationTime() == null ? LocalDateTime.now() : dto.getOperationTime();

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        BizProduction production = new BizProduction();
        production.setProductionNo(CodeGenerator.productionNo());
        production.setGoodsId(goods.getId());
        production.setGoodsName(goods.getGoodsName());
        production.setQuantity(dto.getQuantity());
        production.setUnitPrice(unitPrice);
        production.setTotalPrice(unitPrice == null ? null : unitPrice.multiply(BigDecimal.valueOf(dto.getQuantity())));
        production.setOperatorId(loginUser.getId());
        production.setOperatorName(loginUser.getRealName());
        production.setOperationTime(operationTime);
        production.setRemark(dto.getRemark());
        production.setBizStatus(1);

        bizProductionMapper.insert(production);
        increaseStock(goods.getId(), dto.getQuantity());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireProductionModuleAccess();
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");
        validateDeleteWindow(production.getOperationTime(), "生产入库单");
        decreaseStock(production.getGoodsId(), production.getQuantity(), "当前库存不足，无法删除该生产入库单");
        bizProductionMapper.deleteById(id);
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        requireProductionModuleAccess();
        BizProduction production = requireProduction(id);
        ensureNormalStatus(production.getBizStatus(), "生产入库单");

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizProduction> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizProduction::getId, production.getId())
                .eq(BizProduction::getBizStatus, 1)
                .set(BizProduction::getBizStatus, 2)
                .set(BizProduction::getVoidTime, now)
                .set(BizProduction::getVoidReason, reason);
        int rows = bizProductionMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("生产入库单已被处理，禁止重复作废");
        }

        decreaseStock(production.getGoodsId(), production.getQuantity(), "当前库存不足，无法作废该生产入库单");

        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
            BizProduction redFlushDoc = new BizProduction();
            redFlushDoc.setProductionNo(CodeGenerator.productionNo());
            redFlushDoc.setGoodsId(production.getGoodsId());
            redFlushDoc.setGoodsName(production.getGoodsName());
            redFlushDoc.setQuantity(-production.getQuantity());
            redFlushDoc.setUnitPrice(production.getUnitPrice());
            redFlushDoc.setTotalPrice(production.getTotalPrice() == null ? null : production.getTotalPrice().negate());
            redFlushDoc.setOperatorId(loginUser.getId());
            redFlushDoc.setOperatorName(loginUser.getRealName());
            redFlushDoc.setOperationTime(now);
            redFlushDoc.setRemark("红冲来源:" + production.getProductionNo());
            redFlushDoc.setBizStatus(3);
            redFlushDoc.setSourceId(production.getId());
            redFlushDoc.setVoidReason(reason);
            bizProductionMapper.insert(redFlushDoc);
        }
    }

    private BizProduction requireProduction(Long id) {
        BizProduction production = bizProductionMapper.selectById(id);
        if (production == null) {
            throw BusinessException.notFound("生产入库单不存在");
        }
        return production;
    }

    private BaseGoods requireGoods(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw BusinessException.validateFail("商品不存在");
        }
        return goods;
    }

    private void ensureGoodsEnabled(BaseGoods goods) {
        if (goods.getStatus() == null || goods.getStatus() != 1) {
            throw BusinessException.validateFail("商品已下架，无法创建业务单据");
        }
    }

    private void validateDeleteWindow(LocalDateTime operationTime, String docName) {
        if (operationTime == null) {
            return;
        }
        if (!operationTime.toLocalDate().equals(LocalDate.now())) {
            throw BusinessException.validateFail("仅允许删除当天" + docName + "，历史单据请走作废/红冲流程");
        }
    }

    private void ensureNormalStatus(Integer bizStatus, String docName) {
        if (bizStatus == null || bizStatus == 1) {
            return;
        }
        if (bizStatus == 2) {
            throw BusinessException.validateFail(docName + "已作废，禁止重复操作");
        }
        throw BusinessException.validateFail(docName + "为红冲单，禁止删除或再次作废");
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "手工作废";
        }
        return reason.trim();
    }

    private void validateQuantity(Integer quantity) {
        if (quantity == null || quantity <= 0) {
            throw BusinessException.validateFail("数量必须大于0");
        }
    }

    /**
     * 生产单价可选：为空返回 null（不记录成本）；非空则必须大于 0。
     */
    private BigDecimal resolveProductionUnitPrice(BigDecimal inputPrice) {
        if (inputPrice == null) {
            return null;
        }
        if (inputPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.validateFail("单价必须大于0");
        }
        return inputPrice;
    }

    private void increaseStock(Long goodsId, Integer quantity) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .setSql("stock = stock + " + quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.validateFail("商品不存在");
        }
    }

    private void decreaseStock(Long goodsId, Integer quantity, String msg) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .ge(BaseGoods::getStock, quantity)
                .setSql("stock = stock - " + quantity);
        int rows = baseGoodsMapper.update(null, wrapper);
        if (rows == 0) {
            throw BusinessException.stockInsufficient(msg);
        }
    }

    private Map<Long, BaseGoods> buildGoodsMap(Set<Long> goodsIds) {
        if (goodsIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.in(BaseGoods::getId, goodsIds);
        return baseGoodsMapper.selectList(wrapper).stream().collect(Collectors.toMap(BaseGoods::getId, Function.identity()));
    }

    private ProductionVO toVO(BizProduction production, BaseGoods goods) {
        ProductionVO vo = new ProductionVO();
        BeanUtils.copyProperties(production, vo);
        LocalDateTime bizTime = production.getOperationTime() == null ? production.getCreateTime() : production.getOperationTime();
        vo.setOrderNo(production.getProductionNo());
        vo.setPrice(production.getUnitPrice());
        vo.setTotalAmount(production.getTotalPrice());
        vo.setOperationTime(bizTime);
        vo.setProductionDate(bizTime);
        vo.setOperator(production.getOperatorName());
        vo.setBizStatus(production.getBizStatus());
        vo.setSourceId(production.getSourceId());
        vo.setVoidTime(production.getVoidTime());
        vo.setVoidReason(production.getVoidReason());
        return vo;
    }
}
