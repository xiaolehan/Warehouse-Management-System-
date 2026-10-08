# ADR-0023: 四部门同权开放 — 员工 = 同部门管理员 − 排除清单

日期：2026-10-08　|　状态：已采纳　|　关联：ADR-0021（盲盘/价格偏离审批）、ADR-0006（消息路由）、D141/D142/D143（会话 69）

## 背景

四部门业务页面此前全部挂 `@RequireAdmin` 切面（任意部门 admin∨superadmin），员工角色
只有本部门基础菜单（领料打卡/盘点录入/邮箱），读不到本部门业务单据列表。用户需求四条：
仓储/销售/采购端把除发布与用户部门管理外的页面和权限开放给本部门员工（销售端另排除
价格偏离审批与系统参数）；生产管理员补齐与其他部门管理员一致的发布功能。开放点遍布
13 个控制器、11 个视图，需要一条可复用的口径，而不是逐处特判。

## 决策

1. **开放口径为「减法继承」：员工 = 同部门管理员 − 排除清单。** 排除项天然由
   保留的 @RequireAdmin 注解与 service 层 admin 级守卫表达，不建权限配置表、
   不加「仅本人数据」维度（部门级数据同权）。四层同步：页面按钮 v-permission、
   路由 meta、后端守卫、侧边栏菜单。
2. **守卫放宽范式（机械替换三类）：**
   `requireDeptAdminOrSuperAdmin → requireDeptMemberOrSuperAdmin`、
   `hasDeptAdminOrSuperAdminAccess → hasDeptMemberOrSuperAdminAccess`、
   `isDeptAdmin(X) → isDeptMember(X)`。member 谓词包含 admin，旧管理员正测不破坏。
   controller 摘除 @RA 后守卫必须下沉 service 层，权限判断不留空白。
3. **特例口径（维持既有边界）：**
   - 销售员工挡「作废审批」页：路由 meta 新增 `forbiddenRolesForDept`（按部门排除角色，
     router.beforeEach 走 checkRouteAccess 自动生效），后端 requireApprovalModuleAccess
     兜底 403——价格偏离审批（D120/D121 销售管理员专属）不得经作废审批页绕开；
   - 审批单页可见范围由 warehouse∨sales admin∨superadmin 放宽为
     warehouse member∨sales admin∨superadmin；
   - listPendingVoidBizIds 摘 @RA 后补 service 守卫（四类单据页 admin+员工都需要冻结徽标）；
   - 作废审批发起权（ensureRequesterCanSubmitApproval）同步放宽 admin+员工——
     员工可建销售单/销售退货/采购申请，自然可对本人单据发起作废申请。
4. **盘点与消息维持现状（grilling Q3/Q4/Q7）：** 盲盘 D119 isBlindViewer 零改动
   （员工录入链路本就强制盲盘）；站内消息路由仍只发部门管理员；盘点建单/取消/指派
   开放员工，审核通过/驳回保留管理员。
5. **D142 生产管理员发布 = 纯前端补菜单**（工作要求+公告管理两个子菜单）——
   后端 @RequireAdmin 语义即「任意部门 admin」，无需后端改动。
6. **D143 留痕补 10 处 @AuditLog**（物料管理 4 + BOM 管理 5 + 生产领料批量撤销 1），
   与既有留痕机制（@AuditLog + 单据操作人字段）同源，员工与管理员操作一视同仁。

## 后果

- 正向：四部门员工从「只看本部门基础菜单」升级为「同部门管理员全量工作台」，
  排除项（发布/用户部门管理/价格偏离审批/系统参数）口径清晰且由守卫天然表达；
  留痕无死角。旧管理员路径零回归（member 谓词包含 admin）。
- 负向：13 控制器守卫口径变化，负测语义大面积翻转（本会话 8 个测试类 mock 方法名
  同步改名）；数据范围仍是部门级全量——员工可见本部门全部单据而非仅本人；
  新增模块接入时须遵循「守卫用 member 口径 + 排除项保留 admin 守卫」的范式。
- 备选否决：①权限配置表（角色×页面矩阵）——灵活但重，本系统角色/部门语义稳定，
  减法继承零配置成本且排除项由代码守卫强制，不存在配置漂移；②「仅本人数据」维度
  （数据行加 operator 过滤）——用户明确不需要（Q2），且与跨部门协作认领机制冲突。

