package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.StocktakeCancelDTO;
import org.example.back.dto.StocktakeCreateDTO;
import org.example.back.dto.StocktakeEntryDTO;
import org.example.back.dto.StocktakeQueryDTO;
import org.example.back.dto.StocktakeRejectDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizStocktake;
import org.example.back.entity.BizStocktakeDetail;
import org.example.back.entity.SysDept;
import org.example.back.entity.SysUser;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizStocktakeDetailMapper;
import org.example.back.mapper.BizStocktakeMapper;
import org.example.back.mapper.SysDeptMapper;
import org.example.back.mapper.SysUserMapper;
import org.example.back.vo.StocktakeAssigneeOptionVO;
import org.example.back.vo.StocktakeDetailVO;
import org.example.back.vo.StocktakeGoodsOptionVO;
import org.example.back.vo.StocktakeVO;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 库存盘点（阶段 23，ADR-0010）：
 * 四态 1-盘点中/2-待审核/3-已完成/4-已取消；差异=实盘-生效时点实时账面（D82）；
 * 无删除仅取消（D83）；盲盘导出+xlsx 回填（D84）。
 */
@Service
public class StocktakeService {

    private static final int STATUS_COUNTING = 1;
    private static final int STATUS_PENDING_REVIEW = 2;
    private static final int STATUS_COMPLETED = 3;
    private static final int STATUS_CANCELED = 4;

    @Autowired
    private BizStocktakeMapper bizStocktakeMapper;
    @Autowired
    private BizStocktakeDetailMapper bizStocktakeDetailMapper;
    @Autowired
    private BaseGoodsMapper baseGoodsMapper;
    @Autowired
    private AuthService authService;
    @Autowired
    private AuthzService authzService;
    @Autowired
    private SysUserMapper sysUserMapper;
    @Autowired
    private SysDeptMapper sysDeptMapper;

    // ---------- 读 ----------

