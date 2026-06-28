# 会话进度日志 (Progress)

> 按时间倒序记录每次会话的工作内容、产出与下一步计划。
> 会话中断或执行 `/clear` 后可据此恢复上下文。

---

## 会话 1 — 2026-06-28

### 已完成
- 创建三个规划文件：`task_plan.md`、`findings.md`、`progress.md`
- 初步了解项目结构：前后端分离（Spring Boot + Vue），仓库管理系统含 AI 助手能力

### 本轮工作（2026-06-28）
- 任务：阅读 `document/` 文档，整理新的二次开发改造参考文档
- 阅读：`document/WMS系统改造参考资料整理.md`（客户需求原文）、`projectmd/{project,back,front}.md`（当前系统结构）、`AImd/index.md`、`db.sql` 表清单
- 产出：`document/wms系统改造参考参考资料.md`（面向二次开发，12 节）
  - 客户业务背景 + 10 项待确认问题
  - 当前系统现状（技术栈/22 张表/角色菜单矩阵）
  - 数据模型基线 + 11 张建议新增表
  - 差距分析（12 个业务域 × 当前 vs 需求 × 优先级）
  - 三阶段改造路线图 + 8 个模块改造方案 + 接口规划 + 组织权限重构 + AI 复用 + 验收口径 + 风险

### 本地部署（2026-06-28）
- 环境：WSL2 Linux，Java 17、Node 18、MySQL 8.0（运行中），Maven 用项目自带 mvnw
- 数据库：`wms_user/wms_pass` 已可连，库 `warehouse_management` 已存在，22 张表 + 3 视图齐全，11 个默认账号（密码 123456）
- 后端：`./mvnw spring-boot:run`（后台，日志 `/tmp/wms-backend.log`），端口 8080，context-path `/api`，启动成功（Sa-Token 1.37、MyBatis-Plus 3.5.5、知识库 12 文档）
- 前端：`npm run dev`（后台，日志 `/tmp/wms-frontend.log`），端口 5173，Vite 代理 `/api → 8080`
- 验证：`http://localhost:5173/` 返回 200；登录接口 `POST /api/auth/login` 返回 token

### 下一步
- 等待用户确认待澄清问题（Q1–Q10），尤其是组织权限重构（Q9）与多仓决策（Q10）
- 进入具体模块开发时，按 §5 路线图阶段 1 拆解子任务
- 注意：后端/前端以后台进程运行，重启需 `kill` 旧进程或用 `pkill -f spring-boot:run` / `pkill -f vite`

### 改造参考文档重做（2026-06-28）
- 背景：`document/` 下原有 5 个文件被删，仅留 `wms_v1.docx`（约 530 字，精简版 Brief，补全关键数字 2000㎡ / 500 万物料/年）
- 用户要求：仅基于 `wms_v1.docx` 做二次开发参考，不考虑上一轮其他文档
- 产出：`document/wms系统改造参考参考资料.md`（10 节）
  - §1 客户业务（仅来自 wms_v1：工作流程 3 段 + 5 痛点 + 3 目标 + 规模约束）
  - §2 当前系统现状（来自 projectmd/db.sql/AImd）
  - §3 差距分析（10 项 × 优先级），核心结论：缺销售→仓库→采购协同链路、生产领料链路、库存治理链路
  - §4 三阶段路线图 / §5 七个模块改造方案 / §6 接口规划 / §7 数据模型扩展 / §8 风险 / §9 八项待确认问题
- 关键边界：客户需求仅用 wms_v1；不纳入售后表单、组织架构、序列号编码规则等上一轮内容

### 生产领料模块任务拆解（2026-06-28）
- 用户选定：先拆生产领料模块（P0，纯新增、风险最低）
- 调研：通读 `BizSales`（entity/dto/vo/service/controller）作为实现范式参照；确认 AuthzService 部门常量、CodeGenerator、库存增减方法位置、前端 business 视图与 api 结构
- 产出：
  - `projectmd/生产领料模块开发任务清单.md`（后端 B1–B9 + 前端 F1–F5 + 待确认 Q1–Q5 + 验收标准）
  - `task_plan.md` 更新为阶段 1 正式任务，含 5 条关键决策
