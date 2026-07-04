# CLAUDE.md — WMS 项目协作须知

> 本文件由 Claude Code 在每次会话自动加载。记录项目运行方式与本轮踩过的运维坑，**务必遵守，避免重蹈覆辙**。
> 规划文件：`task_plan.md`（阶段/决策）、`progress.md`（会话日志，倒序）、`findings.md`（调研）。

## 项目概览

- 技术栈：Spring Boot (back/) + Vue3/Vite (front/)，MySQL 8.0。
- 数据库：`warehouse_management`，账号 `wms_user` / `wms_pass`。
- 端口：后端 `8080`（context-path `/api`），前端 `5173`（Vite 代理 `/api → 8080`）。
- 默认账号：`warehouse_admin` / `purchase_admin` / `sales_admin` 等，密码统一 `123456`。

## 本地启动

```bash
# 后端（后台，日志 /tmp/wms-backend.log）
cd back && nohup ./mvnw spring-boot:run > /tmp/wms-backend.log 2>&1 &

# 前端（后台，日志 /tmp/wms-frontend.log）
cd front && nohup npm run dev > /tmp/wms-frontend.log 2>&1 &

# 数据库变更：db.sql 追加 DDL 后，本地执行
mysql -u wms_user -pwms_pass warehouse_management < /tmp/xxx.sql
```

## 运维教训（本轮踩坑，禁止再犯）

### 1. 切换分支 / merge 前必须先停 dev 服务器
- **现象**：`git checkout`/`merge`/`reset` 会让工作树文件先变成目标内容（可能临时删除新增文件）再恢复。运行中的 **Vite 模块图会变陈旧**（页面"功能消失"，因为 Vite 服务的是 checkout 中途的旧 bundle）；**Spring devtools 会出现 `ClassCastException: X cannot be cast to X`**（两个 `RestartClassLoader` 实例并存）。
- **规则**：任何跨 commit 的 `git checkout`/`merge`/`reset` 操作前，先停后端 + 前端；操作完再重启，并用 `curl` 验证。若已发生，重启两端即可恢复。
- **停服务**：后端 `fuser -k 8080/tcp`，前端 `fuser -k 5173/tcp`（按端口杀，避免下面的 pkill 陷阱）。

### 2. `pkill -f "<pattern>"` 自杀陷阱
- **现象**：`pkill -f "spring-boot:run"` 会匹配到**包含该字符串的当前 shell 命令本身**（如同条命令里的 nohup 行），把执行中的 shell 杀掉（exit 144 = SIGTERM），命令中途夭折。
- **规则**（三选一）：
  - 用字符类规避：`pkill -f 'spring-boot[:]run'`（正则匹配进程的 `spring-boot:run`，但不匹配命令里字面量 `spring-boot[:]run`）。
  - 按端口杀：`fuser -k 8080/tcp` / `fuser -k 5173/tcp`。
  - 把 kill 和 start 拆成两次独立 shell 调用。
- **判活注意**：`pgrep ... | head && echo` 不可靠（`&&` 判的是 `head` 的退出码，不是 pgrep 的）。直接看 `pgrep` 的输出与退出码，或用 `ss -tln | grep :端口`。

### 3. 非交互 shell 推送需预配 GitHub 认证
- **现象**：WSL 非 shell 未配 credential helper / SSH key / token 时，`git push` 报 `could not read Username for 'https://github.com'`。
- **规则**：
  - 优先用 **VS Code 源代码管理面板**推送（自带 GitHub 浏览器登录，不走 shell 认证）。
  - 或 `git config --global credential.helper store` 后做一次交互登录。
  - 临时用 PAT：用一次性 URL `https://x-access-token:<PAT>@github.com/owner/repo.git` 推送，**不写入 git config**；输出经 `sed "s|$TOKEN|<redacted>|g"` 过滤；用完 `rm` token 文件；提醒用户去 GitHub 撤销该 PAT（已暴露在对话/命令中）。

### 4. fine-grained PAT 必须显式给写权限
- **现象**：fine-grained PAT 可能只读——`git ls-remote` 成功，但 `git push` 403 `Permission to <repo>.git denied to <user>`。
- **规则**：建 fine-grained PAT 时 Repository permissions → **Contents = Read and Write**；或用 classic PAT 勾 `repo` scope。推送前先 `git ls-remote` 验证可读，再 push 验证可写。

### 5. glm-5.2 bash 安全分类器会临时不可用
- **现象**：分类器宕机时，**写/网络类 bash（git push / pkill / mysql / curl 写）全部被拦**，`CronCreate` 也被拦；但**只读操作（Read / grep / git log / ss）仍可用**。
- **规则**：分类器宕机期间继续做只读调研；写操作等恢复后重试，或让用户用 VS Code / GitHub 网页完成（如 PR 合并、分支删除）。不要连续空跑被拦的写命令。

### 6. GitHub PR 合并后要同步本地 main + 清理分支
- **现象**：在 GitHub 网页合并 PR 后，`origin/main` 前进，本地 `main` 落后（`behind N`），本地/远程 feature 分支未删。
- **规则**：PR 合并后执行：
  ```bash
  git fetch origin --prune
  git checkout main && git merge --ff-only origin/main   # 本地 main 同步
  git branch -d <feature-branch>                          # 删本地分支（已合并才允许 -d）
  git push origin --delete <feature-branch>               # 删远程分支（若未被 GitHub 自动删）
  ```

## 验证习惯（改动后必做）

- **后端**：`./mvnw compile`（编译）→ 重启 → curl E2E（登录→建单→列表→权限负测→清理）。库存类操作测试数据用完即清理、恢复原库存。改代码后若遇运行时 `Unresolved compilation problem`（javac 增量跳过 + IDE JDT 编译生成带错误标记的 class 残留 target），用 `./mvnw clean compile` 强制全编译。重启后确认 8080 为新 pid（`fuser -k 8080/tcp` 有时未杀净残留 java，需 `kill -9 <pid>` 强制清理再启动）。
- **前端**：`npm run build`（捕获编译错误）→ Vite 代理 E2E（或 dev HMR 手测）。
- **跨 commit git 操作后**：重启 dev 服务器 + 浏览器硬刷新（Ctrl+Shift+R）+ 重新登录（后端重启会使旧 session token 失效）。
- **权限改写后 E2E 必须覆盖目标角色实际进页面场景**：不仅测该角色直调操作 API（如 confirm-receive），必须测其加载列表（page）、查看详情（getById）等读接口。读权限（`requireXxxReadAccess`）与写权限（`requireXxxModuleAccess`）常分开，改一处易漏另一处——曾因 `page()` 仍用仅采购权限，导致仓储进"进货入库确认"页 403。

## 关键约束

- 客户需求唯一来源：`document/wms_v1.docx`（不考虑上一轮其他文档）。
- 商品资料管理 = 主数据（每种商品一条，名称唯一）；补库存走入库交易（进货/采购申请/生产入库），**不在商品资料管理重复添加**。
- 库存变更的唯一入口是各业务 Service 的 `increaseStock`/`decreaseStock`，每个业务模块各有一份私有助手（非公共 StockService）。
- 站内消息生命周期绑定（D21 范式）：业务单据发消息必须带 `biz_type`/`biz_id`（用 `sendToDeptAdminsWithBiz`，**不要**用无 biz 的 `sendToDeptAdmins`），并在单据撤销/作废/终态时调 `MessageService.revokeUnreadByBiz(bizType, bizId)` 撤未读消息，避免"有通知无单据"悬挂。新增业务模块发消息务必接全（销售单 D21、销售退货 D22、采购申请 D25 均已接，新模块照此）。
