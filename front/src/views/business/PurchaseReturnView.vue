<template>
  <div class="purchase-return-container">
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
          <el-form-item label="退货物料">
            <el-input v-model="searchForm.keywords" placeholder="请输入退货物料" clearable></el-input>
          </el-form-item>
          <el-form-item label="退货至供应商">
            <el-input v-model="searchForm.supplierName" placeholder="请输入退货至供应商" clearable></el-input>
          </el-form-item>
          <el-form-item label="退货日期">
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
            <el-button v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }" type="success" :icon="Plus" @click="handleAdd">新建退货单</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="tableData" border style="width: 100%" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="returnNo" label="退货单号" width="150" />
        <el-table-column prop="sourcePurchaseNo" label="原进货单" width="150" />
        <el-table-column prop="goodsSummary" label="退货物料" min-width="150" show-overflow-tooltip />
        <el-table-column prop="totalQuantity" label="退货总数量" width="100" />
        <el-table-column v-if="showPrice" prop="totalAmount" label="退货金额(元)" width="120" />
        <el-table-column prop="returnDate" label="退货日期" width="180" />
        <el-table-column label="退货状态" width="110">
          <template #default="{ row }">
            <!-- D97：已作废/已冲抵单据状态列直接展示终态，避免与确认状态歧义 -->
            <el-tag v-if="row.bizStatus === 2" type="info" size="small">已作废</el-tag>
            <el-tooltip v-else-if="row.bizStatus === 3" content="作废时系统生成的负数冲抵记录，用于抵消原单的库存与金额" placement="top">
              <el-tag type="info" size="small">已冲抵</el-tag>
            </el-tooltip>
            <el-tag v-else :type="confirmStatusTagType(row.confirmStatus)">{{ row.confirmStatusText }}</el-tag>
            <el-tag v-if="voidPendingIds.has(row.id)" type="warning" size="small" style="margin-left: 4px">作废审批中</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column prop="remark" label="退货原因" show-overflow-tooltip />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <div class="action-group">
              <el-button size="small" type="primary" link @click="handleView(scope.row)">查看</el-button>
              <!-- D98：作废审批中冻结主流程（禁用+提示）；已作废/冲抵单据不再出现确认按钮 -->
              <el-tooltip v-if="scope.row.confirmStatus === 1 && scope.row.bizStatus === 1" :disabled="!voidPendingIds.has(scope.row.id)" content="作废审批中，待仓储管理员处理" placement="top">
                <span>
                  <el-button
                    v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                    size="small" type="success" link
                    :disabled="voidPendingIds.has(scope.row.id)"
                    @click="handleConfirmOut(scope.row)">确认出库</el-button>
                </span>
              </el-tooltip>
              <el-tooltip v-if="scope.row.confirmStatus === 2 && scope.row.bizStatus === 1" :disabled="!voidPendingIds.has(scope.row.id)" content="作废审批中，待仓储管理员处理" placement="top">
                <span>
                  <el-button
                    v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }"
                    size="small" type="success" link
                    :disabled="voidPendingIds.has(scope.row.id)"
                    @click="handleComplete(scope.row)">确认退货成功</el-button>
                </span>
              </el-tooltip>
              <el-tooltip v-if="showDeleteAction(scope.row)" content="当天未生效错单可删除（不留痕）；已生效或历史错单请用作废（留痕+仓储审批）" placement="top">
                <el-button
                  v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }"
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
                      v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }"
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

      <div class="pagination-box" style="margin: 20px 0 0; display: flex; justify-content: flex-end;">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="total"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <el-dialog :title="dialogType === 'view' ? '查看退货信息' : '发起退货'" v-model="dialogVisible" width="760px">
      <!-- D119/D111：查看态——来源进货单号纯文本展示 + 本退货单明细行（各端看到的数据一致，不再各查各的来源单） -->
      <el-form v-if="dialogType === 'view'" label-width="100px">
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="退货单号"><el-input :value="viewForm.returnNo" disabled /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="来源进货单"><el-input :value="viewForm.sourcePurchaseNo || '-'" disabled /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="退货日期"><el-input :value="viewForm.returnDate" disabled /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="操作人"><el-input :value="viewForm.operator" disabled /></el-form-item></el-col>
        </el-row>
        <el-form-item label="退货明细">
          <el-table :data="viewForm.details" size="small" border style="width: 100%">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="goodsName" label="物料" min-width="140" />
            <el-table-column prop="spec" label="规格" min-width="100" />
            <el-table-column prop="quantity" label="退货数量" width="90" align="center" />
            <el-table-column v-if="showPrice" prop="unitPrice" label="退货单价(元)" width="110" />
            <el-table-column v-if="showPrice" prop="totalPrice" label="金额(元)" width="110" />
          </el-table>
        </el-form-item>
        <el-form-item label="备注"><el-input :value="viewForm.remark" type="textarea" :rows="2" disabled /></el-form-item>
      </el-form>

      <!-- D111：发起态——选一张来源进货单，勾选其明细行，逐行填退货数量（行级可退量封顶） -->
      <el-form v-if="dialogType !== 'view'" label-width="100px">
        <el-form-item label="来源进货单" required>
          <el-select
            v-model="dialogForm.sourcePurchaseId"
            placeholder="请选择来源进货单"
            style="width: 100%"
            filterable
            @change="handleSourcePurchaseChange"
          >
            <el-option
              v-for="item in sourcePurchaseOptions"
              :key="item.id"
              :label="`${item.purchaseNo} | ${normalizeDateTime(item.operationTime)}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="退货明细" required>
          <el-table :data="sourceLineRows" size="small" border style="width: 100%" v-if="dialogForm.sourcePurchaseId">
            <el-table-column width="50" align="center">
              <template #default="scope">
                <el-checkbox v-model="scope.row.selected" />
              </template>
            </el-table-column>
            <el-table-column label="物料" min-width="130">
              <template #default="scope">
                {{ scope.row.goodsName }}
                <span class="line-spec">{{ scope.row.spec ? '（' + scope.row.spec + '）' : '' }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="quantity" label="原进货" width="80" align="center" />
            <el-table-column prop="returnedQuantity" label="已退" width="70" align="center" />
            <el-table-column prop="returnableQuantity" label="可退" width="70" align="center" />
            <el-table-column label="本次退货" width="150">
              <template #default="scope">
                <el-input-number
                  v-model="scope.row.returnQuantity"
                  :min="1"
                  :max="scope.row.returnableQuantity"
                  :precision="0"
                  :disabled="!scope.row.selected"
                  style="width: 100%"
                  @update:model-value="clampReturnQty(scope.row)"
                />
              </template>
            </el-table-column>
          </el-table>
          <el-empty v-else description="请先选择来源进货单" :image-size="60" />
        </el-form-item>
        <el-form-item label="退货总数量">
          <el-input :value="totalQuantityText" disabled />
        </el-form-item>
        <el-form-item v-if="showPrice" label="退货总额">
          <el-input :value="totalAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="退货日期">
          <el-date-picker
            v-model="dialogForm.returnDate"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择退货时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="退货原因">
          <el-input v-model="dialogForm.remark" type="textarea" placeholder="请输入退货原因"></el-input>
        </el-form-item>
      </el-form>
      <!-- D104：查看态展示单据流程时间线（谁在哪一步做了什么） -->
      <DocumentTimeline
        v-if="dialogType === 'view' && dialogVisible"
        :nodes="timelineNodes"
        empty-text="暂无流程记录"
      />
      <template #footer>
        <span class="dialog-footer">
          <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
          <el-button v-if="dialogType !== 'view'" type="primary" :icon="Check" @click="submitForm">确定发起</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- D94：作废说明弹窗 -->
    <VoidConfirmDialog
      v-model="voidDialogVisible"
      :stock-effect="voidStockEffect"
      :reason-required="true"
      :submitting="voidSubmitting"
      @confirm="submitVoid"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled, Search, Refresh, Plus, Close, Check } from '@element-plus/icons-vue'