- 设计要点：单表单行（对齐 biz_sales）；状态机 待发料→已发料→已完成/已驳回；退料直接入库；发料/驳回收口仓储管理员；复核环节解决错领漏领
- 下一步：等待用户确认是否动工，或先澄清 Q1–Q5

### 生产领料模块实现（2026-06-28，阶段 1 完成）

**Q1–Q5 确认结论：**
- Q1 申请限定销售/仓储 admin；Q2 多商品主从表；Q3 可选关联销售单；Q4 退料需仓储确认（统一状态机）；Q5 本期不打印

**后端实现（B1–B9）：**
- B1 建表 `biz_pick_list` + `biz_pick_list_detail`（db.sql 追加 + 本地库执行）
- B2/B3 Entity + Mapper（BizPickList / BizPickListDetail，去掉冗余 @Mapper 对齐 @MapperScan 风格）
- B4 DTO（PickListSaveDTO 含 @Valid 嵌套明细 / Detail / Query / Reject）
- B5 VO（PickListVO 含 details + statusText/pickTypeText / PickListDetailVO）
- B6 CodeGenerator.pickListNo()（注：曾被外部编辑回退，已重新添加）
- B7 PickListService：page/getById/create/issue/confirm/reject/delete；PICK/SUPPLY 扣库存任一缺料整单回滚，RETURN 入库；权限 requireAnyDeptAdminOrSuperAdmin(SALES,WAREHOUSE) 申请 / requireDeptAdminOrSuperAdmin(WAREHOUSE) 发料驳回 / 申请人本人确认
- B8 PickListController `/business/pick-lists/*`，@RequireAdmin + @AuditLog + @PreventDuplicateSubmit
- B9 编译通过，devtools 自动重启

**前端实现（F1–F5）：**
- F1 `api/pickList.js`（7 个接口）
- F2 `views/business/PickListView.vue`（查询/列表/新增多明细/详情/驳回对话框，v-permission 控制发料驳回仅仓储）
- F3 router 加 `/business/pick-list`（deptCodes sales+warehouse）；layout 销售/仓储菜单各加"生产领料"（Box 图标）
- F4 复用全局 v-permission
- F5 `npm run build` 通过；Vite 代理 E2E 全 200

**E2E 验收（全部通过）：** 多商品建单→发料扣库存→确认收货；退料回流入库；缺料整单回滚；销售 admin 发料 403；仓储驳回。测试数据已清理。

**坑点记录：**
- `CodeGenerator.pickListNo()` 被外部编辑回退导致运行时 NoClassDefFoundError/编译问题，已重新添加并验证
- goods options 返回 {id,name} 非 {id,goodsName}，前端选项 label 已修正
- 前端未持久化 userId，确认收货按钮改用 applicantName === realName 判断，后端做权威校验

**下一步：** 阶段 1 完成。可启动阶段 2（销售下单协同）或阶段 3（缺货识别与采购触发）。

### .gitignore 配置规范化（2026-06-28）
- 用户要求：只 push 代码，忽略编译产物
- 规范化三个 .gitignore：
  - 根 `.gitignore`：清理临时噪音条目（tmpclaude-*、front.pen、develop-doxc 等），补 `uploads/` 运行时产物、`.vite/`、Eclipse 文件、`*.tmp/*.bak`
  - `back/.gitignore`：补 env、日志、临时文件
  - `front/.gitignore`：**新建**（此前不存在），覆盖 node_modules/dist/.env/.vite/日志/编辑器
- 验证通过：`back/target`、`front/dist`、`front/node_modules`、`front/.vite`、`back/uploads` 均被 `git check-ignore` 命中；新增源码未被忽略；0 个编译产物被 git 跟踪
- **修复 db.sql 回退问题**：发现 db.sql 中领料建表 DDL 被外部编辑回退（与 CodeGenerator 同样情况），grep 计数为 0；已重新追加 `biz_pick_list` + `biz_pick_list_detail` DDL（47 行），git diff 确认落盘
- 注意：`application.properties` 含本地开发密码（wms_user/wms_pass）被原仓库跟踪，非本次 gitignore 范围；如需保护建议改环境变量注入
