package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.BomQueryDTO;
import org.example.back.dto.BomDetailDTO;
import org.example.back.dto.BomSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.BomDeleteCheckVO;
import org.example.back.vo.BomDetailVO;
import org.example.back.vo.BomVO;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.Picture;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * BOM（物料清单）子系统（D41）。
 * 一个成品一条 BOM：biz_bom 主表 + biz_bom_detail 明细（组件/物料 + 单台用量）。
 * 读取：生产 + 仓储两个部门可见；维护(建/改/删)：仅生产研发部管理员。
 */
@Slf4j
@Service
public class BomService {

    // xlsx 导入/导出模板列固定：序号/图片/组件名称/规格/数量/材质/备注
    private static final int COL_NAME = 2;
    private static final int COL_SPEC = 3;
    private static final int COL_QTY = 4;
    private static final int COL_MATERIAL = 5;
    private static final int COL_REMARK = 6;

    // 补料采购/BOM 建档：自产成品无采购供应商，统一挂缺省供应商（与 db.sql 种子 base_goods supplier_id=1 一致）
    private static final Long DEFAULT_SUPPLIER_ID = 1L;

    @Autowired
    private BizBomMapper bizBomMapper;

    @Autowired
    private BizBomDetailMapper bizBomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthzService authzService;

    // D66：BOM 删除安全级联——成品主档清理口径（库存=0 且无单据引用）与成品手工删除共用；未完结任务单计数亦归口此处
    @Autowired
    private GoodsReferenceService goodsReferenceService;

    @Autowired
    private WorkRequirementAttachmentStorageService storageService;