import { createApprovalOrderAPI, getPendingVoidBizIdsAPI } from '@/api/system'
import VoidConfirmDialog from '@/components/VoidConfirmDialog.vue'
import DocumentTimeline from '@/components/DocumentTimeline.vue'
import { hasBizDocumentWorkflowState, isBizDocumentDeleted, resolveBizDocumentState } from '@/utils/bizDocumentState'
import { getDeptCode, getRole, isSuperAdmin } from '@/utils/auth'
import {
  createPurchaseReturnAPI,
  deletePurchaseReturnAPI,
  getPurchaseReturnDetailAPI,
  getPurchaseReturnPageAPI,
  getReturnablePurchaseOptionsAPI,
  confirmOutPurchaseReturnAPI,
  completePurchaseReturnAPI,
  getPurchaseReturnTimelineAPI
} from '@/api/business'

const searchForm = reactive({ keywords: '', supplierName: '', dateRange: [] })
const userRole = getRole()
const userDept = getDeptCode()

// D94：作废说明弹窗 + 行内「作废审批中」状态（仅 admin 拉取，与作废按钮可见性一致）
const voidDialogVisible = ref(false)
const voidTarget = ref(null)
const voidSubmitting = ref(false)
const voidPendingIds = ref(new Set())
const isPurchaseAdmin = userRole === 'admin' && userDept === 'purchase'
const isWarehouseAdmin = userRole === 'admin' && userDept === 'warehouse'
const voidStockEffect = computed(() => {
  const row = voidTarget.value
  // 已确认出库（>=2）的退货单，作废后须把退出去的货补回来（多行汇总展示）
  if (!row || Number(row.confirmStatus) < 2) return null
  return { goodsName: row.goodsSummary, quantity: row.totalQuantity, mode: 'return' }
})
// D36：退货金额列仅采购部门可见（进价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'purchase' || isSuperAdmin(userRole)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const sourcePurchaseOptions = ref([])
// 当前选中来源单的可退行（勾选+逐行退货数量）
const sourceLineRows = ref([])

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const dialogForm = reactive({ sourcePurchaseId: null, returnDate: '', remark: '' })

