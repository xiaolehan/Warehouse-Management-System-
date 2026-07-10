# 修复价格偏离审批：驳回状态流转 + 出库后误报"作废成功"

## 问题①：超管驳回后销售单未回到销售人员，仓储仍可见

### 根因
- `ApprovalService.reject`(后端)仅把审批单 status 置 3，**不回写销售单状态**；销售单仍 `confirm_status=1`、`biz_status=1`。
- `SalesService.page()`(后端)列表只过滤 `biz_status=1 AND is_deleted=0`，**不过滤审批状态** → 仓储在列表看到被驳回单。
- `ensurePriceDeviationApproved` 只在仓储点"确认出库"时才报"需超管审批"，驳回后单子继续躺在仓储列表。

### 用户期望（已确认）
超管驳回 → 销售单回到销售人员（可改价重提/删除），仓储列表不再显示；销售人员看到驳回原因。

### 方案（不改状态机，用动态审批状态过滤——改动最小、不碰 confirm_status 语义）
驳回后销售单 `approvalStatus=3`(从最新审批单动态算出，已有逻辑)。利用它：

#### 后端
1. **`SalesService.page()` 加角色感知过滤**：
   - 读权限已有 `requireSalesReadAccess`(销售+仓储可读)。在 page() 内取当前角色：
     - **仓储视角**：排除"最新审批=驳回(approvalStatus=3)"的单（仓储不应看到被退回给销售的单）。
     - **销售视角**：全部展示（含被驳回单，让销售处理）。
   - 实现：`buildLatestApprovalMap` 已按 bizId 取最新审批单 → page() 拿到 approvalMap 后，仓储角色时过滤掉 `approvalOrder.status==3` 的销售单。
2. **`SalesVO` 新增 `approvalRemark` 字段**：`toVO` 填充审批单的 `approveRemark`(超管驳回时填的原因)。
3. **`SalesService.getById()` 同步**：仓储查看被驳回单详情时，可由销售侧操作；仓储视角 getById 仍校验(列表已不可见，直调 API 边界防御：getById 对仓储角色也排除驳回单，返回 404/403)。
4. **不改 `ensurePriceDeviationApproved`**：仓储即便绕过列表直调 confirm，仍被拦截（已有兜底）。驳回单 approvalStatus=3(非2) → 拦截，行为正确。

#### 前端
5. **`SalesView.vue` 销售人员列表显示驳回原因**：当 `approvalStatus===3` 时，行内/状态列展示驳回原因 `approvalRemark`（el-tooltip 或状态 tag 旁小字）。

#### 改价重提（无需新接口）
- 销售人员对被驳回单：**删除当天单 + 重新建单**(D32 admin 可删当天单，已有 delete)。重新建单若仍偏离 → 重新触发偏离审批(走 create 现有逻辑)。不改价(仍偏离)重建也允许——重新走审批。
- 不新增 update 接口，降低风险。

## 问题②：出库后操作列误报"作废成功"

### 根因
`front/src/utils/bizDocumentState.js` 的 `resolveBizDocumentState`：
- 当 `approvalStatus===2`(已通过) 时，**不区分 requestAction 类型**，一律返回"作废成功"(void_red 则"作废并红冲成功")。
- 但价格偏离审批(`price_deviation_confirm`)通过后 approvalStatus 也=2 → 误判为"作废成功"。

### 方案
#### 前端
6. **`bizDocumentState.js` 修 `resolveBizDocumentState`**：approvalStatus=2 分支按 `approvalRequestAction` 区分：
   - `void` → "作废成功"
   - `void_red` → "作废并红冲成功"
   - `price_deviation_confirm` → **不返回作废态**(返回 null，让操作列走"已确认出库"等其他真实状态，或返回 `{label:'偏离已审批', type:'success'}` 视觉提示)。
- 已确认出库(`confirmStatus===2`)的单子操作列应显示"已确认出库"而非"作废成功"——确认出库按钮已隐藏(confirmStatus===2 时 v-if 不显示)，走 v-else 分支显示 resolveState。修后价格偏离已审批+已出库的单子，resolveState 返回 null → 显示"不可操作"或改文案。
   - **决策**：price_deviation_confirm 已通过且已出库 → 操作列显示"已出库"(成功态)；已通过但待出库 → 不显示作废文案(正常显示确认出库按钮)。

## 涉及文件

**后端**(2)：SalesService.java(page 角色过滤 + toVO 填 approvalRemark + getById 防御)、SalesVO.java(加 approvalRemark 字段)
**前端**(2)：bizDocumentState.js(区分 requestAction)、SalesView.vue(显示驳回原因 + 已出库文案)

## 验收（E2E）

### 问题①
1. 建偏离单(55%)→ 超管驳回(填驳回原因"价格过高")。
2. 仓储登录 → 销售列表 **看不到该单**。
3. 销售登录 → 列表看到该单 + 显示驳回原因"价格过高"。
4. 销售删除被驳回单 + 改价重建(正常价)→ 无偏离审批，仓储确认出库成功。
5. 仓储绕过列表直调被驳回单 confirm → 仍被拦(兜底)。

### 问题②
6. 建偏离单 → 超管通过 → 仓储确认出库 → 操作列显示"已出库"(或正常态)，**不再显示"作废成功"**。
7. 正常作废审批(void)通过的单 → 操作列仍正确显示"作废成功"(回归不破)。

## 踩坑预防（CLAUDE.md）
- 改 SalesService 后 `./mvnw clean compile`（防 JDT stale）。
- 重启后端 `fuser -k 8080/tcp` + 必要时 `kill -9`。
- 角色感知过滤不能误伤销售视角（销售要看全，含驳回单）。
- bizDocumentState 改动回归测作废审批(void/void_red)不破。