    public PageResult<StocktakeVO> page(StocktakeQueryDTO queryDTO) {
        requireReadAccess();
        LambdaQueryWrapper<BizStocktake> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(queryDTO.getStatus() != null, BizStocktake::getStatus, queryDTO.getStatus())
                .like(StringUtils.hasText(queryDTO.getStocktakeNo()),
                        BizStocktake::getStocktakeNo, queryDTO.getStocktakeNo())
                .orderByDesc(BizStocktake::getCreateTime);
        Page<BizStocktake> page = bizStocktakeMapper.selectPage(
                new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<StocktakeVO> vos = page.getRecords().stream().map(o -> {
            StocktakeVO vo = toVO(o);
            fillSummary(vo, listDetails(o.getId()));
            return vo;
        }).collect(Collectors.toList());
        Page<StocktakeVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(vos);
        return PageResult.of(voPage);
    }

    public StocktakeVO getById(Long id) {
        requireReadAccess();
        BizStocktake order = requireEntity(id);
        StocktakeVO vo = toVO(order);
        List<BizStocktakeDetail> details = listDetails(id);
        vo.setDetailList(details.stream().map(this::toDetailVO).collect(Collectors.toList()));
        fillSummary(vo, details);
        return vo;
    }

    /**
     * 建单勾选列表：全部商品（可按类型过滤）+ 上次盘点时间（派生）+ 是否在未完结单（D81）
     */
    public List<StocktakeGoodsOptionVO> goodsOptions(String type) {
        requireReadAccess();
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(StringUtils.hasText(type), BaseGoods::getType, type)
                .orderByAsc(BaseGoods::getId);
        List<BaseGoods> goodsList = baseGoodsMapper.selectList(wrapper);

        Map<Long, LocalDateTime> lastTimeMap = new HashMap<>();
        for (Map<String, Object> row : bizStocktakeMapper.selectLastStocktakeTimes()) {
            Object gid = row.get("goodsId");
            Object lt = row.get("lastTime");
            if (gid == null || lt == null) {
                continue;
            }
            LocalDateTime time = lt instanceof Timestamp t ? t.toLocalDateTime() : (LocalDateTime) lt;
            lastTimeMap.put(((Number) gid).longValue(), time);
        }

        Set<Long> openGoodsIds = findOpenStocktakeGoodsIds();

        return goodsList.stream().map(g -> {
            StocktakeGoodsOptionVO vo = new StocktakeGoodsOptionVO();
            vo.setGoodsId(g.getId());
            vo.setGoodsCode(g.getGoodsCode());
            vo.setGoodsName(g.getGoodsName());
            vo.setType(g.getType());
            vo.setSpec(g.getSpec());
            vo.setMaterial(g.getMaterial());
            vo.setUnit(g.getUnit());
            vo.setStock(g.getStock());
            vo.setLastStocktakeTime(lastTimeMap.get(g.getId()));
            vo.setInOpenStocktake(openGoodsIds.contains(g.getId()));
            return vo;
        }).collect(Collectors.toList());
    }

    // ---------- create ----------

    @Transactional(rollbackFor = Exception.class)
    public Long create(StocktakeCreateDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可创建盘点单");
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        List<Long> goodsIds = new ArrayList<>();
        Map<Long, Long> assigneeIdByGoods = new HashMap<>();
        for (StocktakeCreateDTO.Item item : dto.getItems()) {
            if (!goodsIds.contains(item.getGoodsId())) {
                goodsIds.add(item.getGoodsId());
            }
            assigneeIdByGoods.put(item.getGoodsId(), item.getAssigneeId());
        }

        List<BaseGoods> goodsList = baseGoodsMapper.selectBatchIds(goodsIds);
        if (goodsList.size() != goodsIds.size()) {
            throw BusinessException.validateFail("存在无效商品，请刷新后重试");
        }

        // 排他守卫：同一商品同一时间只允许在一张未完结盘点单（D81）
        Set<Long> openGoodsIds = findOpenStocktakeGoodsIds();
        List<String> conflicts = goodsList.stream()
                .filter(g -> openGoodsIds.contains(g.getId()))
                .map(BaseGoods::getGoodsName)
                .collect(Collectors.toList());
        if (!conflicts.isEmpty()) {
            throw BusinessException.validateFail(
                    "以下商品已在未完结盘点单中：" + String.join("、", conflicts));
        }

        // D85：负责人必须是仓储部门启用成员（admin/employee）
        Map<Long, SysUser> members = warehouseMemberMap();
        Map<Long, SysUser> assigneeByGoods = new HashMap<>();
        for (Map.Entry<Long, Long> e : assigneeIdByGoods.entrySet()) {
            SysUser assignee = members.get(e.getValue());
            if (assignee == null) {
                throw BusinessException.validateFail("负责人 #" + e.getValue() + " 不是仓储部门成员");
            }
            assigneeByGoods.put(e.getKey(), assignee);
        }

        BizStocktake order = new BizStocktake();
        order.setStocktakeNo(CodeGenerator.stocktakeNo());
        order.setStatus(STATUS_COUNTING);
        order.setRemark(dto.getRemark());
        order.setOperatorId(loginUser.getId());
        order.setOperatorName(loginUser.getRealName());
        order.setOperationTime(LocalDateTime.now());
        bizStocktakeMapper.insert(order);

        for (BaseGoods g : goodsList) {
            SysUser assignee = assigneeByGoods.get(g.getId());
            BizStocktakeDetail d = new BizStocktakeDetail();
            d.setStocktakeId(order.getId());
            d.setGoodsId(g.getId());
            d.setGoodsCode(g.getGoodsCode());
            d.setGoodsName(g.getGoodsName());
            d.setSpec(g.getSpec());
            d.setMaterial(g.getMaterial());
            d.setUnit(g.getUnit());
            d.setGoodsType(g.getType());
            d.setBookQty(g.getStock() == null ? 0 : g.getStock());
            d.setAssigneeId(assignee.getId());
            d.setAssigneeName(assignee.getRealName());
            bizStocktakeDetailMapper.insert(d);
        }
        return order.getId();
    }

    /**
     * 负责人候选（D85）：仓储部门启用成员（admin+员工）
     */
    public List<StocktakeAssigneeOptionVO> assigneeOptions() {
        requireReadAccess();
        return warehouseMemberMap().values().stream().map(u -> {
            StocktakeAssigneeOptionVO vo = new StocktakeAssigneeOptionVO();
            vo.setUserId(u.getId());
            vo.setRealName(u.getRealName());
            vo.setRole(u.getRole());
            return vo;
        }).collect(Collectors.toList());
    }

    /**
     * 改派负责人（D85：盘点中 admin 可改派，人不在岗时调整）
     */
    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, org.example.back.dto.StocktakeAssignDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可改派负责人");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_COUNTING, "仅盘点中的单据可改派负责人");

        BizStocktakeDetail detail = listDetails(id).stream()
                .filter(d -> d.getId().equals(dto.getDetailId()))
                .findFirst()
                .orElseThrow(() -> BusinessException.validateFail("明细 #" + dto.getDetailId() + " 不属于本盘点单"));
        SysUser assignee = warehouseMemberMap().get(dto.getAssigneeId());
        if (assignee == null) {
            throw BusinessException.validateFail("负责人 #" + dto.getAssigneeId() + " 不是仓储部门成员");
        }
        BizStocktakeDetail update = new BizStocktakeDetail();
        update.setId(detail.getId());
        update.setAssigneeId(assignee.getId());
        update.setAssigneeName(assignee.getRealName());
        bizStocktakeDetailMapper.updateById(update);
    }