const viewForm = reactive({ returnNo: '', sourcePurchaseNo: '', returnDate: '', operator: '', remark: '', details: [] })

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

const resolveBizDate = (row) => row?.returnDate || row?.operationTime || row?.createTime || ''

// D95：删除=当天未生效错单（无痕）；作废=已生效或历史错单（留痕+仓储审批）——与后端守卫同口径
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

const showDeleteAction = (row) => !hasBizDocumentWorkflowState(row) && canDelete(row)

const showVoidActions = (row) => !hasBizDocumentWorkflowState(row) && canVoid(row)

const buildOperationTime = (selectedDate) => {
  if (!selectedDate) return undefined
  return String(selectedDate).replace(' ', 'T')
}

const loadSourcePurchaseOptions = async () => {
  const res = await getReturnablePurchaseOptionsAPI()
  sourcePurchaseOptions.value = res.data || []
}

const handleSourcePurchaseChange = (sourcePurchaseId) => {
  const source = sourcePurchaseOptions.value.find((item) => item.id === sourcePurchaseId)
  // 每次换来源单：按其明细行重建勾选行（行级可退量已由后端算好）
  sourceLineRows.value = (source?.lines || []).map((line) => ({
    purchaseDetailId: line.purchaseDetailId,
    goodsId: line.goodsId,
    goodsName: line.goodsName,
    spec: line.spec,
    quantity: line.quantity,
    unitPrice: line.unitPrice,
    returnedQuantity: line.returnedQuantity,
    returnableQuantity: line.returnableQuantity,
    selected: false,
    returnQuantity: 1
  }))
}

// el-input-number 的 max 之外再兜底（输入框手输），防止超可退
const clampReturnQty = (row) => {
  if (row.returnQuantity > row.returnableQuantity) {
    row.returnQuantity = row.returnableQuantity
  }
  if (row.returnQuantity < 1) {
    row.returnQuantity = 1
  }
}

const selectedRows = computed(() => sourceLineRows.value.filter((row) => row.selected))
const totalQuantityText = computed(() =>
  selectedRows.value.reduce((sum, row) => sum + Number(row.returnQuantity || 0), 0))
const totalAmountText = computed(() =>
  selectedRows.value.reduce(
    (sum, row) => sum + Number(row.returnQuantity || 0) * Number(row.unitPrice || 0), 0).toFixed(2))