    // D41：BOM 读取开放给生产 + 仓储两个部门（仓储端「物料管理」的产品名称即对应此 BOM）
    private void requireBomReadAccess() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅生产研发部或仓储部门可访问 BOM",
                AuthzService.DEPT_PRODUCTION,
                AuthzService.DEPT_WAREHOUSE
        );
    }

    // D41：BOM 维护（建/改/删）仅生产研发部管理员
    private void requireBomWriteAccess() {
        authzService.requireDeptAdminOrSuperAdmin(
                AuthzService.DEPT_PRODUCTION,
                "仅生产研发部管理员可维护 BOM"
        );
    }

    // ============================== 查询 ==============================

    public PageResult<BomVO> page(BomQueryDTO queryDTO) {
        requireBomReadAccess();

        LambdaQueryWrapper<BizBom> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getBomCode()), BizBom::getBomCode, queryDTO.getBomCode())
                .like(StringUtils.hasText(queryDTO.getGoodsName()), BizBom::getGoodsName, queryDTO.getGoodsName())
                .orderByDesc(BizBom::getId);

        Page<BizBom> page = bizBomMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<BomVO> records = page.getRecords().stream().map(this::toVO).toList();
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    public BomVO getById(Long id) {
        requireBomReadAccess();
        BizBom bizBom = requireBom(id);
        return toVO(bizBom);
    }

    // ============================== 维护 ==============================

    @Transactional(rollbackFor = Exception.class)
    public void create(BomSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        String productName = dto.getGoodsName().trim();
        BaseGoods product = resolveOrCreateProduct(productName, dto.getUnit());
        checkGoodsBomUnique(product.getId(), null);
        String bomCode = resolveBomCode(dto.getBomCode(), productName);
        checkBomCodeUnique(bomCode, null);

        BizBom bom = new BizBom();
        bom.setBomCode(bomCode);
        bom.setGoodsId(product.getId());
        bom.setGoodsName(product.getGoodsName());
        bom.setLeadDays(dto.getLeadDays()); // D71：标准工期可空
        bom.setRemark(dto.getRemark());
        bizBomMapper.insert(bom);

        insertDetails(bom.getId(), dto.getDetails());
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BomSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        BizBom bom = requireBom(id);
        String newName = dto.getGoodsName().trim();
        BaseGoods product = baseGoodsMapper.selectById(bom.getGoodsId());
        if (product != null) {
            // 成品身份(goodsId)锁定；名称/单位随 BOM 编辑同步到成品主档
            boolean nameChanged = !newName.equals(product.getGoodsName());
            if (nameChanged) {
                checkGoodsNameAvailable(newName, product.getId());
            }
            if (nameChanged || StringUtils.hasText(dto.getUnit())) {
                BaseGoods upd = new BaseGoods();
                upd.setId(product.getId());
                upd.setGoodsName(newName);
                if (StringUtils.hasText(dto.getUnit())) {
                    upd.setUnit(dto.getUnit().trim());
                }
                baseGoodsMapper.updateById(upd);
            }
        }

        String bomCode = resolveBomCode(dto.getBomCode(), newName);
        checkBomCodeUnique(bomCode, id);
        bom.setBomCode(bomCode);
        bom.setGoodsName(newName);
        bom.setLeadDays(dto.getLeadDays()); // D71：标准工期可空（留空=清除，恢复"待生产评估"）
        bom.setRemark(dto.getRemark());
        bizBomMapper.updateById(bom);

        // 明细整体重建：删旧 + 插新
        deleteDetailsByBomId(id);
        insertDetails(id, dto.getDetails());
    }

    /**
     * D66 删除治理：未完结任务单软保护（force 放行）→ 软删 BOM 与明细 → 安全级联清理成品主档。
     * 成品主档清理口径（D66/Q11）：库存=0 且未被任何单据引用；否则保留并在返回信息中说明。
     *
     * @return 处理结果描述（前端 toast 展示）
     */
    @Transactional(rollbackFor = Exception.class)
    public String delete(Long id, boolean force) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        return deleteInternal(id, force);
    }

    /**
     * 手测问题 1（2026-09-23）：批量删除——守卫一次，逐行跑单删同款校验（非强制：有未完结任务单的行进失败明细），尽力而为。
     */
    @Transactional(rollbackFor = Exception.class)
    public BatchDeleteResultVO batchDelete(List<Long> ids) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        BatchDeleteResultVO result = new BatchDeleteResultVO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            try {
                deleteInternal(id, false);
                result.addSuccess();
            } catch (BusinessException e) {
                BizBom bom = bizBomMapper.selectById(id);
                result.addFailure(id, bom != null ? bom.getGoodsName() : String.valueOf(id), e.getMessage());
            }
        }
        return result;
    }

    private String deleteInternal(Long id, boolean force) {
        BizBom bom = requireBom(id);
        BaseGoods product = baseGoodsMapper.selectById(bom.getGoodsId());
        long unfinished = goodsReferenceService.countUnfinishedOrders(bom.getGoodsId());
        if (unfinished > 0 && !force) {
            throw BusinessException.validateFail("该成品有 " + unfinished + " 张未完结生产任务单，删除 BOM 后将无法补料；请确认后重试");
        }
        bizBomMapper.deleteById(id);
        deleteDetailsByBomId(id);
        if (product != null) {
            if (goodsReferenceService.isProductDeletable(product.getId(), product.getStock())) {
                baseGoodsMapper.deleteById(product.getId());
                return "BOM 已删除，成品主档[" + product.getGoodsName() + "]已一并清理";
            }
            return "BOM 已删除；成品主档[" + product.getGoodsName() + "]保留（有库存或被单据引用）";
        }
        return "BOM 已删除";
    }

    /** D66：删除前检查——该成品名下未完结生产任务单数（前端据此事先二次确认） */
    public BomDeleteCheckVO deleteCheck(Long id) {
        requireBomWriteAccess();
        BizBom bom = requireBom(id);
        BomDeleteCheckVO result = new BomDeleteCheckVO();
        result.setUnfinishedOrderCount(goodsReferenceService.countUnfinishedOrders(bom.getGoodsId()));
        result.setGoodsName(bom.getGoodsName());
        return result;
    }

    // ============================== 私有方法 ==============================

    private void insertDetails(Long bomId, List<BomDetailDTO> details) {
        int sortNo = 0;
        for (BomDetailDTO dto : details) {
            validateDetail(dto, sortNo);
            BizBomDetail detail = new BizBomDetail();
            detail.setBomId(bomId);
            detail.setSortNo(sortNo);
            detail.setComponentName(dto.getComponentName());
            detail.setSpec(dto.getSpec());
            detail.setQuantity(dto.getQuantity());
            detail.setMaterial(dto.getMaterial());
            detail.setImage(dto.getImage());
            detail.setRemark(dto.getRemark());
            detail.setIsReference(Boolean.TRUE.equals(dto.getIsReference()) ? 1 : 0);
            detail.setGoodsId(dto.getGoodsId());
            bizBomDetailMapper.insert(detail);
            sortNo++;
        }
    }

    // D41：说明行可不关联物料；一旦关联必须是启用中的物料(type=material)，参考行不参与齐套
    private void validateDetail(BomDetailDTO dto, int sortNo) {
        if (StringUtils.hasText(dto.getComponentName()) || dto.getQuantity() != null) {
            if (!StringUtils.hasText(dto.getComponentName())) {
                throw BusinessException.validateFail("第 " + (sortNo + 1) + " 行缺少组件/物料名称");
            }
            if (dto.getQuantity() == null) {
                throw BusinessException.validateFail("第 " + (sortNo + 1) + " 行「" + dto.getComponentName() + "」缺少单台用量");
            }
        }
        if (dto.getGoodsId() != null) {
            BaseGoods goods = baseGoodsMapper.selectById(dto.getGoodsId());
            if (goods == null || !GoodsService.GOODS_TYPE_MATERIAL.equalsIgnoreCase(goods.getType())) {
                throw BusinessException.validateFail("第 " + (sortNo + 1) + " 行关联的物料不存在或不是物料类型");
            }
            if (goods.getGoodsCode() != null) {
                // 可选：关联物料时用物料名兜底组件名
                if (!StringUtils.hasText(dto.getComponentName())) {
                    dto.setComponentName(goods.getGoodsName());
                }
            }
        }
    }

    /** 一个 BOM 即一种成品：按成品名称找成品主档，不存在则自动落一条 type=product 记录 */
    private BaseGoods resolveOrCreateProduct(String goodsName, String unit) {
        BaseGoods existing = findByProductName(goodsName);
        if (existing != null) {
            return existing;
        }
        checkGoodsNameAvailable(goodsName, null);
        BaseGoods product = new BaseGoods();
        product.setType(GoodsService.GOODS_TYPE_PRODUCT);
        product.setGoodsCode(CodeGenerator.productGoodsCode());
        product.setGoodsName(goodsName);
        product.setProductName(goodsName);
        product.setCategory("成品");
        product.setSupplierId(DEFAULT_SUPPLIER_ID);
        product.setUnit(StringUtils.hasText(unit) ? unit.trim() : null);
        product.setStock(0);
        product.setWarningStock(0); // D65：成品不参与库存预警，与手工建档默认值对齐
        product.setStatus(1);
        product.setDescription("由生产研发部 BOM 建档生成");
        baseGoodsMapper.insert(product);
        return baseGoodsMapper.selectById(product.getId());
    }

    private BaseGoods findByProductName(String goodsName) {
        return baseGoodsMapper.selectOne(Wrappers.<BaseGoods>lambdaQuery()
                .eq(BaseGoods::getGoodsName, goodsName)
                .eq(BaseGoods::getType, GoodsService.GOODS_TYPE_PRODUCT)
                .eq(BaseGoods::getIsDeleted, 0));
    }

    /** 成品名称全库唯一：既不能撞同名的成品，也不能撞物料 */
    private void checkGoodsNameAvailable(String goodsName, Long excludeGoodsId) {
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getGoodsName, goodsName)
                .eq(BaseGoods::getIsDeleted, 0)
                .ne(excludeGoodsId != null, BaseGoods::getId, excludeGoodsId);
        if (baseGoodsMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("该成品名称「" + goodsName + "」已被其他货品占用，请换一个");
        }
    }

    /** BOM 编码：优先用调用方传入，留空则按「{成品名称}-BOM」自动生成 */
    private String resolveBomCode(String bomCode, String goodsName) {
        if (StringUtils.hasText(bomCode)) {
            return bomCode.trim();
        }
        return goodsName + "-BOM";
    }

    private void checkBomCodeUnique(String bomCode, Long excludeId) {
        LambdaQueryWrapper<BizBom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizBom::getBomCode, bomCode)
                .ne(excludeId != null, BizBom::getId, excludeId);
        if (bizBomMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("BOM 编码已存在");
        }
    }

    private void checkGoodsBomUnique(Long goodsId, Long excludeId) {
        LambdaQueryWrapper<BizBom> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizBom::getGoodsId, goodsId)
                .ne(excludeId != null, BizBom::getId, excludeId);
        if (bizBomMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("该成品已存在 BOM，无需重复建立");
        }
    }

    private BizBom requireBom(Long id) {
        BizBom bom = bizBomMapper.selectById(id);
        if (bom == null) {
            throw BusinessException.notFound("BOM 不存在");
        }
        return bom;
    }

    private void deleteDetailsByBomId(Long bomId) {
        LambdaQueryWrapper<BizBomDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizBomDetail::getBomId, bomId);
        bizBomDetailMapper.delete(wrapper);
    }

    private List<BizBomDetail> listDetails(Long bomId) {
        LambdaQueryWrapper<BizBomDetail> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BizBomDetail::getBomId, bomId)
                .orderByAsc(BizBomDetail::getSortNo);
        return bizBomDetailMapper.selectList(wrapper);
    }

    private BomVO toVO(BizBom bom) {
        BomVO vo = new BomVO();
        BeanUtils.copyProperties(bom, vo);
        BaseGoods product = baseGoodsMapper.selectById(bom.getGoodsId());
        if (product != null) {
            vo.setGoodsUnit(product.getUnit());
        }
        List<BizBomDetail> details = listDetails(bom.getId());
        vo.setDetails(details.stream().map(this::toDetailVO).toList());
        return vo;
    }

    private BomDetailVO toDetailVO(BizBomDetail detail) {
        BomDetailVO vo = new BomDetailVO();
        BeanUtils.copyProperties(detail, vo);
        return vo;
    }

    // ============================== xlsx 导出 / 模板 ==============================

    /** 导出单个成品(BOM)全部明细，含按行嵌入的组件图片 */
    public byte[] exportBom(Long bomId) throws IOException {
        BomVO bom = getById(bomId); // 内部已做 BOM 读权限校验
        String title = (StringUtils.hasText(bom.getGoodsName()) ? bom.getGoodsName() : "BOM");
        List<BomDetailVO> details = bom.getDetails() == null ? List.of() : bom.getDetails();
        return buildBook(title + "-BOM", details);
    }

    /** 下载空白 BOM 导入模板（生产研发管理员用） */
    public byte[] downloadTemplate() throws IOException {
        requireBomWriteAccess();
        return buildBook("BOM导入模板", List.of());
    }

    /** 上传组件图片并持久化，返回 storedPath */
    public String uploadComponentImage(MultipartFile file) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        return storageService.storeImagePermanent(file);
    }

    /** 解析导入 xlsx 的文字列（图片不解析），返回明细行 */
    public List<BomDetailDTO> parseImportRows(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter();
            List<BomDetailDTO> rows = new ArrayList<>();
            for (int r = 2; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) {
                    continue;
                }
                String name = fmt.formatCellValue(row.getCell(COL_NAME)).trim();
                if (name.isEmpty()) {
                    continue;
                }
                BigDecimal qty = parseQty(fmt.formatCellValue(row.getCell(COL_QTY)));
                if (qty == null) {
                    throw BusinessException.validateFail("第 " + r + " 行「" + name + "」缺少单台用量");
                }
                BomDetailDTO d = new BomDetailDTO();
                d.setComponentName(name);
                d.setSpec(fmt.formatCellValue(row.getCell(COL_SPEC)).trim());
                d.setQuantity(qty);
                d.setMaterial(fmt.formatCellValue(row.getCell(COL_MATERIAL)).trim());
                d.setRemark(fmt.formatCellValue(row.getCell(COL_REMARK)).trim());
                d.setGoodsId(null); // 方案先行：导入不挂仓库物料，待入库建档后回挂
                d.setIsReference(false);
                rows.add(d);
            }
            if (rows.isEmpty()) {
                throw BusinessException.validateFail("未解析到有效明细行");
            }
            return rows;
        }
    }

    /** 导入并落库：该成品已有 BOM 则整体覆盖明细，否则新建（BOM=成品，按成品名称匹配） */
    @Transactional(rollbackFor = Exception.class)
    public int importBom(String goodsName, String bomCode, List<BomDetailDTO> details) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireBomWriteAccess();
        if (details == null || details.isEmpty() || !StringUtils.hasText(goodsName)) {
            throw BusinessException.validateFail("导入数据或成品名称不能为空");
        }
        String name = goodsName.trim();
        BaseGoods product = resolveOrCreateProduct(name, null);
        BomSaveDTO dto = new BomSaveDTO();
        dto.setBomCode(bomCode);
        dto.setGoodsName(name);
        dto.setRemark("xlsx 批量导入");
        dto.setDetails(details);
        BizBom existing = bizBomMapper.selectOne(Wrappers.<BizBom>lambdaQuery().eq(BizBom::getGoodsId, product.getId()));
        if (existing != null) {
            update(existing.getId(), dto);
        } else {
            create(dto);
        }
        return details.size();
    }

    private BigDecimal parseQty(String raw) {
        if (raw == null || raw.trim().isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(raw.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private byte[] buildBook(String title, List<BomDetailVO> details) throws IOException {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet(title.length() > 31 ? title.substring(0, 31) : title);
            Row titleRow = sheet.createRow(0);
            Cell titleCell = titleRow.createCell(0);
            titleCell.setCellValue(title);
            sheet.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            String[] headers = {"序号", "图片", "组件名称", "规格", "数量", "材质", "备注"};
            Row head = sheet.createRow(1);
            for (int i = 0; i < headers.length; i++) {
                head.createCell(i).setCellValue(headers[i]);
            }

            if (details.isEmpty()) {
                Row example = sheet.createRow(2);
                example.createCell(0).setCellValue(1);
                example.createCell(COL_NAME).setCellValue("组件示例（请按行替换/追加）");
            }
            int r = 2;
            for (int i = 0; i < details.size(); i++) {
                BomDetailVO d = details.get(i);
                Row row = sheet.createRow(r);
                row.setHeightInPoints(55);
                row.createCell(0).setCellValue(i + 1);
                row.createCell(COL_NAME).setCellValue(d.getComponentName());
                row.createCell(COL_SPEC).setCellValue(d.getSpec());
                if (d.getQuantity() != null) {
                    row.createCell(COL_QTY).setCellValue(d.getQuantity().doubleValue());
                }
                row.createCell(COL_MATERIAL).setCellValue(d.getMaterial());
                row.createCell(COL_REMARK).setCellValue(d.getRemark());
                embedDetailImage(wb, sheet, d.getImage(), r);
                r++;
            }
            setColumnWidths(sheet);
            return toByteArray(wb);
        }
    }

    private void embedDetailImage(XSSFWorkbook wb, Sheet sheet, String storedPath, int row) {
        if (!StringUtils.hasText(storedPath)) {
            return;
        }
        try {
            Resource res = storageService.loadAsResource(storedPath);
            byte[] bytes = res.getInputStream().readAllBytes();
            int picType = storedPath.toLowerCase().endsWith(".png")
                    ? Workbook.PICTURE_TYPE_PNG : Workbook.PICTURE_TYPE_JPEG;
            int picIdx = wb.addPicture(bytes, picType);
            ClientAnchor anchor = new XSSFClientAnchor(0, 0, 0, 0, 1, row, 2, row + 1);
            Drawing<?> drawing = sheet.createDrawingPatriarch();
            drawing.createPicture(anchor, picIdx);
        } catch (BusinessException ex) {
            log.warn("BOM 导出跳过缺失图片 {}: {}", storedPath, ex.getMsg());
        } catch (Exception e) {
            log.warn("BOM 导出处理图片失败 {}: {}", storedPath, e.getMessage());
        }
    }

    private void setColumnWidths(Sheet sheet) {
        sheet.setColumnWidth(0, 5 * 256);
        sheet.setColumnWidth(1, 28 * 256);
        sheet.setColumnWidth(2, 26 * 256);
        sheet.setColumnWidth(3, 22 * 256);
        sheet.setColumnWidth(4, 8 * 256);
        sheet.setColumnWidth(5, 12 * 256);
        sheet.setColumnWidth(6, 30 * 256);
    }

    private byte[] toByteArray(Workbook wb) throws IOException {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }
}