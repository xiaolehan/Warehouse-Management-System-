package org.example.back.service;

import org.example.back.common.exception.BusinessException;
import org.example.back.common.result.PageResult;
import org.example.back.dto.LoginResponse;
import org.example.back.dto.StocktakeCancelDTO;
import org.example.back.dto.StocktakeCreateDTO;
import org.example.back.dto.StocktakeEntryDTO;
import org.example.back.dto.StocktakeQueryDTO;
import org.example.back.dto.StocktakeRejectDTO;
import org.example.back.entity.BaseGoods;
import org.example.back.entity.BizStocktake;
import org.example.back.entity.BizStocktakeDetail;
import org.example.back.mapper.BaseGoodsMapper;
import org.example.back.mapper.BizStocktakeDetailMapper;
import org.example.back.mapper.BizStocktakeMapper;
import org.example.back.vo.StocktakeGoodsOptionVO;
import org.example.back.vo.StocktakeVO;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class StocktakeServiceTest {

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // 初始化 MyBatis-Plus lambda 缓存（纯 mock 测试下不会自动加载）
        var assistant = new org.apache.ibatis.builder.MapperBuilderAssistant(
                new org.apache.ibatis.session.Configuration(), "test");
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BizStocktake.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BizStocktakeDetail.class);
        com.baomidou.mybatisplus.core.metadata.TableInfoHelper.initTableInfo(assistant, BaseGoods.class);
    }

    @Mock private BizStocktakeMapper bizStocktakeMapper;
    @Mock private BizStocktakeDetailMapper bizStocktakeDetailMapper;
    @Mock private BaseGoodsMapper baseGoodsMapper;
    @Mock private AuthService authService;
    @Mock private AuthzService authzService;
    @Mock private org.example.back.mapper.SysUserMapper sysUserMapper;
    @Mock private org.example.back.mapper.SysDeptMapper sysDeptMapper;

    @InjectMocks private StocktakeService service;

    // ---------- 工具 ----------

    private LoginResponse.UserInfoVO warehouseAdmin() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(10L);
        user.setRealName("仓储管理员");
        user.setRole("admin");
        return user;
    }

    private LoginResponse.UserInfoVO warehouseEmployee() {
        LoginResponse.UserInfoVO user = new LoginResponse.UserInfoVO();
        user.setId(20L);
        user.setRealName("仓储员工");
        user.setRole("employee");
        return user;
    }

    private org.example.back.entity.SysUser member(long id, String name, String role) {
        org.example.back.entity.SysUser u = new org.example.back.entity.SysUser();
        u.setId(id);
        u.setRealName(name);
        u.setRole(role);
        u.setDeptId(3L);
        u.setStatus(1);
        return u;
    }

    /** 打桩仓储部门 + 成员列表（admin 10 / employee 20） */
    private void stubWarehouseMembers() {
        org.example.back.entity.SysDept dept = new org.example.back.entity.SysDept();
        dept.setId(3L);
        dept.setDeptCode("warehouse");
        when(sysDeptMapper.selectOne(any())).thenReturn(dept);
        when(sysUserMapper.selectList(any())).thenReturn(List.of(
                member(10L, "仓储管理员", "admin"), member(20L, "仓储员工", "employee")));
    }

    private BaseGoods goods(long id, String code, String name, String type, int stock) {
        BaseGoods g = new BaseGoods();
        g.setId(id);
        g.setGoodsCode(code);
        g.setGoodsName(name);
        g.setType(type);
        g.setSpec("规格" + id);
        g.setMaterial("材质" + id);
        g.setUnit("个");
        g.setStock(stock);
        return g;
    }

    private BizStocktake order(long id, int status) {
        BizStocktake o = new BizStocktake();
        o.setId(id);
        o.setStocktakeNo("ST260913000001001");
        o.setStatus(status);
        return o;
    }

    private BizStocktakeDetail detail(long id, long stocktakeId, long goodsId, Integer actualQty) {
        BizStocktakeDetail d = new BizStocktakeDetail();
        d.setId(id);
        d.setStocktakeId(stocktakeId);
        d.setGoodsId(goodsId);
        d.setGoodsCode("GD" + goodsId);
        d.setGoodsName("商品" + goodsId);
        d.setBookQty(10);
        // D85：默认负责人 = 仓储 admin(10)
        d.setAssigneeId(10L);
        d.setAssigneeName("仓储管理员");
        d.setActualQty(actualQty);
        return d;
    }

    private StocktakeCreateDTO createDto(long goodsId, long assigneeId) {
        StocktakeCreateDTO dto = new StocktakeCreateDTO();
        StocktakeCreateDTO.Item item = new StocktakeCreateDTO.Item();
        item.setGoodsId(goodsId);
        item.setAssigneeId(assigneeId);
        dto.setItems(List.of(item));
        return dto;
    }

    // ---------- create ----------

    @Test
    void create_snapshotsGoodsAndAssignsCountingStatus() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(
                goods(51L, "GD51", "板1", "material", 100),
                goods(52L, "GD52", "PTO153", "product", 7)));
        when(bizStocktakeMapper.selectList(any())).thenReturn(List.of());
        stubWarehouseMembers();

        StocktakeCreateDTO dto = new StocktakeCreateDTO();
        StocktakeCreateDTO.Item i1 = new StocktakeCreateDTO.Item();
        i1.setGoodsId(51L);
        i1.setAssigneeId(10L);
        StocktakeCreateDTO.Item i2 = new StocktakeCreateDTO.Item();
        i2.setGoodsId(52L);
        i2.setAssigneeId(20L);
        dto.setItems(List.of(i1, i2));
        dto.setRemark("月末盘点");
        service.create(dto);

        ArgumentCaptor<BizStocktake> masterCaptor = ArgumentCaptor.forClass(BizStocktake.class);
        verify(bizStocktakeMapper).insert(masterCaptor.capture());
        BizStocktake master = masterCaptor.getValue();
        assertEquals(1, master.getStatus());
        assertTrue(master.getStocktakeNo().startsWith("ST"), "实际: " + master.getStocktakeNo());
        assertEquals(10L, master.getOperatorId());
        assertEquals("月末盘点", master.getRemark());
        assertNotNull(master.getOperationTime());

        ArgumentCaptor<BizStocktakeDetail> detailCaptor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper, times(2)).insert(detailCaptor.capture());
        List<BizStocktakeDetail> details = detailCaptor.getAllValues();
        BizStocktakeDetail d51 = details.stream().filter(d -> d.getGoodsId() == 51L).findFirst().orElseThrow();
        assertEquals("GD51", d51.getGoodsCode());
        assertEquals("板1", d51.getGoodsName());
        assertEquals("规格51", d51.getSpec());
        assertEquals("材质51", d51.getMaterial());
        assertEquals("个", d51.getUnit());
        assertEquals("material", d51.getGoodsType());
        assertEquals(100, d51.getBookQty());
        assertNull(d51.getActualQty());
        // D85：逐行负责人快照
        assertEquals(10L, d51.getAssigneeId());
        assertEquals("仓储管理员", d51.getAssigneeName());
        BizStocktakeDetail d52 = details.stream().filter(d -> d.getGoodsId() == 52L).findFirst().orElseThrow();
        assertEquals("product", d52.getGoodsType());
        assertEquals(20L, d52.getAssigneeId());
        assertEquals("仓储员工", d52.getAssigneeName());

        // D77 超管禁写守卫 + 仓储 admin 守卫
        verify(authzService).requireNotSuperAdminForBusinessWrite();
        verify(authzService).requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    @Test
    void create_rejectsWhenGoodsInOpenStocktake() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(51L, "GD51", "板1", "material", 100)));
        // 已存在未完结盘点单（status 1/2）且含 goods 51
        BizStocktake open = order(9L, 1);
        when(bizStocktakeMapper.selectList(any())).thenReturn(List.of(open));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(901L, 9L, 51L, null)));

        StocktakeCreateDTO dto = createDto(51L, 10L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(dto));
        assertTrue(ex.getMessage().contains("未完结"), "实际: " + ex.getMessage());
        verify(bizStocktakeMapper, never()).insert(any());
    }

    @Test
    void create_rejectsWhenAssigneeNotWarehouseMember() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(51L, "GD51", "板1", "material", 100)));
        when(bizStocktakeMapper.selectList(any())).thenReturn(List.of());
        stubWarehouseMembers();

        BusinessException ex = assertThrows(BusinessException.class, () -> service.create(createDto(51L, 999L)));
        assertTrue(ex.getMessage().contains("负责人"), "实际: " + ex.getMessage());
        verify(bizStocktakeMapper, never()).insert(any());
    }

    // ---------- entry ----------

    @Test
    void entry_writesActualQtyAndUsesMemberLevelAccess() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(101L);
        item.setActualQty(7);
        dto.setItems(List.of(item));
        service.entry(1L, dto);

        ArgumentCaptor<BizStocktakeDetail> captor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper).updateById(captor.capture());
        assertEquals(101L, captor.getValue().getId());
        assertEquals(7, captor.getValue().getActualQty());
        // D85：实际录入人盖章
        assertEquals(10L, captor.getValue().getCounterId());
        assertEquals("仓储管理员", captor.getValue().getCounterName());
        assertNotNull(captor.getValue().getCountTime());

        // 录入 = 仓储成员级（admin+员工，D84），超管禁写
        verify(authzService).requireNotSuperAdminForBusinessWrite();
        verify(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    @Test
    void entry_employeeEntersOwnRow() {
        when(authService.getUserInfo()).thenReturn(warehouseEmployee());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        BizStocktakeDetail own = detail(101L, 1L, 51L, null);
        own.setAssigneeId(20L);
        own.setAssigneeName("仓储员工");
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(own));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(101L);
        item.setActualQty(7);
        dto.setItems(List.of(item));
        service.entry(1L, dto);

        ArgumentCaptor<BizStocktakeDetail> captor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper).updateById(captor.capture());
        assertEquals(7, captor.getValue().getActualQty());
        assertEquals(20L, captor.getValue().getCounterId());
        assertEquals("仓储员工", captor.getValue().getCounterName());
    }

    @Test
    void entry_employeeRejectsRowAssignedToOthers() {
        when(authService.getUserInfo()).thenReturn(warehouseEmployee());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        // 行负责人 = admin(10)，员工(20) 录入应被拒（D85 限录本人行）
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(101L);
        item.setActualQty(7);
        dto.setItems(List.of(item));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.entry(1L, dto));
        assertTrue(ex.getMessage().contains("负责人"), "实际: " + ex.getMessage());
        verify(bizStocktakeDetailMapper, never()).updateById(any());
    }

    @Test
    void entry_rejectsWhenStatusNotCounting() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 2));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(101L);
        item.setActualQty(7);
        dto.setItems(List.of(item));
        assertThrows(BusinessException.class, () -> service.entry(1L, dto));
        verify(bizStocktakeDetailMapper, never()).updateById(any(BizStocktakeDetail.class));
    }

    @Test
    void entry_rejectsNegativeActualQty() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(101L);
        item.setActualQty(-1);
        dto.setItems(List.of(item));
        BusinessException ex = assertThrows(BusinessException.class, () -> service.entry(1L, dto));
        assertTrue(ex.getMessage().contains("负数"), "实际: " + ex.getMessage());
    }

    @Test
    void entry_rejectsDetailNotBelongingToOrder() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        StocktakeEntryDTO dto = new StocktakeEntryDTO();
        StocktakeEntryDTO.Item item = new StocktakeEntryDTO.Item();
        item.setDetailId(999L);
        item.setActualQty(7);
        dto.setItems(List.of(item));
        assertThrows(BusinessException.class, () -> service.entry(1L, dto));
    }

    // ---------- submit ----------

    @Test
    void submit_movesToPendingReviewWhenCounted() {
        // 员工提交（D84 修订放开成员级），D85 盖提交人章
        when(authService.getUserInfo()).thenReturn(warehouseEmployee());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(
                detail(101L, 1L, 51L, 7), detail(102L, 1L, 52L, null)));

        service.submit(1L);

        ArgumentCaptor<BizStocktake> captor = ArgumentCaptor.forClass(BizStocktake.class);
        verify(bizStocktakeMapper).updateById(captor.capture());
        assertEquals(2, captor.getValue().getStatus());
        assertNotNull(captor.getValue().getSubmitTime());
        // D85：提交人留痕
        assertEquals(20L, captor.getValue().getSubmitterId());
        assertEquals("仓储员工", captor.getValue().getSubmitterName());

        // 提交 = 仓储成员级（admin+员工，D84 修订：员工实盘完直接送审，审核仍收口 admin）
        verify(authzService).requireNotSuperAdminForBusinessWrite();
        verify(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    @Test
    void submit_rejectsWhenNothingCounted() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.submit(1L));
        assertTrue(ex.getMessage().contains("至少"), "实际: " + ex.getMessage());
        verify(bizStocktakeMapper, never()).updateById(any(BizStocktake.class));
    }

    // ---------- review ----------

    @Test
    void review_adjustsByRealtimeBookAndWritesThreeValues() {
        BizStocktake o = order(1L, 2);
        when(bizStocktakeMapper.selectById(1L)).thenReturn(o);
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, 8)));
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(51L, "GD51", "板1", "material", 10)));
        when(baseGoodsMapper.update(eq(null), any())).thenReturn(1);
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());

        service.review(1L);

        // 明细三值：快照10（不变）/ 实盘8 / 生效时账面=实时10 → 差异 -2（D82）
        ArgumentCaptor<BizStocktakeDetail> detailCaptor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper).updateById(detailCaptor.capture());
        BizStocktakeDetail updated = detailCaptor.getValue();
        assertEquals(10, updated.getFinalBookQty());
        assertEquals(-2, updated.getDiffQty());

        // 盘亏 → 扣库存（私有 decreaseStock 经 baseGoodsMapper.update）
        verify(baseGoodsMapper).update(eq(null), any());

        ArgumentCaptor<BizStocktake> masterCaptor = ArgumentCaptor.forClass(BizStocktake.class);
        verify(bizStocktakeMapper).updateById(masterCaptor.capture());
        assertEquals(3, masterCaptor.getValue().getStatus());
        assertEquals(10L, masterCaptor.getValue().getReviewerId());
        assertNotNull(masterCaptor.getValue().getReviewTime());
    }

    @Test
    void review_skipsUnscannedAndZeroDiffRows() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 2));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(
                detail(101L, 1L, 51L, null),   // 未盘行
                detail(102L, 1L, 52L, 5)));    // 实盘=实时账面 → 差异 0
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(52L, "GD52", "PTO153", "product", 5)));
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());

        service.review(1L);

        // 差异 0 → 不动库存
        verify(baseGoodsMapper, never()).update(eq(null), any());
        // 未盘行不写三值；差异 0 行写 finalBook/diff
        ArgumentCaptor<BizStocktakeDetail> captor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper, times(1)).updateById(captor.capture());
        BizStocktakeDetail updated = captor.getValue();
        assertEquals(102L, updated.getId());
        assertEquals(5, updated.getFinalBookQty());
        assertEquals(0, updated.getDiffQty());
    }

    @Test
    void review_rejectsWhenStatusNotPendingReview() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        assertThrows(BusinessException.class, () -> service.review(1L));
        verify(baseGoodsMapper, never()).update(eq(null), any());
    }

    @Test
    void review_throwsWhenShortageExceedsCurrentStock() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 2));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, 0)));
        when(baseGoodsMapper.selectBatchIds(any())).thenReturn(List.of(goods(51L, "GD51", "板1", "material", 5)));
        when(baseGoodsMapper.update(eq(null), any())).thenReturn(0); // 并发扣减导致不足
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());

        assertThrows(BusinessException.class, () -> service.review(1L));
        verify(bizStocktakeMapper, never()).updateById(any(BizStocktake.class));
    }

    // ---------- reject / cancel ----------

    @Test
    void reject_returnsToCountingWithReason() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 2));

        StocktakeRejectDTO dto = new StocktakeRejectDTO();
        dto.setReason("A区数量存疑，复盘");
        service.reject(1L, dto);

        ArgumentCaptor<BizStocktake> captor = ArgumentCaptor.forClass(BizStocktake.class);
        verify(bizStocktakeMapper).updateById(captor.capture());
        assertEquals(1, captor.getValue().getStatus());
        assertEquals("A区数量存疑，复盘", captor.getValue().getRejectReason());
    }

    @Test
    void cancel_fromCounting_setsCanceledWithReason() {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));

        StocktakeCancelDTO dto = new StocktakeCancelDTO();
        dto.setReason("建错范围");
        service.cancel(1L, dto);

        ArgumentCaptor<BizStocktake> captor = ArgumentCaptor.forClass(BizStocktake.class);
        verify(bizStocktakeMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getStatus());
        assertEquals("建错范围", captor.getValue().getCancelReason());
        // D85：取消人留痕
        assertEquals(10L, captor.getValue().getCancelerId());
        assertEquals("仓储管理员", captor.getValue().getCancelerName());
        // 取消零库存影响
        verify(baseGoodsMapper, never()).update(eq(null), any());
    }

    @Test
    void cancel_rejectsWhenCompleted() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 3));

        StocktakeCancelDTO dto = new StocktakeCancelDTO();
        BusinessException ex = assertThrows(BusinessException.class, () -> service.cancel(1L, dto));
        assertTrue(ex.getMessage().contains("取消"), "实际: " + ex.getMessage());
    }

    // ---------- goodsOptions ----------

    @Test
    void goodsOptions_derivesLastTimeAndOpenFlag() {
        LocalDateTime ts = LocalDateTime.of(2026, 9, 1, 10, 0, 0);
        when(baseGoodsMapper.selectList(any())).thenReturn(List.of(
                goods(51L, "GD51", "板1", "material", 100),
                goods(52L, "GD52", "PTO153", "product", 7)));
        Map<String, Object> row = new HashMap<>();
        row.put("goodsId", 51L);
        row.put("lastTime", ts);
        when(bizStocktakeMapper.selectLastStocktakeTimes()).thenReturn(List.of(row));
        when(bizStocktakeMapper.selectList(any())).thenReturn(List.of(order(9L, 2)));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(901L, 9L, 52L, null)));

        List<StocktakeGoodsOptionVO> options = service.goodsOptions(null);

        StocktakeGoodsOptionVO o51 = options.stream().filter(o -> o.getGoodsId() == 51L).findFirst().orElseThrow();
        assertEquals(ts, o51.getLastStocktakeTime());
        assertFalse(o51.getInOpenStocktake());
        StocktakeGoodsOptionVO o52 = options.stream().filter(o -> o.getGoodsId() == 52L).findFirst().orElseThrow();
        assertNull(o52.getLastStocktakeTime());
        assertTrue(o52.getInOpenStocktake());
        // 超管/仓储成员读权限（D84）
        verify(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    // ---------- getById / page ----------

    @Test
    void getById_computesSummaryCounts() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 3));
        BizStocktakeDetail counted = detail(101L, 1L, 51L, 12);
        counted.setFinalBookQty(10);
        counted.setDiffQty(2);
        BizStocktakeDetail shortRow = detail(102L, 1L, 52L, 6);
        shortRow.setFinalBookQty(10);
        shortRow.setDiffQty(-4);
        BizStocktakeDetail match = detail(103L, 1L, 53L, 10);
        match.setFinalBookQty(10);
        match.setDiffQty(0);
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(
                counted, shortRow, match, detail(104L, 1L, 54L, null)));

        StocktakeVO vo = service.getById(1L);

        assertEquals(4, vo.getTotalRows());
        assertEquals(3, vo.getCountedRows());
        assertEquals(1, vo.getUnscannedRows());
        assertEquals(1, vo.getOverRows());
        assertEquals(1, vo.getShortRows());
        assertEquals(1, vo.getMatchRows());
        assertEquals(4, vo.getDetailList().size());
        assertTrue(vo.getDetailList().get(3).getUnscanned());
    }

    @Test
    void page_usesWarehouseReadAccessAndReturnsVo() {
        com.baomidou.mybatisplus.extension.plugins.pagination.Page<BizStocktake> mpPage =
                new com.baomidou.mybatisplus.extension.plugins.pagination.Page<>(1, 10);
        mpPage.setRecords(List.of(order(1L, 1)));
        mpPage.setTotal(1);
        when(bizStocktakeMapper.selectPage(any(), any())).thenReturn(mpPage);
        lenient().when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of());

        StocktakeQueryDTO query = new StocktakeQueryDTO();
        PageResult<StocktakeVO> result = service.page(query);

        assertEquals(1, result.getTotal());
        assertEquals("盘点中", result.getRecords().get(0).getStatusText());
        verify(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    // ---------- export ----------

    @Test
    void export_blindOmitsBookColumn() throws Exception {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        byte[] bytes = service.export(1L, true);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Row header = wb.getSheetAt(0).getRow(0);
            String headers = headerToString(header);
            assertTrue(headers.contains("商品编码"), "实际: " + headers);
            assertTrue(headers.contains("实盘"), "实际: " + headers);
            assertFalse(headers.contains("账面"), "盲盘不得含账面列，实际: " + headers);
            // 数据行首列=商品编码（回填匹配键）
            assertEquals("GD51", wb.getSheetAt(0).getRow(1).getCell(0).getStringCellValue());
        }
    }

    @Test
    void export_revealIncludesBookColumn() throws Exception {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        byte[] bytes = service.export(1L, false);

        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertTrue(headerToString(wb.getSheetAt(0).getRow(0)).contains("账面"));
        }
    }

    private String headerToString(Row header) {
        StringBuilder sb = new StringBuilder();
        header.forEach(c -> sb.append(c.getStringCellValue()).append('|'));
        return sb.toString();
    }

    // ---------- importEntry ----------

    @Test
    void importEntry_appliesValidRowsAndSkipsBlankQty() throws Exception {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(
                detail(101L, 1L, 51L, null), detail(102L, 1L, 52L, null)));

        byte[] xlsx = buildImportSheet(new Object[][]{
                {"商品编码", "商品名称", "实盘数"},
                {"GD51", "板1", 7},
                {"GD52", "PTO153", null},   // 空白实盘行跳过（部分回填）
        });
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(xlsx));

        int applied = service.importEntry(1L, file);

        assertEquals(1, applied);
        ArgumentCaptor<BizStocktakeDetail> captor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper, times(1)).updateById(captor.capture());
        assertEquals(101L, captor.getValue().getId());
        assertEquals(7, captor.getValue().getActualQty());
        // D85：导入回填同样盖实际录入人章
        assertEquals(10L, captor.getValue().getCounterId());
        assertEquals("仓储管理员", captor.getValue().getCounterName());
        assertNotNull(captor.getValue().getCountTime());
    }

    @Test
    void importEntry_employeeRejectsRowsAssignedToOthers() throws Exception {
        // 员工导入：行负责人是 admin(10) 而非本人(20) → 收集错误整体拒绝（D85 限录本人行）
        when(authService.getUserInfo()).thenReturn(warehouseEmployee());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        byte[] xlsx = buildImportSheet(new Object[][]{
                {"商品编码", "商品名称", "实盘数"},
                {"GD51", "板1", 7},
        });
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(xlsx));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.importEntry(1L, file));
        assertTrue(ex.getMessage().contains("负责人"), "实际: " + ex.getMessage());
        verify(bizStocktakeDetailMapper, never()).updateById(any(BizStocktakeDetail.class));
    }

    @Test
    void importEntry_rejectsUnknownGoodsCode() throws Exception {
        when(authService.getUserInfo()).thenReturn(warehouseAdmin());
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));

        byte[] xlsx = buildImportSheet(new Object[][]{
                {"商品编码", "商品名称", "实盘数"},
                {"GDXX", "不存在", 3},
        });
        MultipartFile file = org.mockito.Mockito.mock(MultipartFile.class);
        when(file.getInputStream()).thenReturn(new ByteArrayInputStream(xlsx));

        BusinessException ex = assertThrows(BusinessException.class, () -> service.importEntry(1L, file));
        assertTrue(ex.getMessage().contains("GDXX"), "实际: " + ex.getMessage());
        verify(bizStocktakeDetailMapper, never()).updateById(any(BizStocktakeDetail.class));
    }

    // ---------- assign / assigneeOptions（D85） ----------

    @Test
    void assign_updatesAssigneeWhenCounting() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));
        stubWarehouseMembers();

        org.example.back.dto.StocktakeAssignDTO dto = new org.example.back.dto.StocktakeAssignDTO();
        dto.setDetailId(101L);
        dto.setAssigneeId(20L);
        service.assign(1L, dto);

        ArgumentCaptor<BizStocktakeDetail> captor = ArgumentCaptor.forClass(BizStocktakeDetail.class);
        verify(bizStocktakeDetailMapper).updateById(captor.capture());
        assertEquals(101L, captor.getValue().getId());
        assertEquals(20L, captor.getValue().getAssigneeId());
        assertEquals("仓储员工", captor.getValue().getAssigneeName());
        // 改派 = 仓储 admin 级
        verify(authzService).requireNotSuperAdminForBusinessWrite();
        verify(authzService).requireDeptAdminOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    @Test
    void assign_rejectsWhenNotCounting() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 2));

        org.example.back.dto.StocktakeAssignDTO dto = new org.example.back.dto.StocktakeAssignDTO();
        dto.setDetailId(101L);
        dto.setAssigneeId(20L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.assign(1L, dto));
        assertTrue(ex.getMessage().contains("盘点中"), "实际: " + ex.getMessage());
        verify(bizStocktakeDetailMapper, never()).updateById(any());
    }

    @Test
    void assign_rejectsWhenAssigneeNotWarehouseMember() {
        when(bizStocktakeMapper.selectById(1L)).thenReturn(order(1L, 1));
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(detail(101L, 1L, 51L, null)));
        stubWarehouseMembers();

        org.example.back.dto.StocktakeAssignDTO dto = new org.example.back.dto.StocktakeAssignDTO();
        dto.setDetailId(101L);
        dto.setAssigneeId(999L);
        BusinessException ex = assertThrows(BusinessException.class, () -> service.assign(1L, dto));
        assertTrue(ex.getMessage().contains("负责人"), "实际: " + ex.getMessage());
        verify(bizStocktakeDetailMapper, never()).updateById(any());
    }

    @Test
    void assigneeOptions_returnsWarehouseMembers() {
        stubWarehouseMembers();

        List<org.example.back.vo.StocktakeAssigneeOptionVO> options = service.assigneeOptions();

        assertEquals(2, options.size());
        org.example.back.vo.StocktakeAssigneeOptionVO admin = options.stream()
                .filter(o -> o.getUserId() == 10L).findFirst().orElseThrow();
        assertEquals("仓储管理员", admin.getRealName());
        assertEquals("admin", admin.getRole());
        org.example.back.vo.StocktakeAssigneeOptionVO emp = options.stream()
                .filter(o -> o.getUserId() == 20L).findFirst().orElseThrow();
        assertEquals("employee", emp.getRole());
        // 读权限 = 仓储成员 + 超管
        verify(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());
    }

    private byte[] buildImportSheet(Object[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("盘点");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    Object v = rows[r][c];
                    if (v == null) {
                        continue;
                    }
                    if (v instanceof Number n) {
                        row.createCell(c).setCellValue(n.doubleValue());
                    } else {
                        row.createCell(c).setCellValue(v.toString());
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    // ---------- D122：导出盘点表——盲盘/明盘列差异与读权限 ----------

    private void stubExportData() {
        when(bizStocktakeMapper.selectById(2L)).thenReturn(order(2, 1));
        BizStocktakeDetail d = detail(1L, 2L, 5L, 7);
        when(bizStocktakeDetailMapper.selectList(any())).thenReturn(List.of(d));
    }

    @Test
    void export_blindOmitsBookQtyColumn() throws Exception {
        stubExportData();

        byte[] bytes = service.export(2L, true);

        assertTrue(bytes.length > 4 && bytes[0] == 'P' && bytes[1] == 'K', "xlsx 非空");
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("实盘数", sheet.getRow(0).getCell(5).getStringCellValue());
            for (int c = 0; c <= 5; c++) {
                assertNotEquals("账面数", sheet.getRow(0).getCell(c).getStringCellValue());
            }
            assertEquals(7.0, sheet.getRow(1).getCell(5).getNumericCellValue(), 0.0001);
        }
    }

    @Test
    void export_openIncludesBookQtyColumn() throws Exception {
        stubExportData();

        byte[] bytes = service.export(2L, false);

        assertTrue(bytes.length > 4);
        try (XSSFWorkbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = wb.getSheetAt(0);
            assertEquals("账面数", sheet.getRow(0).getCell(5).getStringCellValue());
            assertEquals("实盘数", sheet.getRow(0).getCell(6).getStringCellValue());
            assertEquals(10.0, sheet.getRow(1).getCell(5).getNumericCellValue(), 0.0001);
            assertEquals(7.0, sheet.getRow(1).getCell(6).getNumericCellValue(), 0.0001);
        }
    }

    @Test
    void export_requiresWarehouseReadAccess() {
        doThrow(BusinessException.forbidden("仅仓储部门"))
                .when(authzService).requireDeptMemberOrSuperAdmin(eq(AuthzService.DEPT_WAREHOUSE), anyString());

        assertThrows(BusinessException.class, () -> service.export(2L, true));
        verify(bizStocktakeMapper, never()).selectById(2L);
    }
}