    // ---------- entry / import ----------

    @Transactional(rollbackFor = Exception.class)
    public void entry(Long id, StocktakeEntryDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门成员可录入实盘数");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_COUNTING, "仅盘点中的单据可录入实盘数");
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        boolean isAdmin = AuthzService.ROLE_ADMIN.equals(loginUser.getRole());

        Map<Long, BizStocktakeDetail> detailMap = listDetails(id).stream()
                .collect(Collectors.toMap(BizStocktakeDetail::getId, Function.identity()));
        for (StocktakeEntryDTO.Item item : dto.getItems()) {
            if (item.getActualQty() == null || item.getActualQty() < 0) {
                throw BusinessException.validateFail("实盘数不能为负数或空");
            }
            BizStocktakeDetail detail = detailMap.get(item.getDetailId());
            if (detail == null) {
                throw BusinessException.validateFail("明细 #" + item.getDetailId() + " 不属于本盘点单");
            }
            // D85：员工限录本人负责行，admin 兜底可录任意行
            requireRowOwnership(detail, loginUser, isAdmin);
            BizStocktakeDetail update = new BizStocktakeDetail();
            update.setId(detail.getId());
            update.setActualQty(item.getActualQty());
            stampCounter(update, loginUser);
            bizStocktakeDetailMapper.updateById(update);
        }
    }

