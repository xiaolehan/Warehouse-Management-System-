package org.example.back.service;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.DocumentVoidDTO;
import org.example.back.dto.SalesQueryDTO;
import org.example.back.dto.SalesTerminateDTO;
import org.example.back.dto.SalesSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizApprovalOrder;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizProductionOrder;
import org.example.back.entity.BizSales;
import org.example.back.entity.BizSalesDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.mapper.BizApprovalOrderMapper;
import org.example.back.mapper.BizProductionOrderMapper;
import org.example.back.mapper.BizPurchaseMapper;
import org.example.back.mapper.BizSalesDetailMapper;
import org.example.back.mapper.BizSalesMapper;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.SalesDetailVO;
import org.example.back.vo.SalesSourceOptionLineVO;
import org.example.back.vo.SalesSourceOptionVO;
import org.example.back.vo.SalesVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SalesService {

    public static final int CONFIRM_PENDING = 1;
    public static final int CONFIRM_SHIPPED = 2;

    /**
     * 销售价偏离标准售价的审批动作（D29）：偏离需销售管理员审批（D120），仓储确认出库前置校验。
     * 偏离阈值由销售管理员在系统参数页配置（sys_config.price_deviation_threshold，D30/D120 兑现），见 SysConfigService。
     */
    private static final String PRICE_DEVIATION_APPROVAL_ACTION = "price_deviation_confirm";

    @Autowired
    private BizSalesMapper bizSalesMapper;

    @Autowired
    private BizSalesDetailMapper bizSalesDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private BizBomMapper bizBomMapper;

    @Autowired
    private SalesReturnService salesReturnService;

    @Autowired
    private BizPurchaseMapper bizPurchaseMapper;

    @Autowired
    private BizApprovalOrderMapper bizApprovalOrderMapper;

    @Autowired
    private BizProductionOrderMapper bizProductionOrderMapper;

    @Autowired
    private AuthService authService;

    @Autowired
    private AuthzService authzService;

    @Autowired
    private MessageService messageService;
    @Autowired
    private SplitOrderService splitOrderService;

    @Autowired
    private SysConfigService sysConfigService;

    private void requireSalesModuleAccess() {
        // D32：销售部门成员（admin+员工）可建单/建退货（不动库存）
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_SALES, "仅销售部门可访问销售模块");
    }

    /**
     * 销售管理员权限（admin）：删除当天单据、发起作废等写操作收口 admin（D32：员工仅 create+read）。
     */
    private void requireSalesAdminOrSuperAdmin() {
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_SALES, "仅销售管理员可执行该操作");
    }

    /**
     * 销售单读取权限：销售部门成员可完整管理，仓储部门成员可查看以便确认出库。
     */
    private void requireSalesReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅销售/仓储部门可访问销售模块", AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
    }

    public PageResult<SalesVO> page(SalesQueryDTO queryDTO) {
        requireSalesReadAccess();
        LocalDateTime startTime = queryDTO.getStartDate() == null ? null : queryDTO.getStartDate().atStartOfDay();
        LocalDateTime endTime = queryDTO.getEndDate() == null ? null : queryDTO.getEndDate().plusDays(1).atStartOfDay();

        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getSalesNo()), BizSales::getSalesNo, queryDTO.getSalesNo())
                .like(StringUtils.hasText(queryDTO.getCustomerName()), BizSales::getCustomerName, queryDTO.getCustomerName())
            .ge(startTime != null, BizSales::getOperationTime, startTime)
            .lt(endTime != null, BizSales::getOperationTime, endTime)
                .orderByDesc(BizSales::getId);
        // D110：商品名/商品id 过滤下沉明细行（头单已无商品字段），EXISTS 参数化防注入
        wrapper.apply(StringUtils.hasText(queryDTO.getGoodsName()),
                        "EXISTS (SELECT 1 FROM biz_sales_detail d WHERE d.sales_id = biz_sales.id AND d.is_deleted = 0"
                                + " AND d.goods_name LIKE CONCAT('%', {0}, '%'))", queryDTO.getGoodsName());
        wrapper.apply(queryDTO.getGoodsId() != null,
                        "EXISTS (SELECT 1 FROM biz_sales_detail d WHERE d.sales_id = biz_sales.id AND d.is_deleted = 0"
                                + " AND d.goods_id = {0})", queryDTO.getGoodsId());

        Page<BizSales> page = bizSalesMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        Map<Long, BizApprovalOrder> approvalMap = buildLatestApprovalMap(page.getRecords().stream().map(BizSales::getId).toList());
        // D 价格偏离审批：销售管理员驳回后单子退回销售人员，仓储视角列表不再展示被驳回的偏离单
        boolean warehouseView = isWarehouseView();
        List<SalesVO> records = page.getRecords().stream()
                .map(item -> toVO(item, approvalMap.get(item.getId())))
                .filter(vo -> !warehouseView || !isPriceDeviationRejected(vo))
                .toList();
        fillDetails(records); // D110：明细行 + 行级库存快照/缺货标识（仓储出库确认页标红缺货行的数据来源）
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public SalesVO getById(Long id) {
        requireSalesReadAccess();
        BizSales entity = requireEntity(id);
        SalesVO vo = toVO(entity, resolveLatestApproval(entity.getId()));
        // 仓储视角不可查看被驳回退回销售人员的偏离单（与列表过滤一致，防直调详情绕过）
        if (isWarehouseView() && isPriceDeviationRejected(vo)) {
            throw BusinessException.notFound("销售单不存在或已退回销售人员处理");
        }
        fillDetails(List.of(vo));
        return vo;
    }

    /**
     * D110：批量填充明细行（一次 in 查询）+ 行级库存快照/缺货标识 + 头汇总（均价/商品汇总描述）。
     */
    private void fillDetails(List<SalesVO> records) {
        if (records.isEmpty()) {
            return;
        }
        List<Long> salesIds = records.stream().map(SalesVO::getId).toList();
        LambdaQueryWrapper<BizSalesDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.in(BizSalesDetail::getSalesId, salesIds)
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId);
        List<BizSalesDetail> details = bizSalesDetailMapper.selectList(detailWrapper);
        List<Long> goodsIds = details.stream().map(BizSalesDetail::getGoodsId)
                .filter(Objects::nonNull).distinct().toList();
        Map<Long, Integer> stockMap = goodsIds.isEmpty() ? Map.of() : baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g.getStock() == null ? 0 : g.getStock()));
        // D112：一次 in 查询行成品的有效 BOM 存在性（详情/列表行「无 BOM」标记）
        Set<Long> bomGoodsIds = bomGoodsIds(goodsIds);
        Map<Long, List<BizSalesDetail>> bySales = details.stream()
                .collect(Collectors.groupingBy(BizSalesDetail::getSalesId));
        for (SalesVO vo : records) {
            List<SalesDetailVO> lineVOs = bySales.getOrDefault(vo.getId(), List.of()).stream()
                    .map(d -> toDetailVO(d, stockMap.getOrDefault(d.getGoodsId(), 0),
                            bomGoodsIds.contains(d.getGoodsId())))
                    .toList();
            vo.setDetails(lineVOs);
            vo.setGoodsSummary(buildGoodsSummary(lineVOs));
            vo.setAvgPrice(averagePrice(vo.getTotalAmount(), vo.getTotalQuantity()));
        }
    }

    /**
     * D112：批量查一批成品中已建档有效 BOM 的成品 id 集合（一次 in 查询，避免逐行 N+1）。
     */
    private Set<Long> bomGoodsIds(Collection<Long> goodsIds) {
        if (goodsIds == null || goodsIds.isEmpty()) {
            return Set.of();
        }
        return bizBomMapper.selectList(new LambdaQueryWrapper<BizBom>().in(BizBom::getGoodsId, goodsIds))
                .stream().map(BizBom::getGoodsId).collect(Collectors.toSet());
    }

    private SalesDetailVO toDetailVO(BizSalesDetail d, int stock, boolean hasBom) {
        SalesDetailVO line = new SalesDetailVO();
        line.setId(d.getId());
        line.setSalesId(d.getSalesId());
        line.setGoodsId(d.getGoodsId());
        line.setGoodsName(d.getGoodsName());
        line.setQuantity(d.getQuantity());
        line.setUnitPrice(d.getUnitPrice());
        line.setTotalPrice(d.getTotalPrice());
        line.setSortNo(d.getSortNo());
        line.setStock(stock);
        line.setShortage(stock < d.getQuantity());
        line.setZeroStock(stock == 0);
        line.setHasBom(hasBom);
        line.setTerminateStatus(d.getTerminateStatus());
        line.setTerminateReason(d.getTerminateReason());
        line.setTerminateTime(d.getTerminateTime());
        line.setTerminatorName(d.getTerminatorName());
        return line;
    }

    /** 商品汇总描述：单行显示品名，多行显示「首品名 等 N 种」（D110 决策） */
    private String buildGoodsSummary(List<SalesDetailVO> lines) {
        if (lines == null || lines.isEmpty()) {
            return "-";
        }
        String firstName = lines.get(0).getGoodsName();
        if (lines.size() == 1) {
            return firstName;
        }
        return firstName + " 等 " + lines.size() + " 种";
    }

    private BigDecimal averagePrice(BigDecimal totalAmount, Integer totalQuantity) {
        if (totalAmount == null || totalQuantity == null || totalQuantity <= 0) {
            return null;
        }
        return totalAmount.divide(BigDecimal.valueOf(totalQuantity), 2, RoundingMode.HALF_UP);
    }

    /**
     * 仓储视角：当前用户为仓储部门成员（admin/employee）。超管不调销售列表，故无需特殊处理。
     */
    private boolean isWarehouseView() {
        return authzService.isDeptMember(AuthzService.DEPT_WAREHOUSE);
    }

    /**
     * 最新审批为价格偏离审批且被驳回（approvalStatus=3）-> 该单已退回销售人员，仓储不应见。
     */
    private boolean isPriceDeviationRejected(SalesVO vo) {
        return vo.getApprovalStatus() != null
                && Integer.valueOf(3).equals(vo.getApprovalStatus())
                && PRICE_DEVIATION_APPROVAL_ACTION.equals(vo.getApprovalRequestAction());
    }

    /**
     * D110：退货来源销售单选项——按来源销售单分组，行级可退量=原行量-该行被有效退货累计。
     * 有效退货 = 退货单正常（biz_status=1）且已确认入库（confirm_status=2）。
     */
    public List<SalesSourceOptionVO> returnableOptions(Long goodsId) {
        requireSalesModuleAccess();
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSales::getBizStatus, 1)
                .eq(BizSales::getConfirmStatus, CONFIRM_SHIPPED)
                .orderByDesc(BizSales::getOperationTime)
                .orderByDesc(BizSales::getId);
        List<BizSales> salesList = bizSalesMapper.selectList(wrapper);
        if (salesList.isEmpty()) {
            return List.of();
        }

        List<Long> salesIds = salesList.stream().map(BizSales::getId).toList();
        LambdaQueryWrapper<BizSalesDetail> lineWrapper = new LambdaQueryWrapper<>();
        lineWrapper.in(BizSalesDetail::getSalesId, salesIds)
                .eq(goodsId != null, BizSalesDetail::getGoodsId, goodsId)
                .ne(BizSalesDetail::getTerminateStatus, BizSalesDetail.TERMINATE_TERMINATED) // 需求一：已终止行不可退货
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId);
        List<BizSalesDetail> lines = bizSalesDetailMapper.selectList(lineWrapper);
        if (lines.isEmpty()) {
            return List.of();
        }

        Map<Long, Integer> returnedMap = salesReturnService.returnedQtyBySourceDetail(
                lines.stream().map(BizSalesDetail::getId).toList());
        Map<Long, List<BizSalesDetail>> linesBySales = lines.stream()
                .collect(Collectors.groupingBy(BizSalesDetail::getSalesId));

        return salesList.stream()
                .map(head -> {
                    List<SalesSourceOptionLineVO> optionLines = linesBySales
                            .getOrDefault(head.getId(), List.of()).stream()
                            .map(line -> {
                                int returnableQty = line.getQuantity() - returnedMap.getOrDefault(line.getId(), 0);
                                if (returnableQty <= 0) {
                                    return null;
                                }
                                SalesSourceOptionLineVO vo = new SalesSourceOptionLineVO();
                                vo.setSalesDetailId(line.getId());
                                vo.setGoodsId(line.getGoodsId());
                                vo.setGoodsName(line.getGoodsName());
                                vo.setQuantity(line.getQuantity());
                                vo.setUnitPrice(line.getUnitPrice());
                                vo.setReturnedQuantity(returnedMap.getOrDefault(line.getId(), 0));
                                vo.setReturnableQuantity(returnableQty);
                                return vo;
                            })
                            .filter(Objects::nonNull)
                            .toList();
                    if (optionLines.isEmpty()) {
                        return null;
                    }
                    SalesSourceOptionVO vo = new SalesSourceOptionVO();
                    vo.setId(head.getId());
                    vo.setSalesNo(head.getSalesNo());
                    vo.setCustomerName(head.getCustomerName());
                    vo.setCustomerContactName(head.getCustomerContactName()); // D128 退货带出
                    vo.setCustomerPhone(head.getCustomerPhone());
                    vo.setOperationTime(head.getOperationTime());
                    vo.setLines(optionLines);
                    return vo;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * D70：生产建单关联销售单下拉——该成品「正常且待出库」的销售单（生产/销售/仓储可读）。
     * D110：明细行锚定——头单正常+待出库且存在该成品的明细行；顶层 quantity 取该行数量
     * （同一成品一单只允许一行，(头单,成品) 唯一解析），前端仍只传头单 id，零改动复用。
     */
    public List<SalesSourceOptionVO> linkableOptions(Long goodsId) {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产/销售/仓储部门可访问", AuthzService.DEPT_PRODUCTION, AuthzService.DEPT_SALES, AuthzService.DEPT_WAREHOUSE);
        if (goodsId == null) {
            return List.of();
        }
        LambdaQueryWrapper<BizSales> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizSales::getBizStatus, 1)
                .eq(BizSales::getConfirmStatus, CONFIRM_PENDING)
                .apply("EXISTS (SELECT 1 FROM biz_sales_detail d WHERE d.sales_id = biz_sales.id AND d.is_deleted = 0"
                        + " AND d.goods_id = {0} AND d.terminate_status != 2)", goodsId)
                .orderByDesc(BizSales::getOperationTime)
                .orderByDesc(BizSales::getId)
                .last("LIMIT 50");
        List<BizSales> salesList = bizSalesMapper.selectList(wrapper);
        if (salesList.isEmpty()) {
            return List.of();
        }
        List<Long> salesIds = salesList.stream().map(BizSales::getId).toList();
        Map<Long, BizSalesDetail> lineBySales = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                        .in(BizSalesDetail::getSalesId, salesIds)
                        .eq(BizSalesDetail::getGoodsId, goodsId)
                        .ne(BizSalesDetail::getTerminateStatus, BizSalesDetail.TERMINATE_TERMINATED) // 需求一：已终止行不可再锚定生产
                        .orderByAsc(BizSalesDetail::getSortNo)
                        .orderByAsc(BizSalesDetail::getId)).stream()
                .collect(Collectors.toMap(BizSalesDetail::getSalesId, d -> d, (a, b) -> a));
        return salesList.stream()
                .map(item -> {
                    BizSalesDetail line = lineBySales.get(item.getId());
                    if (line == null) {
                        return null;
                    }
                    SalesSourceOptionVO vo = new SalesSourceOptionVO();
                    vo.setId(item.getId());
                    vo.setSalesNo(item.getSalesNo());
                    vo.setCustomerName(item.getCustomerName());
                    vo.setQuantity(line.getQuantity());
                    vo.setOperationTime(item.getOperationTime());
                    return vo;
                })
                .filter(Objects::nonNull)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public void create(SalesSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSalesModuleAccess();
        if (dto.getItems() == null || dto.getItems().isEmpty()) {
            throw BusinessException.validateFail("销售明细不能为空");
        }
        // D110 决策①：同一成品一单只允许一行（保证生产联动可由(头单,成品)唯一解析）
        Set<Long> goodsIds = new HashSet<>();
        for (SalesSaveDTO.Item item : dto.getItems()) {
            if (item.getGoodsId() == null || !goodsIds.add(item.getGoodsId())) {
                throw BusinessException.validateFail("同一商品在一张销售单中只能有一行，请合并数量");
            }
        }

        // D106：销售日期 = 开单时间自动生成（不接受客户端传入，不可补录；「出库日期」标签废除）
        LocalDateTime operationTime = LocalDateTime.now();
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        // 逐行校验+构建明细行（先全量校验再落库，避免半张单）
        Map<Long, BaseGoods> goodsMap = baseGoodsMapper.selectBatchIds(goodsIds).stream()
                .collect(Collectors.toMap(BaseGoods::getId, g -> g));
        List<BizSalesDetail> detailEntities = new ArrayList<>();
        int totalQuantity = 0;
        BigDecimal totalAmount = BigDecimal.ZERO;
        int sortNo = 0;
        // review 补强：最近有效进价一次批量预取（口径同 latestValidUnitPrice），避免逐行单查
        Map<Long, BigDecimal> recentPurchasePrices = bizPurchaseMapper.latestValidUnitPrices(goodsIds, operationTime).stream()
                .collect(Collectors.toMap(BizPurchaseMapper.LatestPurchasePrice::getGoodsId,
                        BizPurchaseMapper.LatestPurchasePrice::getUnitPrice));
        for (SalesSaveDTO.Item item : dto.getItems()) {
            sortNo++;
            BaseGoods goods = goodsMap.get(item.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail("商品不存在");
            }
            ensureGoodsEnabled(goods);
            GoodsService.ensureGoodsType(goods, GoodsService.GOODS_TYPE_PRODUCT, "销售单只可选择成品（type=product）"); // D67
            BigDecimal unitPrice = resolveUnitPrice(item.getUnitPrice(), goods.getSalePrice(),
                    "商品「" + goods.getGoodsName() + "」售价为空，请传入销售单价");
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(item.getQuantity()));
            CostSnapshot costSnapshot = buildSalesCostSnapshot(
                    recentPurchasePrices.get(goods.getId()), goods.getPurchasePrice());

            BizSalesDetail detail = new BizSalesDetail();
            detail.setGoodsId(goods.getId());
            detail.setGoodsName(goods.getGoodsName());
            detail.setQuantity(item.getQuantity());
            detail.setUnitPrice(unitPrice);
            detail.setCostUnitPrice(costSnapshot.unitPrice());
            detail.setCostTotalPrice(costSnapshot.unitPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
            detail.setCostSource(costSnapshot.source());
            detail.setTotalPrice(lineTotal);
            detail.setSortNo(sortNo);
            detailEntities.add(detail);

            totalQuantity += item.getQuantity();
            totalAmount = totalAmount.add(lineTotal);
        }

        BizSales entity = new BizSales();
        entity.setSalesNo(CodeGenerator.salesNo());
        entity.setTotalQuantity(totalQuantity);
        entity.setTotalAmount(totalAmount);
        entity.setOperatorId(loginUser.getId());
        entity.setOperatorName(loginUser.getRealName());
        entity.setOperationTime(operationTime);
        entity.setRemark(dto.getRemark());
        entity.setBizStatus(1);
        entity.setConfirmStatus(CONFIRM_PENDING);
        entity.setCustomerName(dto.getCustomerName());
        entity.setContractNo(dto.getContractNo());
        entity.setCustomerContactName(dto.getCustomerContactName()); // D128 选填透传
        entity.setCustomerPhone(dto.getCustomerPhone());
        entity.setTaxIncluded(dto.getTaxIncluded());

        bizSalesMapper.insert(entity);
        for (BizSalesDetail detail : detailEntities) {
            detail.setSalesId(entity.getId());
            bizSalesDetailMapper.insert(detail);
        }

        // 价格偏离探测（D29/D30，D110 决策④整单一笔）：任一行偏离即建一张审批单，request_reason 列出偏离行
        createPriceDeviationApproval(entity, detailEntities, goodsMap, loginUser.getRealName(), loginUser.getRole());

        // 销售下单后不立即扣库存，待仓库管理员确认出库时再按行扣减；同时通知仓储管理员有待确认单据
        messageService.sendSalesPendingConfirmToWarehouseAdmins(
                entity.getSalesNo(), entity.getCustomerName(), loginUser.getRealName(), entity.getId());

        // D70/D110 决策⑤：缺货行按单汇总一条消息通知生产管理员（不再逐行刷屏）
        // D112：无 BOM 缺货行追加建档指引（新成品照常下单，生产端有明确指引）
        Set<Long> bomGoodsIds = bomGoodsIds(goodsMap.keySet());
        List<String> shortageDescs = new ArrayList<>();
        for (BizSalesDetail detail : detailEntities) {
            BaseGoods goods = goodsMap.get(detail.getGoodsId());
            int available = goods.getStock() == null ? 0 : goods.getStock();
            if (detail.getQuantity() > available) {
                String desc = detail.getGoodsName() + "×" + detail.getQuantity() + "（现存 " + available + "）";
                if (!bomGoodsIds.contains(detail.getGoodsId())) {
                    desc += "（未建档 BOM，需先在 BOM 管理建档）";
                }
                shortageDescs.add(desc);
            }
        }
        if (!shortageDescs.isEmpty()) {
            messageService.sendSalesDemandToProductionAdmins(
                    entity.getSalesNo(), String.join("、", shortageDescs),
                    entity.getCustomerName(), loginUser.getRealName(), entity.getId());
        }
    }

    /**
     * D110 决策④：逐行对照标准售价判偏离，返回「第N行 品名 本次X/标准Y 偏离Z%」描述列表（会话68/D137 带价格）。
     */
    private List<String> buildDeviationDescs(List<BizSalesDetail> details, Map<Long, BaseGoods> goodsMap) {
        List<String> descs = new ArrayList<>();
        int lineNo = 0;
        for (BizSalesDetail detail : details) {
            lineNo++;
            BaseGoods goods = goodsMap.get(detail.getGoodsId());
            if (isPriceDeviated(detail.getUnitPrice(), goods.getSalePrice())) {
                descs.add("第" + lineNo + "行 " + detail.getGoodsName()
                        + " 本次" + detail.getUnitPrice().setScale(2, RoundingMode.HALF_UP)
                        + "/标准" + goods.getSalePrice().setScale(2, RoundingMode.HALF_UP)
                        + " 偏离 " + deviationRatio(detail.getUnitPrice(), goods.getSalePrice())
                        .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP) + "%");
            }
        }
        return descs;
    }

    private BigDecimal deviationRatio(BigDecimal unitPrice, BigDecimal standardSalePrice) {
        return unitPrice.subtract(standardSalePrice).abs()
                .divide(standardSalePrice, 4, RoundingMode.HALF_UP);
    }

    /**
     * 仓储管理员确认销售单出库（D110 决策②整单一次确认）：逐行条件扣库存，
     * 任一行不足整单失败回滚并提示缺货行；不出现部分出库。
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirm(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSalesVoidExecutionAccess();
        BizSales entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "销售单");
        ensureNoPendingVoidApproval(id, "销售单");
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("销售单已确认出库，禁止重复确认");
        }

        // 价格偏离前置门闸（D29/D110 决策④）：偏离订单需存在已通过的价格偏离审批单（整单级）
        ensurePriceDeviationApproved(entity);

        List<BizSalesDetail> details = requireDetails(entity.getId());
        for (BizSalesDetail detail : details) {
            if (isLineTerminated(detail)) {
                continue; // 需求一 Q22：已终止行不发货不扣库存，整单确认只发活跃行
            }
            decreaseStock(detail.getGoodsId(), detail.getQuantity(),
                    "库存不足，确认出库失败（" + detail.getGoodsName() + " 需 " + detail.getQuantity() + "）");
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizSales> updateWrapper = new LambdaUpdateWrapper<>();
        updateWrapper.eq(BizSales::getId, entity.getId())
                .eq(BizSales::getConfirmStatus, CONFIRM_PENDING)
                .set(BizSales::getConfirmStatus, CONFIRM_SHIPPED)
                .set(BizSales::getConfirmTime, now)
                .set(BizSales::getConfirmerId, loginUser.getId())
                .set(BizSales::getConfirmerName, loginUser.getRealName());
        int rows = bizSalesMapper.update(null, updateWrapper);
        if (rows != 1) {
            // 库存已扣减但状态更新失败时由事务回滚保护
            throw BusinessException.validateFail("销售单已被处理，禁止重复确认");
        }

        // 仓储已确认出库，撤销该单未读待确认消息
        messageService.revokeUnreadByBiz("sales", id);
        // D127：出库完成后提醒销售部门管理员（先撤后发——新提醒与被撤消息同 biz 绑定，顺序颠倒会被本次撤销误撤）
        messageService.sendSalesShippedToSalesAdmins(entity.getSalesNo(), entity.getCustomerName(),
                loginUser.getRealName(), id);
    }

    /**
     * 需求一（行级终止，ADR-0019）：销售单明细行终止——客户取消后的正常业务闭环（第三条取消通道），免审批立即生效。
     * 权限（Q3/Q23）：销售管理员可终止本部门所有单，销售员工仅可终止本人所建单。
     * 仅限未确认出库的有效单（Q4）；存在待审作废申请时拦截（Q18）。逐行条件更新 1→2 留痕（原因必填/时间/操作人，Q22），
     * 每行通知锚定的未完结生产任务单（Q1a/Q20a 冻结+人工闭环），表头汇总重算只计活跃行（Q22-1），
     * 全部行终止 → 头单派生 biz_status=4 已终止并撤未读待确认消息。
     */
    @Transactional(rollbackFor = Exception.class)
    public void terminate(Long id, SalesTerminateDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSalesModuleAccess();
        BizSales entity = requireEntity(id);
        ensureCanTerminateSales(entity);
        ensureNormalStatus(entity.getBizStatus(), "销售单");
        ensureNoPendingVoidApproval(id, "销售单");
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("销售单已确认出库，不可终止；客户取消已发货部分请走销售退货");
        }
        if (dto == null || dto.getLines() == null || dto.getLines().isEmpty()) {
            throw BusinessException.validateFail("请选择要终止的明细行");
        }

        // 先全量校验再落库（行归属 + 未终止），避免半张单
        List<BizSalesDetail> details = requireDetails(id);
        Map<Long, BizSalesDetail> detailMap = details.stream()
                .collect(Collectors.toMap(BizSalesDetail::getId, d -> d));
        for (SalesTerminateDTO.TerminateLine line : dto.getLines()) {
            BizSalesDetail detail = detailMap.get(line.getDetailId());
            if (detail == null) {
                throw BusinessException.validateFail("明细行不存在或不属于该销售单");
            }
            if (isLineTerminated(detail)) {
                throw BusinessException.validateFail("明细行「" + detail.getGoodsName() + "」已终止，禁止重复终止");
            }
        }

        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        LocalDateTime now = LocalDateTime.now();
        for (SalesTerminateDTO.TerminateLine line : dto.getLines()) {
            LambdaUpdateWrapper<BizSalesDetail> uw = new LambdaUpdateWrapper<>();
            uw.eq(BizSalesDetail::getId, line.getDetailId())
                    .eq(BizSalesDetail::getTerminateStatus, BizSalesDetail.TERMINATE_NORMAL)
                    .set(BizSalesDetail::getTerminateStatus, BizSalesDetail.TERMINATE_TERMINATED)
                    .set(BizSalesDetail::getTerminateReason, line.getReason().trim())
                    .set(BizSalesDetail::getTerminateTime, now)
                    .set(BizSalesDetail::getTerminatorId, loginUser.getId())
                    .set(BizSalesDetail::getTerminatorName, loginUser.getRealName());
            if (bizSalesDetailMapper.update(null, uw) != 1) {
                throw BusinessException.validateFail("明细行已被处理，禁止重复终止");
            }
        }

        // Q1a/Q20a：逐行通知锚定的未完结生产任务单（通知+冻结，生产侧人工终止闭环，不越权自动终止）
        for (SalesTerminateDTO.TerminateLine line : dto.getLines()) {
            BizSalesDetail detail = detailMap.get(line.getDetailId());
            notifyLineTerminatedToProduction(entity, detail, line.getReason().trim());
            // ADR-0020/D116：行终止后若该行成品已生产入库未出库 → 通知仓储处置（保留成品/发起拆分）
            splitOrderService.handleLineTerminated(entity, detail);
        }

        // Q22-1：表头汇总重算只计活跃行（已终止行保留可见，不进汇总/统计/确认出库）
        List<BizSalesDetail> freshDetails = requireDetails(id);
        int activeQuantity = 0;
        BigDecimal activeAmount = BigDecimal.ZERO;
        for (BizSalesDetail d : freshDetails) {
            if (isLineTerminated(d)) {
                continue;
            }
            activeQuantity += d.getQuantity();
            activeAmount = activeAmount.add(d.getTotalPrice());
        }
        LambdaUpdateWrapper<BizSales> headWrapper = new LambdaUpdateWrapper<>();
        headWrapper.eq(BizSales::getId, entity.getId())
                .set(BizSales::getTotalQuantity, activeQuantity)
                .set(BizSales::getTotalAmount, activeAmount);
        bizSalesMapper.update(null, headWrapper);

        // F6/code-review：价格偏离审批基于终止前金额发起；部分终止减量后整单金额已变，
        // 审批继续走会按过时金额放行——部分/全部终止都撤销（幂等：仅 status 1→3 条件更新，无待撤时 0 行）
        revokePriceDeviationApprovals(id);

        // Q21：全部行终止 → 头单派生 biz_status=4 已终止（撤未读待确认消息）
        boolean allTerminated = freshDetails.stream().allMatch(this::isLineTerminated);
        if (allTerminated) {
            LambdaUpdateWrapper<BizSales> statusWrapper = new LambdaUpdateWrapper<>();
            statusWrapper.eq(BizSales::getId, entity.getId())
                    .eq(BizSales::getBizStatus, BizSales.BIZ_STATUS_NORMAL)
                    .set(BizSales::getBizStatus, BizSales.BIZ_STATUS_TERMINATED);
            if (bizSalesMapper.update(null, statusWrapper) != 1) {
                throw BusinessException.validateFail("销售单已被处理，禁止终止");
            }
            messageService.revokeUnreadByBiz("sales", id);
        }
    }

    /** 终止权限（Q3/Q23；D141 同权开放）：销售部门（admin+员工）可终止本部门所有单。 */
    private void ensureCanTerminateSales(BizSales entity) {
        if (authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)) {
            return;
        }
        throw BusinessException.forbidden("仅销售部门可终止销售单");
    }

    /** 需求一：明细行是否已终止（terminate_status=2） */
    private boolean isLineTerminated(BizSalesDetail detail) {
        return detail.getTerminateStatus() != null
                && detail.getTerminateStatus() == BizSalesDetail.TERMINATE_TERMINATED;
    }

    /**
     * 需求一：行终止后通知锚定的未完结生产任务单（salesDetailId 直连或 (头单,成品) 解析，ADR-0013）。
     */
    private void notifyLineTerminatedToProduction(BizSales entity, BizSalesDetail detail, String reason) {
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizProductionOrder::getSalesOrderId, entity.getId())
                .in(BizProductionOrder::getStatus, BizProductionOrder.UNFINISHED_STATUSES);
        List<BizProductionOrder> orders = bizProductionOrderMapper.selectList(wrapper);
        for (BizProductionOrder order : orders) {
            boolean anchored = (order.getSalesDetailId() != null && order.getSalesDetailId().equals(detail.getId()))
                    || (order.getSalesDetailId() == null && order.getGoodsId() != null
                        && order.getGoodsId().equals(detail.getGoodsId()));
            if (anchored) {
                messageService.sendSalesLineTerminatedToProductionAdmins(entity.getSalesNo(),
                        detail.getGoodsName() + "×" + detail.getQuantity(), reason,
                        order.getOrderNo(), order.getId());
            }
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        deleteInternal(id);
    }

    /** 手测问题 1（2026-09-23）：批量删除——守卫一次，逐行跑单删同款校验，尽力而为聚合明细 */
    @Transactional(rollbackFor = Exception.class)
    public BatchDeleteResultVO batchDelete(List<Long> ids) {
        authzService.requireNotSuperAdminForBusinessWrite();
        BatchDeleteResultVO result = new BatchDeleteResultVO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            try {
                deleteInternal(id);
                result.addSuccess();
            } catch (BusinessException e) {
                BizSales head = bizSalesMapper.selectById(id);
                result.addFailure(id, head != null ? head.getSalesNo() : String.valueOf(id), e.getMessage());
            }
        }
        return result;
    }

    private void deleteInternal(Long id) {
        BizSales entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "销售单");
        validateDeleteWindow(entity.getOperationTime(), "销售单");
        ensureCanDeleteSales(entity);
        // 仅已确认出库（已扣库存）的销售单删除时需按行回补库存；待确认单据尚未扣库存，直接删除
        // （需求一：已终止行从未出库——confirm 只扣活跃行，回补排除之防幽灵库存）
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() == CONFIRM_SHIPPED) {
            for (BizSalesDetail detail : requireDetails(entity.getId())) {
                if (isLineTerminated(detail)) {
                    continue;
                }
                increaseStock(detail.getGoodsId(), detail.getQuantity());
            }
        }
        // 撤销该单未读待确认消息（待确认单被删除后，仓储侧不再有悬挂通知）
        messageService.revokeUnreadByBiz("sales", id);
        // 撤销价格偏离审批待办（单据已删除，销售管理员不再需要审批）
        revokePriceDeviationApprovals(id);
        notifyLinkedProductionOrderIfUnfinished(entity, "删除");
        bizSalesMapper.deleteById(id);
        // review 补强：级联软删明细行——GoodsReferenceService 按明细行统计成品引用，
        // 头删行留会让该成品永久无法删除（对齐 PurchaseRequestService.delete 的级联口径）
        LambdaQueryWrapper<BizSalesDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.eq(BizSalesDetail::getSalesId, id);
        bizSalesDetailMapper.delete(detailWrapper);
    }

    /**
     * 删除权限（D141 同权开放）：销售部门（admin+员工）可删本部门当天未出库单（D32 原员工仅限本人单，现与管理员同口径）。
     * 员工不触碰库存：未出库单(confirm_status=1)未扣库存，删除仅撤销审批/消息。
     * D95：与进货/销退统一口径——已确认出库的单据不可无痕删除（admin/员工亦然），请走作废留痕+审批。
     */
    private void ensureCanDeleteSales(BizSales entity) {
        if (!authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)) {
            throw BusinessException.forbidden("仅销售部门可删除销售单");
        }
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() != CONFIRM_PENDING) {
            throw BusinessException.validateFail("已出库的销售单不可删除，请走作废流程");
        }
    }

    /**
     * D73：销售单删除/作废生效后，若存在关联的未终态生产任务单 → 通知生产管理员手动终止+退料。
     * 已完工/已作废/已报废/已终止的生产单不打扰（SQL 层 in 过滤）；关联保留，生产端列表标注"已取消"。
     * D110：消息中的成品描述按明细行汇总（PTO153×5、轴承×3）。
     */
    private void notifyLinkedProductionOrderIfUnfinished(BizSales entity, String cancelAction) {
        LambdaQueryWrapper<BizProductionOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizProductionOrder::getSalesOrderId, entity.getId())
                .in(BizProductionOrder::getStatus, BizProductionOrder.UNFINISHED_STATUSES);
        List<BizProductionOrder> orders = bizProductionOrderMapper.selectList(wrapper);
        if (orders.isEmpty()) {
            return;
        }
        String goodsDesc = describeLines(requireDetails(entity.getId()));
        for (BizProductionOrder order : orders) {
            messageService.sendSalesCancelledToProductionAdmins(
                    entity.getSalesNo(), goodsDesc, order.getOrderNo(), order.getId(), cancelAction);
        }
    }

    /** D110：行序列描述「PTO153×5、轴承×3」（跨部门消息汇总文案用） */
    private String describeLines(List<BizSalesDetail> details) {
        return details.stream()
                .map(d -> d.getGoodsName() + "×" + d.getQuantity())
                .collect(Collectors.joining("、"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void voidDocument(Long id, DocumentVoidDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSalesVoidExecutionAccess();
        BizSales entity = requireEntity(id);
        ensureNormalStatus(entity.getBizStatus(), "销售单");
        // D99：作废并冲抵（红冲）已停用——界面无入口（D74），此处封死 API 直废路径
        if (dto != null && Boolean.TRUE.equals(dto.getCreateRedFlush())) {
            throw BusinessException.validateFail("「作废并冲抵」已停用，请使用普通作废");
        }

        String reason = normalizeReason(dto == null ? null : dto.getReason());
        LocalDateTime now = LocalDateTime.now();
        LambdaUpdateWrapper<BizSales> voidWrapper = new LambdaUpdateWrapper<>();
        voidWrapper.eq(BizSales::getId, entity.getId())
                .eq(BizSales::getBizStatus, 1)
                .set(BizSales::getBizStatus, 2)
                .set(BizSales::getVoidTime, now)
                .set(BizSales::getVoidReason, reason);
        int rows = bizSalesMapper.update(null, voidWrapper);
        if (rows != 1) {
            throw BusinessException.validateFail("销售单已被处理，禁止重复作废");
        }

        // 作废后撤销该单未读待确认消息（单据已失效，仓储侧不再需要处理）
        messageService.revokeUnreadByBiz("sales", id);
        // 撤销价格偏离审批待办（单据已作废，销售管理员不再需要审批）
        revokePriceDeviationApprovals(id);

        // 仅已确认出库（已扣库存）的销售单作废时需按行回补库存；待确认单据尚未扣库存，不回补
        // D110 决策⑨：红冲死代码不再随头行改造移植（D99 已封死入口），直接移除
        // （需求一：已终止行从未出库——confirm 只扣活跃行，回补排除之防幽灵库存）
        if (entity.getConfirmStatus() != null && entity.getConfirmStatus() == CONFIRM_SHIPPED) {
            for (BizSalesDetail detail : requireDetails(entity.getId())) {
                if (isLineTerminated(detail)) {
                    continue;
                }
                increaseStock(detail.getGoodsId(), detail.getQuantity());
            }
        }
        // D73：作废生效后通知关联未终态生产任务单（库存回补之后）
        notifyLinkedProductionOrderIfUnfinished(entity, "作废");
    }

    /**
     * D141 同权开放：仓储部门（admin+员工）= 确认出库/作废执行权；销售部门（admin+员工）= 作废转审批发起权。
     * confirm（确认出库）与 voidDocument（作废执行）共用本守卫——仓储侧动作，销售侧调用即引导走审批。
     */
    private void requireSalesVoidExecutionAccess() {
        if (authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_WAREHOUSE)) {
            return;
        }
        if (authzService.hasDeptMemberOrSuperAdminAccess(AuthzService.DEPT_SALES)) {
            throw BusinessException.validateFail("历史销售单作废需提交仓储审批");
        }
        throw BusinessException.forbidden("仅销售部门可发起销售作废申请，且需由仓储部门审批");
    }

    private BizSales requireEntity(Long id) {
        BizSales entity = bizSalesMapper.selectById(id);
        if (entity == null) {
            throw BusinessException.notFound("销售单不存在");
        }
        return entity;
    }

    /** D110：加载销售单明细行（按 sort_no 稳定排序）；缺失视为数据异常拦截 */
    private List<BizSalesDetail> requireDetails(Long salesId) {
        List<BizSalesDetail> details = bizSalesDetailMapper.selectList(new LambdaQueryWrapper<BizSalesDetail>()
                .eq(BizSalesDetail::getSalesId, salesId)
                .orderByAsc(BizSalesDetail::getSortNo)
                .orderByAsc(BizSalesDetail::getId));
        if (details.isEmpty()) {
            throw BusinessException.validateFail("销售单缺少明细行，无法执行该操作");
        }
        return details;
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
            throw BusinessException.validateFail("仅允许删除当天" + docName + "，历史单据请走作废流程");
        }
    }

    private void ensureNormalStatus(Integer bizStatus, String docName) {
        if (bizStatus == null || bizStatus == 1) {
            return;
        }
        if (bizStatus == 2) {
            throw BusinessException.validateFail(docName + "已作废，禁止重复操作");
        }
        if (bizStatus == 4) {
            throw BusinessException.validateFail(docName + "已终止，禁止重复操作");
        }
        throw BusinessException.validateFail(docName + "为冲抵记录，禁止删除或再次作废");
    }

    // D97：作废审批中冻结主流程——存在待审批(1)/处理中(4)的作废类申请时禁止继续确认
    private void ensureNoPendingVoidApproval(Long bizId, String docName) {
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "sales")
                .eq(BizApprovalOrder::getBizId, bizId)
                .in(BizApprovalOrder::getStatus, 1, 4)
                .in(BizApprovalOrder::getRequestAction, "void", "void_red");
        if (bizApprovalOrderMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail(docName + "正在作废审批中，待仓储管理员处理后再操作");
        }
    }

    private String normalizeReason(String reason) {
        if (!StringUtils.hasText(reason)) {
            return "手工作废";
        }
        return reason.trim();
    }

    private String confirmStatusText(Integer confirmStatus) {
        if (confirmStatus == null) return null;
        return switch (confirmStatus) {
            case CONFIRM_PENDING -> "待仓库确认";
            case CONFIRM_SHIPPED -> "已确认出库";
            default -> String.valueOf(confirmStatus);
        };
    }

    private BigDecimal resolveUnitPrice(BigDecimal inputPrice, BigDecimal fallbackPrice, String emptyPriceMsg) {
        BigDecimal finalPrice = inputPrice == null ? fallbackPrice : inputPrice;
        if (finalPrice == null) {
            throw BusinessException.validateFail(emptyPriceMsg);
        }
        if (finalPrice.compareTo(BigDecimal.ZERO) <= 0) {
            throw BusinessException.validateFail("单价必须大于0");
        }
        return finalPrice;
    }

    private CostSnapshot buildSalesCostSnapshot(BigDecimal recentPurchasePrice, BigDecimal fallbackPurchasePrice) {
        if (recentPurchasePrice != null && recentPurchasePrice.compareTo(BigDecimal.ZERO) > 0) {
            return new CostSnapshot(recentPurchasePrice, "RECENT_PURCHASE");
        }
        if (fallbackPurchasePrice != null && fallbackPurchasePrice.compareTo(BigDecimal.ZERO) > 0) {
            return new CostSnapshot(fallbackPurchasePrice, "GOODS_PRICE");
        }
        return new CostSnapshot(BigDecimal.ZERO, "ZERO_FALLBACK");
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

    private Map<Long, BizApprovalOrder> buildLatestApprovalMap(List<Long> bizIds) {
        if (bizIds.isEmpty()) {
            return Map.of();
        }
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "sales")
                .in(BizApprovalOrder::getBizId, bizIds)
                .orderByDesc(BizApprovalOrder::getId);
        Map<Long, BizApprovalOrder> approvalMap = new LinkedHashMap<>();
        for (BizApprovalOrder item : bizApprovalOrderMapper.selectList(wrapper)) {
            approvalMap.putIfAbsent(item.getBizId(), item);
        }
        return approvalMap;
    }

    private BizApprovalOrder resolveLatestApproval(Long bizId) {
        return buildLatestApprovalMap(List.of(bizId)).get(bizId);
    }

    private SalesVO toVO(BizSales entity, BizApprovalOrder approvalOrder) {
        SalesVO vo = new SalesVO();
        BeanUtils.copyProperties(entity, vo);
        LocalDateTime bizTime = entity.getOperationTime() == null ? entity.getCreateTime() : entity.getOperationTime();
        vo.setOperationTime(bizTime);
        vo.setSalesDate(bizTime);
        vo.setOperator(entity.getOperatorName());
        vo.setBizStatus(entity.getBizStatus());
        vo.setSourceId(entity.getSourceId());
        vo.setVoidTime(entity.getVoidTime());
        vo.setVoidReason(entity.getVoidReason());
        vo.setConfirmStatus(entity.getConfirmStatus());
        vo.setConfirmStatusText(confirmStatusText(entity.getConfirmStatus()));
        vo.setApprovalStatus(approvalOrder == null ? null : approvalOrder.getStatus());
        vo.setApprovalRequestAction(approvalOrder == null ? null : approvalOrder.getRequestAction());
        vo.setApprovalRemark(approvalOrder == null ? null : approvalOrder.getApproveRemark());
        return vo;
    }

    private record CostSnapshot(BigDecimal unitPrice, String source) {
    }

    // ============================== 价格偏离审批（D29/D30，D110 决策④整单一笔） ==============================

    /**
     * 判断销售价是否偏离标准售价超阈值（阈值由销售管理员配置，D120，默认 5%）。
     * 偏离比例 = |unitPrice - salePrice| / salePrice，salePrice 为空或 <=0 时视为无法判定，不触发。
     */
    private boolean isPriceDeviated(BigDecimal unitPrice, BigDecimal standardSalePrice) {
        if (standardSalePrice == null || standardSalePrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (unitPrice == null || unitPrice.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        return deviationRatio(unitPrice, standardSalePrice).compareTo(sysConfigService.getPriceDeviationThreshold()) > 0;
    }

    /**
     * 建价格偏离审批单（biz_type=sales, action=price_deviation_confirm, status=pending；整单一笔）。
     * 无偏离行时不建单不发消息。request_reason 列出偏离行（行号+品名+本次/标准价+偏离%），
     * 拼接超 200 字符（request_reason 列宽）时退化为行数汇总——完整价格对照在 request_detail
     * 快照 JSON（会话68/D137）。request_detail 存建单时点偏离行价格快照；销售管理员一次批准/驳回整单（D120）。
     */
    private void createPriceDeviationApproval(BizSales entity, List<BizSalesDetail> details, Map<Long, BaseGoods> goodsMap, String operatorName, String operatorRole) {
        List<String> deviationDescs = buildDeviationDescs(details, goodsMap);
        if (deviationDescs.isEmpty()) {
            return;
        }
        BizApprovalOrder approval = new BizApprovalOrder();
        approval.setApprovalNo(CodeGenerator.approvalNo());
        approval.setBizType("sales");
        approval.setBizId(entity.getId());
        approval.setBizNo(entity.getSalesNo());
        approval.setRequestAction(PRICE_DEVIATION_APPROVAL_ACTION);
        BigDecimal thresholdPct = sysConfigService.getPriceDeviationThreshold().multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP);
        String reason = "销售价偏离标准售价（" + String.join("；", deviationDescs) + "），超 " + thresholdPct + "% 阈值，需销售管理员审批";
        if (reason.length() > 200) {
            // request_reason 列宽 200：多偏离行带价格超长时退化为行数汇总，完整对照见 request_detail 快照
            reason = "销售价偏离标准售价（共 " + deviationDescs.size() + " 行），超 " + thresholdPct + "% 阈值，需销售管理员审批";
        }
        approval.setRequestReason(reason);
        approval.setRequestDetail(buildDeviationSnapshot(details, goodsMap, thresholdPct));
        approval.setBeforeBizStatus(entity.getBizStatus());
        approval.setAfterBizStatus(entity.getBizStatus());
        approval.setStatus(1);
        approval.setRequesterId(entity.getOperatorId());
        approval.setRequesterName(operatorName);
        approval.setRequesterRole(operatorRole == null ? "" : operatorRole.trim().toLowerCase(Locale.ROOT));
        try {
            bizApprovalOrderMapper.insert(approval);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // pending 唯一约束：已存在待审批，忽略（避免重复建单时报错）
            return;
        }
        messageService.sendPriceDeviationToSalesAdmin(entity.getSalesNo(), operatorName, String.join("；", deviationDescs), entity.getId(), approval.getId());
    }

    /**
     * 建单时点偏离行价格快照 JSON（会话68/D137）：{thresholdPercent, rows:[lineNo/goodsName/quantity/
     * unitPrice/standardSalePrice/deviationAmount/deviationPercent]}，deviationAmount/Percent 带符号。
     */
    private String buildDeviationSnapshot(List<BizSalesDetail> details, Map<Long, BaseGoods> goodsMap, BigDecimal thresholdPct) {
        List<Map<String, Object>> rows = new ArrayList<>();
        int lineNo = 0;
        for (BizSalesDetail detail : details) {
            lineNo++;
            BaseGoods goods = goodsMap.get(detail.getGoodsId());
            if (!isPriceDeviated(detail.getUnitPrice(), goods.getSalePrice())) {
                continue;
            }
            BigDecimal deviationAmount = detail.getUnitPrice().subtract(goods.getSalePrice());
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("lineNo", lineNo);
            row.put("goodsName", detail.getGoodsName());
            row.put("quantity", detail.getQuantity());
            row.put("unitPrice", detail.getUnitPrice().setScale(2, RoundingMode.HALF_UP));
            row.put("standardSalePrice", goods.getSalePrice().setScale(2, RoundingMode.HALF_UP));
            row.put("deviationAmount", deviationAmount.setScale(2, RoundingMode.HALF_UP));
            row.put("deviationPercent", deviationAmount.abs()
                    .divide(goods.getSalePrice(), 4, RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100)).setScale(0, RoundingMode.HALF_UP));
            rows.add(row);
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("thresholdPercent", thresholdPct);
        snapshot.put("rows", rows);
        return JSONUtil.toJsonStr(snapshot);
    }

    /**
     * confirm 前置校验：偏离订单必须存在已通过（status=2）的价格偏离审批单（整单级），否则拦截。
     * 非偏离订单（无偏离审批单记录）直接放行。
     */
    private void ensurePriceDeviationApproved(BizSales entity) {
        LambdaQueryWrapper<BizApprovalOrder> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "sales")
                .eq(BizApprovalOrder::getBizId, entity.getId())
                .eq(BizApprovalOrder::getRequestAction, PRICE_DEVIATION_APPROVAL_ACTION)
                .orderByDesc(BizApprovalOrder::getId);
        List<BizApprovalOrder> approvals = bizApprovalOrderMapper.selectList(wrapper);
        if (approvals.isEmpty()) {
            return; // 非偏离订单，无审批记录，放行
        }
        boolean anyApproved = approvals.stream().anyMatch(a -> Integer.valueOf(2).equals(a.getStatus()));
        if (!anyApproved) {
            throw BusinessException.validateFail("销售价偏离标准售价，需销售管理员审批通过后仓储方可确认出库");
        }
    }

    /**
     * 删除/作废销售单时撤销其 pending 的价格偏离审批单（避免悬挂审批）。
     */
    private void revokePriceDeviationApprovals(Long salesId) {
        LambdaUpdateWrapper<BizApprovalOrder> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BizApprovalOrder::getBizType, "sales")
                .eq(BizApprovalOrder::getBizId, salesId)
                .eq(BizApprovalOrder::getRequestAction, PRICE_DEVIATION_APPROVAL_ACTION)
                .eq(BizApprovalOrder::getStatus, 1)
                .set(BizApprovalOrder::getStatus, 3)
                .set(BizApprovalOrder::getApproveRemark, "销售单已删除/作废，审批自动撤销")
                .set(BizApprovalOrder::getRejectedAt, LocalDateTime.now());
        bizApprovalOrderMapper.update(null, wrapper);
    }
}
