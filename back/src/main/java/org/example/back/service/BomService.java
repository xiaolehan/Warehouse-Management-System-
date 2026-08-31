package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
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
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * BOM（物料清单）子系统（D41）。
 * 一个成品一条 BOM：biz_bom 主表 + biz_bom_detail 明细（组件/物料 + 单台用量）。
 * 读取：生产 + 仓储两个部门可见；维护(建/改/删)：仅生产研发部管理员。
 */
@Service
public class BomService {

    @Autowired
    private BizBomMapper bizBomMapper;

    @Autowired
    private BizBomDetailMapper bizBomDetailMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthzService authzService;

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
}