    /**
     * xlsx 回填实盘数（导出表即模板，按「商品编码」列匹配，「实盘数」空白行跳过——D84）
     *
     * @return 实际回填行数
     */
    @Transactional(rollbackFor = Exception.class)
    public int importEntry(Long id, MultipartFile file) throws IOException {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门成员可录入实盘数");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_COUNTING, "仅盘点中的单据可导入实盘数");
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        boolean isAdmin = AuthzService.ROLE_ADMIN.equals(loginUser.getRole());

        Map<String, BizStocktakeDetail> byCode = listDetails(id).stream()
                .filter(d -> d.getGoodsCode() != null)
                .collect(Collectors.toMap(BizStocktakeDetail::getGoodsCode, Function.identity(), (a, b) -> a));

        DataFormatter fmt = new DataFormatter();
        List<String> errors = new ArrayList<>();
        List<BizStocktakeDetail> updates = new ArrayList<>();
        try (XSSFWorkbook wb = new XSSFWorkbook(file.getInputStream())) {
            Sheet sheet = wb.getSheetAt(0);
            Row header = sheet.getRow(0);
            if (header == null) {
                throw BusinessException.validateFail("导入文件为空，请使用导出的盘点表模板");
            }
            int codeCol = findColumn(header, fmt, "商品编码");
            int qtyCol = findColumn(header, fmt, "实盘数");
            if (codeCol < 0 || qtyCol < 0) {
                throw BusinessException.validateFail("导入文件缺少「商品编码」或「实盘数」列，请使用导出的盘点表模板");
            }
            for (int r = 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String code = cellText(row.getCell(codeCol), fmt);
                String qtyText = cellText(row.getCell(qtyCol), fmt);
                if (!StringUtils.hasText(code) && !StringUtils.hasText(qtyText)) {
                    continue; // 整行空白
                }
                if (!StringUtils.hasText(qtyText)) {
                    continue; // 实盘数空白行跳过（部分回填）
                }
                BizStocktakeDetail detail = StringUtils.hasText(code) ? byCode.get(code) : null;
                if (detail == null) {
                    errors.add("第" + (r + 1) + "行商品编码「" + code + "」不在本盘点单");
                    continue;
                }
                Integer qty = parseQty(qtyText);
                if (qty == null) {
                    errors.add("第" + (r + 1) + "行实盘数「" + qtyText + "」无效（需为非负整数）");
                    continue;
                }
                // D85：员工限录本人负责行（admin 兜底），违规行收集后整体拒绝
                if (!isAdmin && (detail.getAssigneeId() == null || !detail.getAssigneeId().equals(loginUser.getId()))) {
                    errors.add("第" + (r + 1) + "行「" + code + "」负责人是「"
                            + (detail.getAssigneeName() == null ? "未指定" : detail.getAssigneeName())
                            + "」，仅本人或仓储管理员可录入");
                    continue;
                }
                BizStocktakeDetail update = new BizStocktakeDetail();
                update.setId(detail.getId());
                update.setActualQty(qty);
                stampCounter(update, loginUser);
                updates.add(update);
            }
        }
        if (!errors.isEmpty()) {
            throw BusinessException.validateFail(String.join("；", errors));
        }
        updates.forEach(bizStocktakeDetailMapper::updateById);
        return updates.size();
    }

    // ---------- submit / review / reject / cancel ----------

    @Transactional(rollbackFor = Exception.class)
    public void submit(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        // D84 修订：提交 = 仓储成员级（员工实盘完直接送审）；审核生效/驳回/取消仍收口 admin
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门成员可提交盘点单");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_COUNTING, "仅盘点中的单据可提交");
        boolean anyCounted = listDetails(id).stream().anyMatch(d -> d.getActualQty() != null);
        if (!anyCounted) {
            throw BusinessException.validateFail("请至少录入一行实盘数再提交");
        }
        // D85：提交人留痕（与审核人分离）
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        BizStocktake update = new BizStocktake();
        update.setId(id);
        update.setStatus(STATUS_PENDING_REVIEW);
        update.setSubmitTime(LocalDateTime.now());
        update.setSubmitterId(loginUser.getId());
        update.setSubmitterName(loginUser.getRealName());
        bizStocktakeMapper.updateById(update);
    }

