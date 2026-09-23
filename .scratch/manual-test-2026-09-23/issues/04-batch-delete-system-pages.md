# 04 · 批量删除—系统页（用户/员工/公告/工作要求/IP 管控）

Type: task
Status: ready-for-agent
Blocked by: 02（共用 BatchDeleteResultVO 与前端范式）

同 02 号票定案，本票覆盖系统页 5 页。各页 disabled 逻辑（如 UserView canManageRow、NoticeView canManage）在批量场景的口径：批量删除按钮显隐沿用页面编辑权；已选中但不可管的行进失败明细（尽力而为）。

## 范围（5 页）

| 页面 | 单删现状 | 批量端点 |
|---|---|---|
| 用户管理 UserView | UserManageService.delete + canManageRow（超管保护等） | /system/users/batch-delete |
| 员工管理 EmployeeView | EmployeeService.delete | /system/employees/batch-delete |
| 公告管理 NoticeView | NoticeService.delete + canManage | /system/notices/batch-delete |
| 工作要求 WorkRequirementView | WorkRequirementService.delete | /system/work-requirements/batch-delete |
| IP 安全管控 SecurityIpPolicyView | SecurityIpPolicyService.delete | /system/ip-policies/batch-delete |

## 统一约定
- 守卫一次 + 逐行单删同款校验（尽力而为）；超管保护/自删保护等逐行进 failures。
- 已有批量端点的登录日志/操作日志页不动。
- 单测：守卫负测 + 混合成败（含不可管行）。

## 验收
- 5 页批量删除可用；不可管行（如超管账号）进失败明细且不被删除。
