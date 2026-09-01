package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.BomQueryDTO;
import org.example.back.dto.BomDetailDTO;
import org.example.back.dto.BomSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizBom;
import org.example.back.entity.BizBomDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizBomDetailMapper;
import org.example.back.mapper.BizBomMapper;
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

    @Autowired
    private BizBomMapper bizBomMapper;

    @Autowired
    private BizBomDetailMapper bizBomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthzService authzService;

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
        requireBomWriteAccess();
        BaseGoods product = requireProduct(dto.getGoodsId());
        checkBomCodeUnique(dto.getBomCode(), null);
        checkGoodsBomUnique(dto.getGoodsId(), null);

        BizBom bom = new BizBom();
        bom.setBomCode(dto.getBomCode());
        bom.setGoodsId(product.getId());
        bom.setGoodsName(product.getGoodsName());
        bom.setRemark(dto.getRemark());
        bizBomMapper.insert(bom);

        insertDetails(bom.getId(), dto.getDetails());
    }

    @Transactional(rollbackFor = Exception.class)
    public void update(Long id, BomSaveDTO dto) {
        requireBomWriteAccess();
        BizBom bom = requireBom(id);
        BaseGoods product = requireProduct(dto.getGoodsId());
        checkBomCodeUnique(dto.getBomCode(), id);
        checkGoodsBomUnique(dto.getGoodsId(), id);

        bom.setBomCode(dto.getBomCode());
        bom.setGoodsId(product.getId());
        bom.setGoodsName(product.getGoodsName());
        bom.setRemark(dto.getRemark());
        bizBomMapper.updateById(bom);

        // 明细整体重建：删旧 + 插新
        deleteDetailsByBomId(id);
        insertDetails(id, dto.getDetails());
    }

    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        requireBomWriteAccess();
        BizBom bom = requireBom(id);
        if (bom.getIsDeleted() != null && bom.getIsDeleted() == 1) {
            throw BusinessException.validateFail("该 BOM 已删除");
        }
        bizBomMapper.deleteById(id);
        deleteDetailsByBomId(id);
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

    private BaseGoods requireProduct(Long goodsId) {
        BaseGoods goods = baseGoodsMapper.selectById(goodsId);
        if (goods == null) {
            throw BusinessException.validateFail("成品不存在");
        }
        if (!GoodsService.GOODS_TYPE_PRODUCT.equalsIgnoreCase(goods.getType())) {
            throw BusinessException.validateFail("BOM 只能挂接在成品（type=product）上，所选货品不是成品");
        }
        if (goods.getStatus() != null && goods.getStatus() != 1) {
            throw BusinessException.validateFail("成品已停用，无法建立 BOM");
        }
        return goods;
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

    /** 导入并落库：该成品已有 BOM 则整体覆盖明细，否则新建 */
    @Transactional(rollbackFor = Exception.class)
    public int importBom(Long goodsId, String bomCode, List<BomDetailDTO> details) {
        requireBomWriteAccess();
        if (details == null || details.isEmpty() || !StringUtils.hasText(bomCode)) {
            throw BusinessException.validateFail("导入数据或 BOM 编码不能为空");
        }
        BomSaveDTO dto = new BomSaveDTO();
        dto.setBomCode(bomCode.trim());
        dto.setGoodsId(goodsId);
        dto.setRemark("xlsx 批量导入");
        dto.setDetails(details);
        BizBom existing = bizBomMapper.selectOne(Wrappers.<BizBom>lambdaQuery().eq(BizBom::getGoodsId, goodsId));
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