    /**
     * 审核生效：差异 = 实盘 − 生效时点实时账面（D82），盘盈增/盘亏减，明细写三值。
     */
    @Transactional(rollbackFor = Exception.class)
    public void review(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可审核盘点单");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_PENDING_REVIEW, "仅待审核的单据可审核");
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();

        List<BizStocktakeDetail> details = listDetails(id);
        List<BizStocktakeDetail> counted = details.stream()
                .filter(d -> d.getActualQty() != null)
                .collect(Collectors.toList());
        if (counted.isEmpty()) {
            throw BusinessException.validateFail("无已盘行，不可审核");
        }
        Map<Long, BaseGoods> goodsMap = baseGoodsMapper.selectBatchIds(
                counted.stream().map(BizStocktakeDetail::getGoodsId).collect(Collectors.toList()))
                .stream().collect(Collectors.toMap(BaseGoods::getId, Function.identity()));

        for (BizStocktakeDetail d : counted) {
            BaseGoods goods = goodsMap.get(d.getGoodsId());
            if (goods == null) {
                throw BusinessException.validateFail("商品「" + d.getGoodsName() + "」档案缺失，请人工核对");
            }
            int finalBook = goods.getStock() == null ? 0 : goods.getStock();
            int diff = d.getActualQty() - finalBook;
            if (diff > 0) {
                increaseStock(d.getGoodsId(), diff);
            } else if (diff < 0) {
                decreaseStock(d.getGoodsId(), -diff,
                        "盘亏调整失败：「" + d.getGoodsName() + "」当前库存不足（存在并发变动），请人工核对");
            }
            BizStocktakeDetail update = new BizStocktakeDetail();
            update.setId(d.getId());
            update.setFinalBookQty(finalBook);
            update.setDiffQty(diff);
            bizStocktakeDetailMapper.updateById(update);
        }

        BizStocktake masterUpdate = new BizStocktake();
        masterUpdate.setId(id);
        masterUpdate.setStatus(STATUS_COMPLETED);
        masterUpdate.setReviewerId(loginUser.getId());
        masterUpdate.setReviewerName(loginUser.getRealName());
        masterUpdate.setReviewTime(LocalDateTime.now());
        bizStocktakeMapper.updateById(masterUpdate);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reject(Long id, StocktakeRejectDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可驳回盘点单");
        BizStocktake order = requireEntity(id);
        requireStatus(order, STATUS_PENDING_REVIEW, "仅待审核的单据可驳回");
        BizStocktake update = new BizStocktake();
        update.setId(id);
        update.setStatus(STATUS_COUNTING);
        update.setRejectReason(dto.getReason());
        bizStocktakeMapper.updateById(update);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cancel(Long id, StocktakeCancelDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        authzService.requireDeptAdminOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储管理员可取消盘点单");
        BizStocktake order = requireEntity(id);
        if (order.getStatus() != STATUS_COUNTING && order.getStatus() != STATUS_PENDING_REVIEW) {
            throw BusinessException.validateFail("当前状态不可取消（已完成/已取消为终态）");
        }
        // D85：取消人留痕
        LoginResponse.UserInfoVO loginUser = authService.getUserInfo();
        BizStocktake update = new BizStocktake();
        update.setId(id);
        update.setStatus(STATUS_CANCELED);
        update.setCancelReason(dto == null ? null : dto.getReason());
        update.setCancelerId(loginUser.getId());
        update.setCancelerName(loginUser.getRealName());
        bizStocktakeMapper.updateById(update);
    }

    // ---------- export ----------

    /**
     * 导出盘点表（盲盘默认不含账面数，可切明盘——D84）；导出表即回填模板。
     */
    public byte[] export(Long id, boolean blind) throws IOException {
        requireReadAccess();
        requireEntity(id);
        List<BizStocktakeDetail> details = listDetails(id);

        List<String> headers = new ArrayList<>(List.of("商品编码", "商品名称", "规格", "材质", "单位"));
        if (!blind) {
            headers.add("账面数");
        }
        headers.add("实盘数");

        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("盘点表");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.size(); c++) {
                header.createCell(c).setCellValue(headers.get(c));
            }
            for (int r = 0; r < details.size(); r++) {
                BizStocktakeDetail d = details.get(r);
                Row row = sheet.createRow(r + 1);
                int c = 0;
                row.createCell(c++).setCellValue(d.getGoodsCode() == null ? "" : d.getGoodsCode());
                row.createCell(c++).setCellValue(d.getGoodsName() == null ? "" : d.getGoodsName());
                row.createCell(c++).setCellValue(d.getSpec() == null ? "" : d.getSpec());
                row.createCell(c++).setCellValue(d.getMaterial() == null ? "" : d.getMaterial());
                row.createCell(c++).setCellValue(d.getUnit() == null ? "" : d.getUnit());
                if (!blind) {
                    row.createCell(c++).setCellValue(d.getBookQty() == null ? 0 : d.getBookQty());
                }
                if (d.getActualQty() != null) {
                    row.createCell(c).setCellValue(d.getActualQty());
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ---------- 私有助手 ----------

    /**
     * 盘盈入库（库存变更唯一入口纪律：服务私有助手，D83）
     */
    private void increaseStock(Long goodsId, Integer quantity) {
        LambdaUpdateWrapper<BaseGoods> wrapper = new LambdaUpdateWrapper<>();
        wrapper.eq(BaseGoods::getId, goodsId)
                .setSql("stock = stock + " + quantity);
        baseGoodsMapper.update(null, wrapper);
    }

    /**
     * 盘亏出库（.ge 守卫防并发扣成负库存）
     */
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

    private void requireReadAccess() {
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_WAREHOUSE, "仅仓储部门与超级管理员可查看盘点");
    }

    private BizStocktake requireEntity(Long id) {
        BizStocktake order = bizStocktakeMapper.selectById(id);
        if (order == null) {
            throw BusinessException.notFound("盘点单不存在");
        }
        return order;
    }

    private void requireStatus(BizStocktake order, int expected, String msg) {
        if (order.getStatus() == null || order.getStatus() != expected) {
            throw BusinessException.validateFail(msg);
        }
    }

    private List<BizStocktakeDetail> listDetails(Long stocktakeId) {
        LambdaQueryWrapper<BizStocktakeDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizStocktakeDetail::getStocktakeId, stocktakeId)
                .orderByAsc(BizStocktakeDetail::getId);
        return bizStocktakeDetailMapper.selectList(wrapper);
    }

    /**
     * 未完结盘点单（盘点中/待审核）覆盖的商品ID集合（建单排他守卫）
     */
    private Set<Long> findOpenStocktakeGoodsIds() {
        LambdaQueryWrapper<BizStocktake> openWrapper = new LambdaQueryWrapper<>();
        openWrapper.in(BizStocktake::getStatus, STATUS_COUNTING, STATUS_PENDING_REVIEW);
        List<BizStocktake> openOrders = bizStocktakeMapper.selectList(openWrapper);
        if (openOrders.isEmpty()) {
            return new HashSet<>();
        }
        LambdaQueryWrapper<BizStocktakeDetail> detailWrapper = new LambdaQueryWrapper<>();
        detailWrapper.in(BizStocktakeDetail::getStocktakeId,
                openOrders.stream().map(BizStocktake::getId).collect(Collectors.toList()));
        return bizStocktakeDetailMapper.selectList(detailWrapper).stream()
                .map(BizStocktakeDetail::getGoodsId)
                .collect(Collectors.toSet());
    }

    /**
     * 仓储部门启用成员（admin+employee）Map——D85 负责人候选/校验共用
     */
    private Map<Long, SysUser> warehouseMemberMap() {
        LambdaQueryWrapper<SysDept> deptWrapper = new LambdaQueryWrapper<>();
        deptWrapper.eq(SysDept::getDeptCode, AuthzService.DEPT_WAREHOUSE).last("LIMIT 1");
        SysDept dept = sysDeptMapper.selectOne(deptWrapper);
        if (dept == null) {
            return Map.of();
        }
        LambdaQueryWrapper<SysUser> userWrapper = new LambdaQueryWrapper<>();
        userWrapper.eq(SysUser::getDeptId, dept.getId())
                .eq(SysUser::getStatus, 1)
                .in(SysUser::getRole, AuthzService.ROLE_ADMIN, AuthzService.ROLE_EMPLOYEE);
        return sysUserMapper.selectList(userWrapper).stream()
                .collect(Collectors.toMap(SysUser::getId, Function.identity()));
    }

    /**
     * D85 行级归属校验：员工限录本人负责行，admin 兜底可录任意行
     */
    private void requireRowOwnership(BizStocktakeDetail detail, LoginResponse.UserInfoVO loginUser, boolean isAdmin) {
        if (isAdmin) {
            return;
        }
        if (detail.getAssigneeId() == null || !detail.getAssigneeId().equals(loginUser.getId())) {
            throw BusinessException.validateFail("行「" + detail.getGoodsName() + "」负责人是「"
                    + (detail.getAssigneeName() == null ? "未指定" : detail.getAssigneeName())
                    + "」，仅本人或仓储管理员可录入");
        }
    }

    /**
     * D85 实际录入人盖章（页面录入/导入回填共用）
     */
    private void stampCounter(BizStocktakeDetail update, LoginResponse.UserInfoVO loginUser) {
        update.setCounterId(loginUser.getId());
        update.setCounterName(loginUser.getRealName());
        update.setCountTime(LocalDateTime.now());
    }

    private StocktakeVO toVO(BizStocktake o) {
        StocktakeVO vo = new StocktakeVO();
        vo.setId(o.getId());
        vo.setStocktakeNo(o.getStocktakeNo());
        vo.setStatus(o.getStatus());
        vo.setStatusText(statusText(o.getStatus()));
        vo.setRemark(o.getRemark());
        vo.setOperatorId(o.getOperatorId());
        vo.setOperatorName(o.getOperatorName());
        vo.setOperationTime(o.getOperationTime());
        vo.setSubmitTime(o.getSubmitTime());
        vo.setSubmitterName(o.getSubmitterName());
        vo.setReviewerName(o.getReviewerName());
        vo.setReviewTime(o.getReviewTime());
        vo.setRejectReason(o.getRejectReason());
        vo.setCancelReason(o.getCancelReason());
        vo.setCancelerName(o.getCancelerName());
        vo.setCreateTime(o.getCreateTime());
        return vo;
    }

    private StocktakeDetailVO toDetailVO(BizStocktakeDetail d) {
        StocktakeDetailVO vo = new StocktakeDetailVO();
        vo.setId(d.getId());
        vo.setGoodsId(d.getGoodsId());
        vo.setGoodsCode(d.getGoodsCode());
        vo.setGoodsName(d.getGoodsName());
        vo.setSpec(d.getSpec());
        vo.setMaterial(d.getMaterial());
        vo.setUnit(d.getUnit());
        vo.setGoodsType(d.getGoodsType());
        vo.setBookQty(d.getBookQty());
        vo.setAssigneeId(d.getAssigneeId());
        vo.setAssigneeName(d.getAssigneeName());
        vo.setActualQty(d.getActualQty());
        vo.setCounterName(d.getCounterName());
        vo.setCountTime(d.getCountTime());
        vo.setFinalBookQty(d.getFinalBookQty());
        vo.setDiffQty(d.getDiffQty());
        vo.setUnscanned(d.getActualQty() == null);
        return vo;
    }

    private void fillSummary(StocktakeVO vo, List<BizStocktakeDetail> details) {
        vo.setTotalRows(details.size());
        vo.setCountedRows((int) details.stream().filter(d -> d.getActualQty() != null).count());
        vo.setUnscannedRows(vo.getTotalRows() - vo.getCountedRows());
        vo.setOverRows((int) details.stream().filter(d -> d.getDiffQty() != null && d.getDiffQty() > 0).count());
        vo.setShortRows((int) details.stream().filter(d -> d.getDiffQty() != null && d.getDiffQty() < 0).count());
        vo.setMatchRows((int) details.stream().filter(d -> d.getDiffQty() != null && d.getDiffQty() == 0).count());
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case STATUS_COUNTING -> "盘点中";
            case STATUS_PENDING_REVIEW -> "待审核";
            case STATUS_COMPLETED -> "已完成";
            case STATUS_CANCELED -> "已取消";
            default -> "未知";
        };
    }

    private int findColumn(Row header, DataFormatter fmt, String name) {
        for (Cell cell : header) {
            if (name.equals(fmt.formatCellValue(cell).trim())) {
                return cell.getColumnIndex();
            }
        }
        return -1;
    }

    private String cellText(Cell cell, DataFormatter fmt) {
        return cell == null ? "" : fmt.formatCellValue(cell).trim();
    }

    private Integer parseQty(String text) {
        try {
            BigDecimal v = new BigDecimal(text);
            if (v.stripTrailingZeros().scale() > 0 || v.signum() < 0) {
                return null;
            }
            return v.intValueExact();
        } catch (NumberFormatException | ArithmeticException e) {
            return null;
        }
    }
}
