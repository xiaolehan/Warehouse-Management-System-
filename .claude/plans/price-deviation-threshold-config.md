# 修复超管价格偏离审批入口 + 新增偏离阈值超管可配置

## 根因

### 问题①：超管点"价格偏离审批"被拦回首页
- 路由守卫 `router/index.js:214`：超管访问不在 `SUPERADMIN_ALLOWED_PATHS` 白名单的路径，弹"超级管理员仅开放首页与超管中心模块"并跳回 `/home`。
- `/system/void-approval`（价格偏离审批页）**不在白名单**；且该路由 meta `roles: ['admin']` 不含 `superadmin`。
- 但菜单项 `layout/index.vue:109` 把这个入口放在**超管菜单区** → 超管看得到菜单、点进去被守卫拦回首页。这就是用户看到的"功能不对"。
- 注：审批功能本身正确（`ensurePriceDeviationApproved` 解锁逻辑无误），是前端路由权限配置漏了这个路径。

### 问题②：偏离阈值写死、无设置入口
- 阈值 `0.05` 硬编码三处：后端 `SalesService:47` 常量、前端 `SalesView:210` 的 `>5`、审批单文案"超 5% 阈值"（`SalesService:523`）。
- 无 `sys_config` 表、无超管设置入口。task_plan D30 当初写"常量先简，后续可配置"，现兑现。
- 用户已选：独立"系统参数"页放阈值设置。

## 方案

### A. 修复超管进不去价格偏离审批（前端，2 处）

1. `front/src/router/index.js:5` `SUPERADMIN_ALLOWED_PATHS` 加入 `'/system/void-approval'`。
2. `front/src/router/index.js:143` `SystemVoidApproval` 路由 meta 改为 `{ roles: ['admin', 'superadmin'], deptCodes: ['warehouse'] }`。
   - `hasDeptAccess`/`hasRole`（auth.js）对 superadmin 放行 → 超管能进；仓储 admin 走 meta 分支不受白名单影响，照常进（作废审批仍归仓储）。

### B. 新增偏离阈值超管可配置（独立系统参数页）

#### 后端（范式对齐 IpPolicy/SecurityController）
1. **db.sql** 追加 `sys_config` 表 DDL + 本地执行 CREATE：
   - 列：`id`, `config_key`(VARCHAR 唯一), `config_value`, `config_name`, `remark`, `updater_id`, `updater_name`, `create_time`, `update_time`, `is_deleted`（逻辑删除，utf8mb4_unicode_ci）。
   - 种子：`config_key='price_deviation_threshold'`, `config_value='0.05'`, `config_name='销售价格偏离阈值'`。
2. **Entity** `SysConfig.java`（`@TableName("sys_config")`, `@TableLogic isDeleted`）。
3. **Mapper** `SysConfigMapper extends BaseMapper<SysConfig>`。
4. **Service** `SysConfigService`：
   - `volatile BigDecimal priceDeviationThreshold` + `@PostConstruct` 启动从库加载（读频繁，每次建销售单都读，避免查库）。
   - `getPriceDeviationThreshold()` 返回缓存值。
   - `listAll()` 超管查看全部配置。
   - `updatePriceDeviationThreshold(BigDecimal value, operator)`：`requireSuperAdmin`；校验 `0 < value < 1`；更新库 + 刷新缓存。
5. **Controller** `SystemConfigController`（`/system/configs`）：
   - `GET /system/configs`（列表，超管）。
   - `GET /system/configs/price-deviation-threshold`（读阈值，**任意已登录用户可读**——建单前端提示用，阈值非敏感）。
   - `PUT /system/configs/price-deviation-threshold`（更新，超管，`@PreventDuplicateSubmit`）。
6. **改 SalesService**：
   - 删 `PRICE_DEVIATION_THRESHOLD` 常量；注入 `SysConfigService`。
   - `isPriceDeviation` / `createPriceDeviationApproval` 改读 `sysConfigService.getPriceDeviationThreshold()`。
   - 审批单 `requestReason` 文案动态拼阈值（"超 {threshold×100}% 阈值"）。

#### 前端
1. **新建** `front/src/views/system/SystemConfigView.vue`：当前阈值展示 + `el-input-number`（0–100% 区间）+ 保存。对齐 `SecurityIpPolicyView` 的 el-card 范式，单值设置更简。
2. **router** 新增 `/system/config` 路由（`SystemConfigView`, `meta: { roles: ['superadmin'] }`）+ `SUPERADMIN_ALLOWED_PATHS` 加 `'/system/config'`。
3. **layout** 超管菜单（`/system/super-admin-center` 子菜单）加"系统参数"项（置于价格偏离审批附近）。
4. **api** 新建 `front/src/api/config.js`：`getPriceDeviationThresholdAPI` / `updatePriceDeviationThresholdAPI`。
5. **改 SalesView.vue**：`isPriceDeviated` 的 `>5` 改为 `> threshold`（建单对话框打开时拉后端阈值，缓存到组件 ref）；提示文案动态。

### C. 验收（E2E，curl + 前端 build + Vite 代理）
1. 超管登录 → 点"价格偏离审批" → 正常进入列表（**不弹提示、不跳首页**）。
2. 超管 → "系统参数" → 改阈值 10% → 保存成功，回显 10%。
3. `sales_admin` 建单：偏离 6%（<10%）→ 不提示需审批、无审批单；偏离 12%（>10%）→ 提示需超管审批、生成审批单、文案"超 10% 阈值"。
4. 超管审批通过 → 仓储 confirm 解锁扣库存（链路不变，复用 D29）。
5. 权限：`warehouse_admin` PUT 阈值 → 403；非超管 GET 阈值 → 200。
6. 重启后端 → 阈值仍为 10%（`@PostConstruct` 从库加载，验证持久化）。
7. 测试数据清理（偏离销售单 / 审批单 / 消息 / 库存回滚）。

### D. 踩坑预防（CLAUDE.md 已记，本轮遵守）
- 改 SalesService 后 `./mvnw clean compile`（防 JDT stale class 残留）。
- 重启后端前 `fuser -k 8080/tcp` + 必要时 `kill -9 <pid>` 清残留 java。
- 阈值缓存更新后同步刷新 volatile 变量，避免读到旧值。
- 跨文件 git 操作后重启 dev + 浏览器硬刷新 + 重新登录。

## 涉及文件清单

**后端**（6）：db.sql、SysConfig.java、SysConfigMapper.java、SysConfigService.java、SystemConfigController.java、SalesService.java（改）
**前端**（5）：SystemConfigView.vue（新）、router/index.js（改2处+新路由）、layout/index.vue（改菜单）、api/config.js（新）、SalesView.vue（改）
