package org.example.back.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.common.util.CodeGenerator;
import org.example.back.dto.SupplierContactDTO;
import org.example.back.dto.SupplierQueryDTO;
import org.example.back.dto.SupplierSaveDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BaseSupplier;
import org.example.back.entity.BaseSupplierContact;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BaseSupplierContactMapper;
import org.example.back.mapper.BaseSupplierMapper;
import org.example.back.vo.BatchDeleteResultVO;
import org.example.back.vo.OptionVO;
import org.example.back.vo.SupplierContactVO;
import org.example.back.vo.SupplierVO;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SupplierService {

    @Autowired
    private BaseSupplierMapper baseSupplierMapper;

    @Autowired
    private BaseSupplierContactMapper supplierContactMapper;

    @Autowired
    private BaseGoodsMapper baseGoodsMapper;

    @Autowired
    private AuthzService authzService;

    // D33：供应商资料管理从仓储移入采购部门（admin+员工均可维护）；超管通配
    private void requireSupplierModuleAccess() {
        authzService.requireDeptMemberOrSuperAdmin(AuthzService.DEPT_PURCHASE, "仅采购部门可访问供应商资料");
    }

    public PageResult<SupplierVO> page(SupplierQueryDTO queryDTO) {
        requireSupplierModuleAccess();
        LambdaQueryWrapper<BaseSupplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.like(StringUtils.hasText(queryDTO.getSupplierName()), BaseSupplier::getSupplierName, queryDTO.getSupplierName())
                .eq(queryDTO.getStatus() != null, BaseSupplier::getStatus, queryDTO.getStatus())
                .orderByDesc(BaseSupplier::getId);
        // 联系人现位于子表，联系人搜索改为子表存在性匹配
        if (StringUtils.hasText(queryDTO.getContact())) {
            String like = queryDTO.getContact();
            wrapper.inSql(BaseSupplier::getId,
                    "SELECT supplier_id FROM base_supplier_contact WHERE is_deleted=0 AND contact_person LIKE '%" + like + "%'");
        }

        Page<BaseSupplier> page = baseSupplierMapper.selectPage(new Page<>(queryDTO.getPageNum(), queryDTO.getPageSize()), wrapper);
        List<SupplierVO> records = toVOList(page.getRecords());
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize(), page.getPages());
    }

    // 供应商下拉供商品/进货/预警等跨部门使用，放开为仓储/采购/销售/生产成员（对齐 GoodsService.options）
    public List<OptionVO> options() {
        authzService.requireAnyDeptMemberOrSuperAdmin(
                "仅仓储、采购或销售部门可获取供应商选项",
                AuthzService.DEPT_WAREHOUSE,
                AuthzService.DEPT_PURCHASE,
                AuthzService.DEPT_SALES,
                AuthzService.DEPT_PRODUCTION
        );
        LambdaQueryWrapper<BaseSupplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseSupplier::getStatus, 1).orderByAsc(BaseSupplier::getSupplierName);
        return baseSupplierMapper.selectList(wrapper).stream()
                .map(item -> new OptionVO(item.getId(), item.getSupplierName()))
                .toList();
    }

    public SupplierVO getById(Long id) {
        requireSupplierModuleAccess();
        return toVO(requireSupplier(id));
    }

    @Transactional
    public void create(SupplierSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSupplierModuleAccess();
        checkSupplierNameUnique(dto.getSupplierName(), null);
        BaseSupplier supplier = new BaseSupplier();
        supplier.setSupplierName(dto.getSupplierName());
        supplier.setAddress(dto.getAddress());
        supplier.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
        supplier.setDescription(dto.getDescription());
        supplier.setSupplierCode(CodeGenerator.supplierCode());
        baseSupplierMapper.insert(supplier);
        insertContacts(supplier.getId(), dto.getContacts());
    }

    @Transactional
    public void update(Long id, SupplierSaveDTO dto) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSupplierModuleAccess();
        BaseSupplier supplier = requireSupplier(id);
        checkSupplierNameUnique(dto.getSupplierName(), id);
        supplier.setSupplierName(dto.getSupplierName());
        supplier.setAddress(dto.getAddress());
        supplier.setStatus(dto.getStatus() == null ? supplier.getStatus() : dto.getStatus());
        supplier.setDescription(dto.getDescription());
        baseSupplierMapper.updateById(supplier);
        // 重建联系人（先逻辑删旧，再按提交内容重插）
        supplierContactMapper.delete(Wrappers.<BaseSupplierContact>lambdaQuery()
                .eq(BaseSupplierContact::getSupplierId, id));
        insertContacts(id, dto.getContacts());
    }

    public void delete(Long id) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSupplierModuleAccess();
        deleteInternal(id);
    }

    /** 手测问题 1（2026-09-23）：批量删除——守卫一次，逐行跑单删同款校验，尽力而为聚合明细 */
    public BatchDeleteResultVO batchDelete(List<Long> ids) {
        authzService.requireNotSuperAdminForBusinessWrite();
        requireSupplierModuleAccess();
        BatchDeleteResultVO result = new BatchDeleteResultVO();
        if (ids == null || ids.isEmpty()) {
            return result;
        }
        for (Long id : ids) {
            try {
                deleteInternal(id);
                result.addSuccess();
            } catch (BusinessException e) {
                BaseSupplier supplier = baseSupplierMapper.selectById(id);
                result.addFailure(id, supplier != null ? supplier.getSupplierName() : String.valueOf(id), e.getMessage());
            }
        }
        return result;
    }

    private void deleteInternal(Long id) {
        // D100：缺省供应商是成品建档/未知物料自动建档的系统锚点（GoodsService.DEFAULT_SUPPLIER_ID），不可删除
        if (GoodsService.DEFAULT_SUPPLIER_ID.equals(id)) {
            throw BusinessException.validateFail("系统默认供应商是成品建档与自动建档的系统依赖，不可删除");
        }
        requireSupplier(id);
        LambdaQueryWrapper<BaseGoods> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseGoods::getSupplierId, id);
        if (baseGoodsMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("该供应商下仍有关联商品，无法删除");
        }
        baseSupplierMapper.deleteById(id);
        supplierContactMapper.delete(Wrappers.<BaseSupplierContact>lambdaQuery()
                .eq(BaseSupplierContact::getSupplierId, id));
    }

    private void insertContacts(Long supplierId, List<SupplierContactDTO> contacts) {
        if (contacts == null) {
            return;
        }
        for (int i = 0; i < contacts.size(); i++) {
            SupplierContactDTO dto = contacts.get(i);
            BaseSupplierContact c = new BaseSupplierContact();
            c.setSupplierId(supplierId);
            c.setContactPerson(dto.getContactPerson());
            c.setContactPhone(dto.getContactPhone());
            c.setPosition(dto.getPosition());
            c.setIsDefault(i == 0 ? 1 : 0);
            supplierContactMapper.insert(c);
        }
    }

    private void checkSupplierNameUnique(String supplierName, Long excludeId) {
        LambdaQueryWrapper<BaseSupplier> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(BaseSupplier::getSupplierName, supplierName)
                .ne(excludeId != null, BaseSupplier::getId, excludeId);
        if (baseSupplierMapper.selectCount(wrapper) > 0) {
            throw BusinessException.validateFail("供应商名称已存在");
        }
    }

    private BaseSupplier requireSupplier(Long id) {
        BaseSupplier supplier = baseSupplierMapper.selectById(id);
        if (supplier == null) {
            throw BusinessException.notFound("供应商不存在");
        }
        return supplier;
    }

    private List<SupplierVO> toVOList(List<BaseSupplier> suppliers) {
        List<Long> ids = suppliers.stream().map(BaseSupplier::getId).toList();
        // 一次查询本页所有联系人以避免 N+1
        List<BaseSupplierContact> contacts = ids.isEmpty() ? List.of()
                : supplierContactMapper.selectList(Wrappers.<BaseSupplierContact>lambdaQuery()
                        .in(BaseSupplierContact::getSupplierId, ids));
        Map<Long, List<BaseSupplierContact>> grouped = contacts.stream()
                .collect(Collectors.groupingBy(BaseSupplierContact::getSupplierId));
        return suppliers.stream().map(s -> {
            SupplierVO vo = new SupplierVO();
            BeanUtils.copyProperties(s, vo);
            fillContacts(vo, grouped.getOrDefault(s.getId(), List.of()));
            return vo;
        }).toList();
    }

    private SupplierVO toVO(BaseSupplier supplier) {
        SupplierVO vo = new SupplierVO();
        BeanUtils.copyProperties(supplier, vo);
        List<BaseSupplierContact> contacts = supplierContactMapper.selectList(Wrappers.<BaseSupplierContact>lambdaQuery()
                .eq(BaseSupplierContact::getSupplierId, supplier.getId()));
        fillContacts(vo, contacts);
        return vo;
    }

    private void fillContacts(SupplierVO vo, List<BaseSupplierContact> contacts) {
        List<SupplierContactVO> vos = contacts.stream().map(c -> {
            SupplierContactVO cv = new SupplierContactVO();
            BeanUtils.copyProperties(c, cv);
            return cv;
        }).toList();
        vo.setContacts(vos);
        // 主联系人：is_default=1 优先，否则取第一条
        BaseSupplierContact main = contacts.stream()
                .filter(c -> c.getIsDefault() != null && c.getIsDefault() == 1)
                .findFirst()
                .orElse(contacts.isEmpty() ? null : contacts.get(0));
        if (main != null) {
            vo.setContact(main.getContactPerson());
            vo.setPosition(main.getPosition());
            vo.setPhone(main.getContactPhone());
        }
    }
}