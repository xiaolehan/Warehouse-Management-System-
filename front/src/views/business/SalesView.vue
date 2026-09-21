<template>
  <div class="sales-container">
    <el-card>
      <div class="search-box">
        <div class="top-right-help">
          <span class="help-label">作废:</span>
          <el-tooltip content="当天未生效单据可直接删除；已生效或历史单据的作废会提交给仓储管理员审批，通过后才执行。" placement="left">
            <el-icon class="void-help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
          <span class="help-label">冲抵:</span>
          <el-tooltip content="冲抵单=作废时系统生成的负数留痕记录（数量/金额为负），用于抵消原单的库存与金额；该功能已停用，如出现即为历史数据，不可删除、不可再作废，无需操作。" placement="left">
            <el-icon class="void-help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <el-form :inline="true" :model="searchForm">
          <el-form-item label="出库商品">
            <el-input v-model="searchForm.keywords" placeholder="请输入出库商品" clearable></el-input>
          </el-form-item>
          <el-form-item label="客户公司名">
            <el-input v-model="searchForm.customerName" placeholder="请输入客户公司名" clearable></el-input>
          </el-form-item>
          <el-form-item label="销售日期">
            <el-date-picker
              v-model="searchForm.dateRange"
              type="daterange"
              range-separator="至"
              start-placeholder="开始日期"
              end-placeholder="结束日期"
              value-format="YYYY-MM-DD"
            />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
            <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
            <el-button v-permission="{ roles: ['admin', 'employee'], deptCodes: ['sales'] }" type="success" :icon="Plus" @click="handleAdd">新建销售单</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="tableData" border style="width: 100%" v-loading="loading" :row-class-name="shortageRowClass">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="salesNo" label="销售单号" width="150" />
        <!-- D110：一单 N 个成品行，列表汇总展示「首品名 等 N 种」，明细进详情 -->
        <el-table-column prop="goodsSummary" label="出库商品" min-width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="客户公司名" width="140" show-overflow-tooltip />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column prop="totalQuantity" label="销售数量" width="100" />
        <!-- D110：仓储视角行级缺货标识——任一明细行缺货即整单标红并汇总展示（出库整单硬校验） -->
        <!-- 仅待出库单展示（review 修复）：已确认出库/作废单的 shortage 是当前库存回看，不代表仍未交付 -->
        <el-table-column v-if="isWarehouseUser" label="缺货明细" width="220">
          <template #default="scope">
            <span v-if="isShortageRow(scope.row)" class="stock-shortage-text">
              {{ shortageLines(scope.row).map((d) => `${d.goodsName} 需${d.quantity}/现存${d.stock ?? 0}`).join('；') }}
            </span>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="showPrice" prop="avgPrice" label="销售均价(元)" width="120" />
        <el-table-column v-if="showPrice" prop="totalAmount" label="销售总额(元)" width="120" />
        <el-table-column v-if="showPrice" label="是否含税" width="100" align="center">
          <template #default="scope">
            <el-tag :type="Number(scope.row.taxIncluded) === 1 ? 'success' : 'info'" size="small">
              {{ Number(scope.row.taxIncluded) === 1 ? '含税' : '不含税' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="salesDate" label="销售日期" width="180" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="确认状态" width="140">
          <template #default="scope">
            <!-- D97：已作废/已冲抵单据状态列直接展示终态，避免与确认状态歧义 -->
            <el-tag v-if="scope.row.bizStatus === 2" type="info" size="small">已作废</el-tag>
            <el-tooltip v-else-if="scope.row.bizStatus === 3" content="作废时系统生成的负数冲抵记录，用于抵消原单的库存与金额" placement="top">
              <el-tag type="info" size="small">已冲抵</el-tag>
            </el-tooltip>
            <el-tag v-else :type="scope.row.confirmStatus === 2 ? 'success' : 'warning'" size="small">
              {{ scope.row.confirmStatusText || (scope.row.confirmStatus === 2 ? '已确认出库' : '待仓库确认') }}
            </el-tag>
            <el-tag v-if="voidPendingIds.has(scope.row.id)" type="warning" size="small" style="margin-left: 4px">作废审批中</el-tag>
            <el-tooltip
              v-if="isPriceDeviationRejectedRow(scope.row)"
              :content="'价格偏离审批被驳回：' + (scope.row.approvalRemark || '未填写原因')"
              placement="top"
            >
              <el-tag type="danger" size="small" style="margin-left: 6px;">已驳回</el-tag>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <div class="action-group">
              <el-button size="small" type="primary" link @click="handleView(scope.row)">查看</el-button>
              <!-- D98：作废审批中冻结主流程（禁用+提示）；已作废/冲抵单据不再出现确认按钮 -->
              <el-tooltip v-if="scope.row.confirmStatus === 1 && scope.row.bizStatus === 1 && !isBizDocumentDeleted(scope.row)" :disabled="!voidPendingIds.has(scope.row.id)" content="作废审批中，待仓储管理员处理" placement="top">
                <span>
                  <el-button
                    v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                    size="small"
                    type="success"
                    link
                    :disabled="voidPendingIds.has(scope.row.id)"
                    @click="handleConfirm(scope.row)"
                  >
                    确认出库
                  </el-button>
                </span>
              </el-tooltip>
              <el-tooltip v-if="showDeleteAction(scope.row)" content="当天未生效错单可删除（不留痕）；已生效或历史错单请用作废（留痕+仓储审批）" placement="top">
                <el-button
                  v-permission="{ roles: ['admin', 'employee'], deptCodes: ['sales'] }"
                  size="small"
                  type="danger"
                  link
                  @click="handleDelete(scope.row)"
                >
                  删除
                </el-button>
              </el-tooltip>
              <template v-else-if="showVoidActions(scope.row)">
                <el-tooltip :content="voidPendingIds.has(scope.row.id) ? '作废审批中，待仓储管理员处理' : '已生效或历史错单作废留痕，仓储审批通过后生效'" placement="top">
                  <span>
                    <el-button
                      v-permission="{ roles: ['admin'], deptCodes: ['sales'] }"
                      size="small"
                      type="warning"
                      link
                      :disabled="!canVoid(scope.row) || voidPendingIds.has(scope.row.id)"
                      @click="handleVoid(scope.row)"
                    >
                      作废
                    </el-button>
                  </span>
                </el-tooltip>
              </template>
              <span v-else :class="['action-disabled', stateTextClass(scope.row)]">{{ resolveState(scope.row)?.label || '不可操作' }}</span>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div class="pagination-box" style="margin-top: 20px; display: flex; justify-content: flex-end;">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <el-dialog :title="dialogType === 'view' ? '销售单详情' : '新增销售单'" v-model="dialogVisible" width="760px">
      <!-- D110：详情=头信息 + 明细行表；新增=多明细行编辑（同一成品一单只允许一行） -->
      <el-form v-if="dialogType === 'view'" :model="viewForm" label-width="100px" disabled>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="销售单号"><el-input :value="viewForm.salesNo" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="客户公司名"><el-input :value="viewForm.customerName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="合同编号"><el-input :value="viewForm.contractNo" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="销售日期"><el-input :value="viewForm.salesDate" /></el-form-item></el-col>
          <el-col v-if="showPrice" :span="12"><el-form-item label="销售总额"><el-input :value="viewForm.totalAmount"><template #append>元</template></el-input></el-form-item></el-col>
          <el-col v-if="showPrice" :span="12"><el-form-item label="销售均价"><el-input :value="viewForm.avgPrice"><template #append>元</template></el-input></el-form-item></el-col>
          <el-col v-if="showPrice" :span="12">
            <el-form-item label="是否含税">
              <el-input :value="Number(viewForm.taxIncluded) === 1 ? '含税' : '不含税'" />
            </el-form-item>
          </el-col>
          <el-col :span="12"><el-form-item label="操作人"><el-input :value="viewForm.operator" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input :value="viewForm.remark" type="textarea" :rows="2" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <el-form v-if="dialogType === 'view'" label-width="100px">
        <el-form-item label="成品明细">
          <el-table :data="viewForm.details" size="small" border style="width: 100%">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="goodsName" label="成品" min-width="140" />
            <el-table-column prop="quantity" label="数量" width="80" align="center" />
            <el-table-column v-if="showPrice" prop="unitPrice" label="销售单价(元)" width="110" />
            <el-table-column v-if="showPrice" prop="totalPrice" label="金额(元)" width="110" />
            <el-table-column label="标记" width="140">
              <template #default="scope">
                <!-- D112：零库存/无 BOM 行标注（不拦下单，提示联动生产） -->
                <el-tag v-if="scope.row.zeroStock" type="warning" size="small">零库存</el-tag>
                <el-tag v-if="scope.row.hasBom === false" type="danger" size="small" style="margin-left: 4px">无 BOM</el-tag>
                <span v-if="!scope.row.zeroStock && scope.row.hasBom !== false" style="color:#c0c4cc">—</span>
              </template>
            </el-table-column>
            <el-table-column v-if="isWarehouseUser" label="库存/缺货" width="170">
              <template #default="scope">
                <el-tag v-if="scope.row.shortage" type="danger" size="small">缺货（需{{ scope.row.quantity }}/现存{{ scope.row.stock ?? 0 }}）</el-tag>
                <span v-else>库存 {{ scope.row.stock ?? '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
      </el-form>

      <el-form v-if="dialogType !== 'view'" ref="dialogFormRef" :model="dialogForm" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="客户公司名">
              <el-input v-model="dialogForm.customerName" placeholder="请输入客户公司名（可选）"></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="合同编号">
              <el-input v-model="dialogForm.contractNo" placeholder="请输入合同编号（可选）"></el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="成品明细" required>
          <div class="items-editor">
            <el-table :data="dialogForm.items" size="small" border style="width: 100%">
              <el-table-column type="index" label="#" width="50" align="center" />
              <el-table-column label="成品" min-width="180">
                <template #default="scope">
                  <div class="goods-select-row">
                    <el-select v-model="scope.row.goodsId" placeholder="选择成品" style="width: 100%" filterable @change="onGoodsSelected(scope.row)">
                      <el-option
                        v-for="g in availableGoods(scope.$index)"
                        :key="g.id"
                        :label="`${g.name}（库存 ${g.stock || 0}${g.unit ? ' ' + g.unit : ''}${g.hasBom === false ? ' ｜ 无BOM' : ''}）`"
                        :value="g.id"
                      />
                    </el-select>
                    <!-- D121：成品下拉搜不到时销售可四项快捷建档并自动选中（ADR-0017）；仅销售部门成员可见 -->
                    <el-button
                      v-if="canQuickCreate && dialogType !== 'view'"
                      link
                      type="primary"
                      size="small"
                      @click="openQuickProduct(scope.row)"
                    >
                      + 新品
                    </el-button>
                  </div>
                  <div v-if="lineStock(scope.row) === 0 || lineGoods(scope.row)?.hasBom === false" class="line-flags">
                    <!-- D112：零库存/无 BOM 行标注（不拦下单） -->
                    <el-tag v-if="lineStock(scope.row) === 0" type="warning" size="small">零库存</el-tag>
                    <el-tag v-if="lineGoods(scope.row)?.hasBom === false" type="danger" size="small" style="margin-left: 4px">无 BOM</el-tag>
                  </div>
                  <div v-if="lineStock(scope.row) !== null" class="stock-hint">
                    当前库存：{{ lineStock(scope.row) }} {{ lineUnit(scope.row) }}<span v-if="lineSalePrice(scope.row)"> ｜ 标准售价：¥{{ lineSalePrice(scope.row) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="130">
                <template #default="scope">
                  <el-input-number v-model="scope.row.quantity" :min="1" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column v-if="showPrice" label="销售单价" width="160">
                <template #default="scope">
                  <el-input-number v-model="scope.row.unitPrice" :min="0.01" :precision="2" :step="0.1" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column label="操作" width="70" align="center">
                <template #default="scope">
                  <el-button
                    size="small"
                    type="danger"
                    link
                    :disabled="dialogForm.items.length <= 1"
                    @click="dialogForm.items.splice(scope.$index, 1)"
                  >
                    删除
                  </el-button>
                </template>
              </el-table-column>
            </el-table>
            <div class="items-editor-footer">
              <el-button size="small" :icon="Plus" @click="addItemRow">添加成品行</el-button>
              <span class="items-editor-hint">同一成品一张销售单只能有一行，多件请合并数量</span>
            </div>
            <div v-for="(hint, idx) in lineHints" :key="idx" class="line-hints">
              <div v-if="hint.shortage" class="shortage-hint">⚠ 第{{ idx + 1 }}行库存不足（需 {{ hint.quantity }} / 现存 {{ hint.stock }}），建单后将通知生产排产</div>
              <div v-if="hint.deviated" class="price-deviation-hint">⚠ 第{{ idx + 1 }}行销售价偏离标准售价 {{ hint.deviationPct }}%，超 {{ priceDeviationThresholdPct }}% 阈值，整单提交后将需超管审批后仓储方可确认出库</div>
            </div>
          </div>
        </el-form-item>
        <el-form-item v-if="showPrice" label="是否含税">
          <el-radio-group v-model="dialogForm.taxIncluded">
            <el-radio :value="1">含税</el-radio>
            <el-radio :value="0">不含税</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="showPrice" label="销售总额">
          <el-input :value="totalAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.remark" placeholder="请输入备注说明"></el-input>
        </el-form-item>
        <!-- D106：销售日期 = 开单时间自动生成，删除原「出库日期」选择器（不可补录/不可改） -->
        <el-form-item label="销售日期">
          <div class="sales-date-hint">开单时自动生成（当前时间），不可选择</div>
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
          <el-button v-if="dialogType !== 'view'" type="primary" :icon="Check" @click="submitForm">确定新增</el-button>
        </span>
      </template>
      <!-- D71/D110：履约时间线按明细行逐行展示（类淘宝物流），仅详情态展示 -->
      <SalesTimeline v-if="dialogType === 'view' && dialogVisible" :sales-id="currentViewId" />
    </el-dialog>

    <VoidConfirmDialog
      v-model="voidDialogVisible"
      :stock-effect="voidStockEffect"
      :reason-required="true"
      :submitting="voidSubmitting"
      @confirm="submitVoid"
    />

    <!-- D121：销售端「+新品」快速建品小表单（ADR-0017）——四项入参，同名成品直接选用 -->
    <el-dialog
      v-model="quickProductVisible"
      title="快速新建成品"
      width="440px"
      append-to-body
    >
      <el-form label-width="90px">
        <el-form-item label="成品名称" required>
          <el-input v-model="quickProductForm.goodsName" placeholder="请输入成品名称" maxlength="50" />
        </el-form-item>
        <el-form-item label="规格">
          <el-input v-model="quickProductForm.spec" placeholder="选填" maxlength="50" />
        </el-form-item>
        <el-form-item label="单位" required>
          <el-input v-model="quickProductForm.unit" placeholder="如：台" maxlength="20" />
        </el-form-item>
        <el-form-item label="售价（元）" required>
          <el-input-number v-model="quickProductForm.salePrice" :min="0" :precision="2" :step="0.1" style="width: 100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button :icon="Close" @click="quickProductVisible = false">取消</el-button>
          <el-button type="primary" :icon="Check" :loading="quickProductSubmitting" @click="submitQuickProduct">创建并选中</el-button>
        </span>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled, Search, Refresh, Plus, Close, Check } from '@element-plus/icons-vue'
import { createApprovalOrderAPI, getPendingVoidBizIdsAPI } from '@/api/system'
import VoidConfirmDialog from '@/components/VoidConfirmDialog.vue'
import { getPriceDeviationThresholdAPI } from '@/api/config'
import { hasBizDocumentWorkflowState, isBizDocumentDeleted, resolveBizDocumentState } from '@/utils/bizDocumentState'
import { getRole } from '@/utils/auth'
import { getDeptCode, isSuperAdmin } from '@/utils/auth'
import SalesTimeline from '@/components/SalesTimeline.vue'
import {
  createSalesAPI,
  confirmSalesAPI,
  deleteSalesAPI,
  getGoodsOptionsAPI,
  getSalesDetailAPI,
  getSalesPageAPI
} from '@/api/business'
import { createQuickProductAPI } from '@/api/base'

const searchForm = reactive({ keywords: '', customerName: '', dateRange: [] })
const userRole = getRole()
const userDept = getDeptCode()

// D94：作废说明弹窗 + 行内「作废审批中」状态（仅 admin 拉取，与作废按钮可见性一致）
const voidDialogVisible = ref(false)
const voidTarget = ref(null)
const voidSubmitting = ref(false)
const voidPendingIds = ref(new Set())
const isSalesAdmin = userRole === 'admin' && userDept === 'sales'
const isWarehouseAdmin = userRole === 'admin' && userDept === 'warehouse'
const voidStockEffect = computed(() => {
  const row = voidTarget.value
  // 已确认出库的销售单，作废审批通过后须把发出去的货补回来（D110：按明细行回补，提示按汇总）
  if (!row || Number(row.confirmStatus) !== 2) return null
  return { goodsName: row.goodsSummary || '多成品', quantity: row.totalQuantity, mode: 'return' }
})
// D36：销售金额列仅销售部门可见（售价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'sales' || isSuperAdmin(userRole)
// D110：仓储视角展示行级缺货明细并整单标红
const isWarehouseUser = userDept === 'warehouse'
const shortageLines = (row) => (row?.details || []).filter((d) => d.shortage)
const isShortageRow = (row) => Number(row?.confirmStatus) === 1 && !isBizDocumentDeleted(row) && shortageLines(row).length > 0
const shortageRowClass = ({ row }) => (isWarehouseUser && isShortageRow(row) ? 'shortage-row' : '')
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const goodsOptions = ref([])

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const currentViewId = ref(null) // 详情态当前查看的销售单id（履约时间线数据源）
const dialogFormRef = ref(null)
// D110：新增=多明细行；unitPrice 由所选成品标准售价预填（可改）
const dialogForm = reactive({
  items: [emptyItem()],
  customerName: '',
  contractNo: '',
  taxIncluded: 0,
  remark: ''
})
const viewForm = reactive({ salesNo: '', customerName: '', contractNo: '', salesDate: '', totalAmount: '', avgPrice: '', taxIncluded: 0, operator: '', remark: '', details: [] })

function emptyItem() {
  return { goodsId: null, quantity: 1, unitPrice: 0 }
}

const lineGoods = (row) => goodsOptions.value.find((g) => g.id === row.goodsId) || null
const lineStock = (row) => (lineGoods(row) ? lineGoods(row).stock ?? 0 : null)
const lineUnit = (row) => lineGoods(row)?.unit || ''
const lineSalePrice = (row) => lineGoods(row)?.salePrice ?? null

/** 同一成品一单只允许一行：其他行已选中的成品从当前行下拉中排除 */
const availableGoods = (index) => {
  const chosen = new Set(dialogForm.items.filter((_, i) => i !== index).map((i) => i.goodsId))
  return goodsOptions.value.filter((g) => !chosen.has(g.id))
}

const addItemRow = () => dialogForm.items.push(emptyItem())

// D121：销售端快速建品（ADR-0017）——成品下拉搜不到时四项建档并自动选中当前行；仅销售部门成员可见
const canQuickCreate = userDept === 'sales'
const quickProductVisible = ref(false)
const quickProductSubmitting = ref(false)
const quickTargetRow = ref(null)
const quickProductForm = reactive({ goodsName: '', spec: '', unit: '台', salePrice: 0 })

const openQuickProduct = (row) => {
  quickTargetRow.value = row
  quickProductForm.goodsName = ''
  quickProductForm.spec = ''
  quickProductForm.unit = '台'
  quickProductForm.salePrice = 0
  quickProductVisible.value = true
}

const submitQuickProduct = async () => {
  if (!quickProductForm.goodsName.trim()) {
    ElMessage.warning('请输入成品名称')
    return
  }
  if (!quickProductForm.unit.trim()) {
    ElMessage.warning('请输入单位')
    return
  }
  if (quickProductForm.salePrice === null || quickProductForm.salePrice < 0) {
    ElMessage.warning('请输入不小于 0 的售价')
    return
  }
  quickProductSubmitting.value = true
  try {
    const res = await createQuickProductAPI({
      goodsName: quickProductForm.goodsName.trim(),
      spec: quickProductForm.spec.trim() || undefined,
      unit: quickProductForm.unit.trim(),
      salePrice: quickProductForm.salePrice
    })
    const created = res.data
    if (!goodsOptions.value.some((g) => g.id === created.id)) {
      goodsOptions.value.push(created)
    }
    if (quickTargetRow.value) {
      quickTargetRow.value.goodsId = created.id
      onGoodsSelected(quickTargetRow.value)
    }
    quickProductVisible.value = false
    if (created.existing) {
      ElMessage.warning('已存在同名成品，已为你选用')
    } else {
      ElMessage.success(`成品「${created.name}」已创建并选中`)
    }
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    quickProductSubmitting.value = false
  }
}

// review 修复：选中成品后按标准售价预填单价（初始 0 会被后端 DTO 拒收「单价必须大于0」），用户可改
const onGoodsSelected = (row) => {
  const sp = lineSalePrice(row)
  if (showPrice && sp) {
    row.unitPrice = Number(sp)
  }
}

// 价格偏离比例（D30：阈值由超管在系统参数页配置，默认 5%；D110 决策④：整单一笔审批，逐行提示）
const priceDeviationThreshold = ref(0.05) // 比例小数，如 0.05
const priceDeviationThresholdPct = computed(() => Math.round(priceDeviationThreshold.value * 100))
const lineHints = computed(() =>
  dialogForm.items.map((row) => {
    const sp = lineSalePrice(row)
    const up = Number(row.unitPrice || 0)
    const deviationPct = sp && sp > 0 && up > 0 ? Math.round(Math.abs(up - sp) / sp * 100) : 0
    const stock = lineStock(row)
    return {
      quantity: Number(row.quantity || 0),
      stock: stock ?? 0,
      shortage: stock !== null && Number(row.quantity || 0) > stock,
      deviationPct,
      deviated: deviationPct > priceDeviationThresholdPct.value
    }
  })
)

const totalAmountText = computed(() =>
  dialogForm.items.reduce((sum, row) => sum + Number(row.quantity || 0) * Number(row.unitPrice || 0), 0).toFixed(2)
)

// D69：定制公司销售单=需求单，零库存/超卖均可建单（缺货自动通知生产排产，出库时仓储硬校验兜底），
// 故不再钳制数量、不再拦截库存为 0，仅在输入区提示缺货。

const normalizeDateTime = (val) => {
  if (!val) return ''
  return String(val).replace('T', ' ')
}

const toDateOnly = (val) => {
  if (!val) return ''
  return String(val).slice(0, 10)
}

const localToday = () => {
  const now = new Date()
  const y = now.getFullYear()
  const m = String(now.getMonth() + 1).padStart(2, '0')
  const d = String(now.getDate()).padStart(2, '0')
  return `${y}-${m}-${d}`
}

const resolveBizDate = (row) => {
  return row?.salesDate || row?.operationTime || row?.createTime || ''
}

// D95：删除=当天未生效错单（无痕，员工另限本人单/后端兜底）；作废=已生效或历史错单（留痕+仓储审批）——admin 已出库单亦收口作废
const isPendingTodayDoc = (row) => Number(row?.confirmStatus) === 1 && toDateOnly(resolveBizDate(row)) === localToday()

const canDelete = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  return isPendingTodayDoc(row)
}

const canVoid = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  return !isPendingTodayDoc(row)
}

const resolveState = (row) => resolveBizDocumentState(row)

const stateTextClass = (row) => {
  const state = resolveState(row)
  if (!state) return ''
  if (state.type === 'success') return 'state-success'
  if (state.type === 'danger') return 'state-danger'
  if (state.type === 'warning') return 'state-warning'
  return ''
}

// 价格偏离审批被超管驳回（单子退回销售人员，列表展示驳回原因）
const isPriceDeviationRejectedRow = (row) =>
  Number(row?.approvalStatus) === 3 && String(row?.approvalRequestAction || '').toLowerCase() === 'price_deviation_confirm'

const showDeleteAction = (row) => !hasBizDocumentWorkflowState(row) && canDelete(row)

const showVoidActions = (row) => !hasBizDocumentWorkflowState(row) && canVoid(row)

const loadGoodsOptions = async () => {
  const res = await getGoodsOptionsAPI({ type: 'product' }) // D67：销售下单只选成品
  goodsOptions.value = res.data || []
}

const loadPriceDeviationThreshold = async () => {
  try {
    const res = await getPriceDeviationThresholdAPI()
    if (res.data != null) {
      const v = Number(res.data)
      if (Number.isFinite(v) && v > 0 && v < 1) {
        priceDeviationThreshold.value = v
      }
    }
  } catch (e) {
    // 读取失败时沿用默认 5%，不阻断建单
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const hasDateRange = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      goodsName: searchForm.keywords || undefined,
      customerName: searchForm.customerName || undefined,
      startDate: hasDateRange ? searchForm.dateRange[0] : undefined,
      endDate: hasDateRange ? searchForm.dateRange[1] : undefined
    }
    const res = await getSalesPageAPI(params)
    const pageData = res.data || {}
    tableData.value = (pageData.records || []).map((item) => ({
      ...item,
      salesDate: normalizeDateTime(item.salesDate || item.operationTime || item.createTime)
    }))
    total.value = pageData.total || 0
    loadVoidPendingIds()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const loadVoidPendingIds = async () => {
  // D98：仓储 admin 也拉取——「确认出库」主操作者，作废审批中需禁用+提示（端点 @RequireAdmin 仓储可过）
  if (!isSalesAdmin && !isWarehouseAdmin) {
    voidPendingIds.value = new Set()
    return
  }
  try {
    const res = await getPendingVoidBizIdsAPI('sales')
    voidPendingIds.value = new Set(res.data || [])
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadList()
}

const resetSearch = () => {
  searchForm.keywords = ''
  searchForm.customerName = ''
  searchForm.dateRange = []
  currentPage.value = 1
  loadList()
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  loadList()
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  loadList()
}

const handleAdd = () => {
  dialogType.value = 'add'
  dialogFormRef.value?.clearValidate()
  Object.assign(dialogForm, { items: [emptyItem()], customerName: '', contractNo: '', taxIncluded: 0, remark: '' })
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    const res = await getSalesDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    currentViewId.value = row.id
    Object.assign(viewForm, {
      salesNo: detail.salesNo || '',
      customerName: detail.customerName || '',
      contractNo: detail.contractNo || '',
      salesDate: normalizeDateTime(detail.salesDate || detail.operationTime || detail.createTime),
      totalAmount: detail.totalAmount ?? '—',
      avgPrice: detail.avgPrice ?? '—',
      taxIncluded: detail.taxIncluded ?? 0,
      operator: detail.operator || detail.operatorName || '',
      remark: detail.remark || '',
      details: detail.details || []
    })
    dialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('删除后该销售单库存将自动回补，确认继续吗？', '警告', { type: 'warning' }).then(async () => {
    await deleteSalesAPI(row.id)
    ElMessage.success('删除成功')
    row.__uiDeleted = true
    row.isDeleted = 1
  }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleConfirm = (row) => {
  // D110 决策②：整单一次确认出库，任一行缺货整单失败——确认前给出缺货行预告
  const shortage = shortageLines(row)
  const tip = shortage.length
    ? `以下成品现货不足，确认出库将失败，请先协调生产/采购：\n${shortage.map((d) => `· ${d.goodsName} 需 ${d.quantity}/现存 ${d.stock ?? 0}`).join('\n')}`
    : '确认出库将按明细行从库存扣减，任一行不足则整单失败，确认继续吗？'
  ElMessageBox.confirm(tip, '确认出库', { type: 'warning' }).then(async () => {
    await confirmSalesAPI(row.id)
    ElMessage.success('已确认出库')
    loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

// D94：先弹说明弹窗（后果/审批链路/留痕），确认后再提交
const handleVoid = (row) => {
  voidTarget.value = row
  voidDialogVisible.value = true
}

const submitVoid = async (reason) => {
  if (!voidTarget.value) return
  voidSubmitting.value = true
  try {
    await createApprovalOrderAPI({
      bizType: 'sales',
      bizId: voidTarget.value.id,
      requestAction: 'void',
      reason
    })
    ElMessage.success('已提交作废审批，待仓储管理员处理')
    voidDialogVisible.value = false
    loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    voidSubmitting.value = false
  }
}

const submitForm = () => {
  const items = dialogForm.items
  if (!items.length) {
    ElMessage.warning('请至少添加一行成品明细')
    return
  }
  if (items.some((row) => !row.goodsId)) {
    ElMessage.warning('请为每一行选择成品')
    return
  }
  const goodsIds = items.map((row) => row.goodsId)
  if (new Set(goodsIds).size !== goodsIds.length) {
    ElMessage.warning('同一成品在一张销售单中只能有一行，请合并数量')
    return
  }
  if (showPrice && items.some((row) => !Number(row.unitPrice) || Number(row.unitPrice) <= 0)) {
    ElMessage.warning('请为每一行填写大于 0 的销售单价')
    return
  }
  dialogFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    try {
      const payload = {
        // D110：一单多明细行；员工不传单价（后端按标准售价取值）
        items: items.map((row) => ({
          goodsId: row.goodsId,
          quantity: row.quantity,
          unitPrice: showPrice ? Number(row.unitPrice) : undefined
        })),
        // D106：销售日期由后端按开单时间自动生成，不再上传
        customerName: dialogForm.customerName || undefined,
        contractNo: dialogForm.contractNo || undefined,
        taxIncluded: dialogForm.taxIncluded ?? 0,
        remark: dialogForm.remark || ''
      }
      await createSalesAPI(payload)
      ElMessage.success('销售单已创建，销售日期为开单时间')
      dialogVisible.value = false
      loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })
}

onMounted(async () => {
  try {
    await loadGoodsOptions()
    await loadPriceDeviationThreshold()
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  }
})
</script>

<style scoped>
/* D121：成品下拉 + 快速建品按钮同行排布 */
.goods-select-row {
  display: flex;
  align-items: center;
  gap: 4px;
}

.stock-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.4;
}

/* D112：编辑器行内零库存/无 BOM 标注 */
.line-flags {
  margin-top: 4px;
  line-height: 1.4;
}

.price-deviation-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #f56c6c;
  line-height: 1.4;
}

.search-box {
  position: relative;
  margin-bottom: 20px;
}

.top-right-help {
  position: absolute;
  right: 0;
  top: -8px;
  color: #909399;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  z-index: 2;
}

.help-label {
  font-size: 12px;
  color: #909399;
}

.action-disabled {
  color: #999;
  font-size: 12px;
}

.state-success {
  color: #16a34a;
}

.state-danger {
  color: #dc2626;
}

.state-warning {
  color: #d97706;
}

.action-group {
  display: flex;
  align-items: center;
}

.void-help-icon {
  color: #909399;
  font-size: 15px;
  cursor: pointer;
}

.items-editor {
  width: 100%;
}

.items-editor-footer {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.items-editor-hint {
  font-size: 12px;
  color: #909399;
}

.line-hints {
  width: 100%;
}

.shortage-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.4;
}

/* D106：销售日期自动生成提示 */
.sales-date-hint {
  font-size: 12px;
  color: #909399;
  line-height: 32px;
}

.stock-shortage-text {
  color: #f56c6c;
  font-weight: 600;
  font-size: 12px;
}

:deep(.shortage-row) {
  background-color: #fef0f0;
}
</style>