const loadList = async () => {
  loading.value = true
  try {
    const hasDateRange = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      goodsName: searchForm.keywords || undefined,
      supplierName: searchForm.supplierName || undefined,
      startDate: hasDateRange ? searchForm.dateRange[0] : undefined,
      endDate: hasDateRange ? searchForm.dateRange[1] : undefined
    }
    const res = await getPurchaseReturnPageAPI(params)
    const pageData = res.data || {}
    tableData.value = (pageData.records || []).map((item) => ({
      ...item,
      returnDate: normalizeDateTime(item.returnDate || item.operationTime || item.createTime)
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
  if (!isPurchaseAdmin && !isWarehouseAdmin) {
    voidPendingIds.value = new Set()
    return
  }
  try {
    const res = await getPendingVoidBizIdsAPI('purchase_return')
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
  searchForm.supplierName = ''
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

const confirmStatusTagType = (status) => ({
  1: 'warning', 2: 'warning', 3: 'success'
}[status] || 'info')

const handleConfirmOut = (row) => {
  ElMessageBox.confirm('确认出库后将减少库存，不可撤销。是否继续？', '确认出库', { type: 'warning' })
    .then(async () => {
      await confirmOutPurchaseReturnAPI(row.id)
      ElMessage.success('已确认出库，库存已减少')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleComplete = (row) => {
  ElMessageBox.confirm('确认退货成功？将完成该退货单。', '确认退货成功', { type: 'warning' })
    .then(async () => {
      await completePurchaseReturnAPI(row.id)
      ElMessage.success('已确认退货成功')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleAdd = () => {
  dialogType.value = 'add'
  sourceLineRows.value = []
  Object.assign(dialogForm, { sourcePurchaseId: null, returnDate: '', remark: '' })
  dialogVisible.value = true
}

// D104：查看态加载单据流程时间线
const timelineNodes = ref([])
const loadTimeline = async (id) => {
  try {
    const res = await getPurchaseReturnTimelineAPI(id)
    timelineNodes.value = res.data?.nodes || []
  } catch {
    timelineNodes.value = []
    // 业务错误已由拦截器统一提示
  }
}

const handleView = async (row) => {
  try {
    const res = await getPurchaseReturnDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    loadTimeline(row.id)
    Object.assign(viewForm, {
      returnNo: detail.returnNo ?? '',
      sourcePurchaseNo: detail.sourcePurchaseNo ?? '',
      returnDate: normalizeDateTime(detail.returnDate || detail.operationTime || detail.createTime),
      operator: detail.operator ?? '',
      remark: detail.remark || '',
      details: detail.details || []
    })
    dialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定删除该退货单吗？仅当天待出库单据可删除，不影响库存。', '确认', { type: 'warning' })
    .then(async () => {
      await deletePurchaseReturnAPI(row.id)
      ElMessage.success('删除成功')
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
      bizType: 'purchase_return',
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
  if (!dialogForm.sourcePurchaseId) {
    ElMessage.warning('请选择来源进货单')
    return
  }
  const lines = selectedRows.value
  if (!lines.length) {
    ElMessage.warning('请至少勾选一行退货明细')
    return
  }
  if (lines.some((row) => !Number(row.returnQuantity) || Number(row.returnQuantity) < 1)) {
    ElMessage.warning('请为每个勾选行填写退货数量')
    return
  }
  ;(async () => {
    try {
      const payload = {
        lines: lines.map((row) => ({
          sourceDetailId: row.purchaseDetailId,
          quantity: Number(row.returnQuantity)
        })),
        operationTime: buildOperationTime(dialogForm.returnDate),
        remark: dialogForm.remark || ''
      }
      await createPurchaseReturnAPI(payload)
      ElMessage.success('退货开单成功')
      dialogVisible.value = false
      await loadSourcePurchaseOptions()
      loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })()
}

onMounted(async () => {
  // D96：可退进货单选项仅建单角色（采购部门成员/超管）需要；仓储进页面只做确认出库，不拉取避免无权限提示
  if (userDept === 'purchase' || isSuperAdmin(userRole)) {
    try {
      await loadSourcePurchaseOptions()
    } catch {
      // 选项加载失败不阻断列表
    }
  }
  try {
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示（loadList 内部已兜底，此 catch 实际不可达）
  }
})
</script>

<style scoped>
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

.line-spec {
  color: #909399;
  font-size: 12px;
}
</style>
