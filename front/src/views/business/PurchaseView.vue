<template>
  <div class="purchase-container">
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
          <el-form-item label="物料名称">
            <el-input v-model="searchForm.keywords" placeholder="请输入物料名称" clearable></el-input>
          </el-form-item>
          <el-form-item label="供应商">
            <el-input v-model="searchForm.supplierName" placeholder="请输入供应商" clearable></el-input>
          </el-form-item>
          <el-form-item label="进货日期">
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
            <el-button v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }" type="success" :icon="Plus" @click="handleAdd">新增进货</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="tableData" border style="width: 100%" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="orderNo" label="进货单号" width="170" />
        <el-table-column prop="goodsSummary" label="物料名称" min-width="150" show-overflow-tooltip />
        <el-table-column prop="supplierSummary" label="供应商" min-width="120" show-overflow-tooltip />
        <el-table-column v-if="showPrice" label="进货单价(元)" width="110">
          <template #default="{ row }">{{ row.avgPrice ?? '—' }}</template>
        </el-table-column>
        <el-table-column prop="totalQuantity" label="进货数量" width="90" />
        <el-table-column v-if="showPrice" prop="totalAmount" label="总金额(元)" width="120" />
        <el-table-column prop="purchaseDate" label="进货日期" width="170" />
        <el-table-column label="入库状态" width="110">
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
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="scope">
            <div class="action-group">
              <el-button size="small" type="primary" link @click="handleView(scope.row)">查看</el-button>
              <!-- D98：作废审批中冻结主流程（禁用+提示）；已作废/冲抵单据不再出现确认按钮 -->
              <el-tooltip v-if="scope.row.confirmStatus === 1 && scope.row.bizStatus === 1" :disabled="!voidPendingIds.has(scope.row.id)" content="作废审批中，待仓储管理员处理" placement="top">
                <span>
                  <el-button
                    v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }"
                    size="small" type="success" link
                    :disabled="voidPendingIds.has(scope.row.id)"
                    @click="handleArrive(scope.row)">到货确认</el-button>
                </span>
              </el-tooltip>
              <el-tooltip v-if="scope.row.confirmStatus === 2 && scope.row.bizStatus === 1" :disabled="!voidPendingIds.has(scope.row.id)" content="作废审批中，待仓储管理员处理" placement="top">
                <span>
                  <el-button
                    v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                    size="small" type="success" link
                    :disabled="voidPendingIds.has(scope.row.id)"
                    @click="handleConfirmReceive(scope.row)">确认入库</el-button>
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

      <!-- 分页区域 -->
      <div class="pagination-box" style="margin-top: 20px; display: flex; justify-content: flex-end;">
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

    <el-dialog :title="dialogType === 'view' ? '查看进货信息' : '新增进货'" v-model="dialogVisible" width="760px">
      <!-- D111：查看态=头信息 + 物料明细行表 -->
      <el-form v-if="dialogType === 'view'" :model="viewForm" label-width="100px" disabled>
        <el-row :gutter="16">
          <el-col :span="12"><el-form-item label="进货单号"><el-input :value="viewForm.purchaseNo" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="进货日期"><el-input :value="viewForm.purchaseDate" /></el-form-item></el-col>
          <el-col v-if="showPrice" :span="12"><el-form-item label="进货总额"><el-input :value="viewForm.totalAmount"><template #append>元</template></el-input></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="操作人"><el-input :value="viewForm.operator" /></el-form-item></el-col>
          <el-col :span="24"><el-form-item label="备注"><el-input :value="viewForm.remark" type="textarea" :rows="2" /></el-form-item></el-col>
        </el-row>
      </el-form>
      <el-form v-if="dialogType === 'view'" label-width="100px">
        <el-form-item label="物料明细">
          <el-table :data="viewForm.details" size="small" border style="width: 100%">
            <el-table-column type="index" label="#" width="50" align="center" />
            <el-table-column prop="goodsName" label="物料" min-width="140" />
            <el-table-column prop="spec" label="规格" min-width="100" />
            <el-table-column prop="quantity" label="数量" width="80" align="center" />
            <el-table-column v-if="showPrice" prop="unitPrice" label="进货单价(元)" width="110" />
            <el-table-column v-if="showPrice" prop="totalPrice" label="金额(元)" width="110" />
          </el-table>
        </el-form-item>
      </el-form>

      <!-- D111：新增=多物料行编辑（同一物料一单只允许一行） -->
      <el-form v-if="dialogType !== 'view'" ref="dialogFormRef" :model="dialogForm" label-width="100px">
        <el-form-item label="物料明细" required>
          <div class="items-editor">
            <el-table :data="dialogForm.items" size="small" border style="width: 100%">
              <el-table-column type="index" label="#" width="50" align="center" />
              <el-table-column label="物料" min-width="200">
                <template #default="scope">
                  <el-select v-model="scope.row.goodsId" placeholder="选择物料" style="width: 100%" filterable @change="onGoodsSelected(scope.row)">
                    <el-option
                      v-for="g in availableGoods(scope.$index)"
                      :key="g.id"
                      :label="materialLabel(g)"
                      :value="g.id"
                    />
                  </el-select>
                  <div v-if="lineMaterial(scope.row)" class="stock-hint">
                    当前库存：{{ lineMaterial(scope.row).stock ?? 0 }} {{ lineMaterial(scope.row).unit }}
                    <span v-if="lineMaterial(scope.row).purchasePrice"> ｜ 最近进价：¥{{ lineMaterial(scope.row).purchasePrice }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="数量" width="130">
                <template #default="scope">
                  <el-input-number v-model="scope.row.quantity" :min="1" :precision="0" style="width: 100%" />
                </template>
              </el-table-column>
              <el-table-column v-if="showPrice" label="进货单价" width="160">
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
              <el-button size="small" :icon="Plus" @click="addItemRow">添加物料行</el-button>
              <span class="items-editor-hint">同一物料一张进货单只能有一行，多件请合并数量</span>
            </div>
          </div>
        </el-form-item>
        <el-form-item label="进货总数量">
          <el-input :value="totalQuantityText" disabled />
        </el-form-item>
        <el-form-item v-if="showPrice" label="进货总额">
          <el-input :value="totalAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="进货日期">
          <el-date-picker
            v-model="dialogForm.purchaseDate"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择进货时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="dialogForm.remark" type="textarea" placeholder="请输入备注说明"></el-input>
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
          <el-button v-if="dialogType !== 'view'" type="primary" :icon="Check" @click="submitForm">确定新增</el-button>
        </span>
      </template>
    </el-dialog>

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
  createPurchaseAPI,
  deletePurchaseAPI,
  getGoodsOptionsAPI,
  getPurchaseDetailAPI,
  getPurchasePageAPI,
  arrivePurchaseAPI,
  confirmReceivePurchaseAPI,
  getPurchaseTimelineAPI
} from '@/api/business'

const searchForm = reactive({
  keywords: '',
  supplierName: '',
  dateRange: []
})
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
  // 已确认入库（3）的进货单，作废审批通过后须把入了库的货扣回去
  if (!row || Number(row.confirmStatus) !== 3) return null
  return { goodsName: row.goodsSummary, quantity: row.totalQuantity, mode: 'deduct' }
})
// D36：进货金额列仅采购部门可见（进价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'purchase' || isSuperAdmin(userRole)

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const goodsOptions = ref([])

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const dialogFormRef = ref(null)

function emptyItem() {
  return { goodsId: null, quantity: 1, unitPrice: 0 }
}
// D111：新增=多物料行
const dialogForm = reactive({
  items: [emptyItem()],
  purchaseDate: '',
  remark: ''
})
const viewForm = reactive({ purchaseNo: '', purchaseDate: '', totalAmount: '', operator: '', remark: '', details: [] })

const lineMaterial = (row) => goodsOptions.value.find((g) => g.id === row.goodsId) || null
const materialLabel = (g) => `${g.name}${g.spec ? '（' + g.spec + '）' : ''}`

/** 同一物料一单只允许一行：其他行已选中的物料从当前行下拉中排除 */
const availableGoods = (index) => {
  const chosen = new Set(dialogForm.items.filter((_, i) => i !== index).map((i) => i.goodsId))
  return goodsOptions.value.filter((g) => !chosen.has(g.id))
}

const addItemRow = () => dialogForm.items.push(emptyItem())

// 选中物料后按最近进价预填单价（初始 0 会被后端拒收），用户可改
const onGoodsSelected = (row) => {
  const pp = lineMaterial(row)?.purchasePrice
  if (showPrice && pp) {
    row.unitPrice = Number(pp)
  }
}

const totalQuantityText = computed(() =>
  dialogForm.items.reduce((sum, row) => sum + Number(row.quantity || 0), 0))
const totalAmountText = computed(() =>
  dialogForm.items.reduce((sum, row) => sum + Number(row.quantity || 0) * Number(row.unitPrice || 0), 0).toFixed(2))

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
  return row?.purchaseDate || row?.operationTime || row?.createTime || ''
}

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

const loadGoodsOptions = async () => {
  const res = await getGoodsOptionsAPI({ type: 'material' }) // D67：物料进货只选物料
  goodsOptions.value = res.data || []
}

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
    const res = await getPurchasePageAPI(params)
    const pageData = res.data || {}
    tableData.value = (pageData.records || []).map((item) => ({
      ...item,
      purchaseDate: normalizeDateTime(item.purchaseDate || item.operationTime || item.createTime)
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
  // D98：仓储 admin 也拉取——「确认入库」主操作者，作废审批中需禁用+提示（端点 @RequireAdmin 仓储可过）
  if (!isPurchaseAdmin && !isWarehouseAdmin) {
    voidPendingIds.value = new Set()
    return
  }
  try {
    const res = await getPendingVoidBizIdsAPI('purchase')
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

const handleArrive = (row) => {
  ElMessageBox.confirm('确认到货？将通知仓储管理员确认入库。', '到货确认', { type: 'warning' })
    .then(async () => {
      await arrivePurchaseAPI(row.id)
      ElMessage.success('已确认到货，待仓储确认入库')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleConfirmReceive = (row) => {
  ElMessageBox.confirm('确认入库后将增加库存，不可撤销。是否继续？', '确认入库', { type: 'warning' })
    .then(async () => {
      await confirmReceivePurchaseAPI(row.id)
      ElMessage.success('已确认入库，库存已增加')
      loadList()
    }).catch(() => {}) // 取消或业务错误已由拦截器统一提示
}

const handleAdd = () => {
  dialogType.value = 'add'
  dialogFormRef.value?.clearValidate()
  Object.assign(dialogForm, { items: [emptyItem()], purchaseDate: '', remark: '' })
  dialogVisible.value = true
}

// D104：查看态加载单据流程时间线
const timelineNodes = ref([])
const loadTimeline = async (id) => {
  try {
    const res = await getPurchaseTimelineAPI(id)
    timelineNodes.value = res.data?.nodes || []
  } catch {
    timelineNodes.value = []
    // 业务错误已由拦截器统一提示
  }
}

const handleView = async (row) => {
  try {
    const res = await getPurchaseDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    loadTimeline(row.id)
    Object.assign(viewForm, {
      purchaseNo: detail.purchaseNo ?? '',
      purchaseDate: normalizeDateTime(detail.purchaseDate || detail.operationTime || detail.createTime),
      totalAmount: detail.totalAmount ?? '',
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
  ElMessageBox.confirm('确定要删除该进货单吗？仅当天待到货单据可删除，不影响库存。', '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    await deletePurchaseAPI(row.id)
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
      bizType: 'purchase',
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
    ElMessage.warning('请至少添加一行物料明细')
    return
  }
  if (items.some((row) => !row.goodsId)) {
    ElMessage.warning('请为每一行选择物料')
    return
  }
  const goodsIds = items.map((row) => row.goodsId)
  if (new Set(goodsIds).size !== goodsIds.length) {
    ElMessage.warning('同一物料在一张进货单中只能有一行，请合并数量')
    return
  }
  if (showPrice && items.some((row) => !Number(row.unitPrice) || Number(row.unitPrice) <= 0)) {
    ElMessage.warning('请为每一行填写大于 0 的进货单价')
    return
  }
  ;(async () => {
    try {
      const payload = {
        lines: items.map((row) => ({
          goodsId: row.goodsId,
          quantity: row.quantity,
          unitPrice: showPrice ? Number(row.unitPrice) : undefined
        })),
        operationTime: buildOperationTime(dialogForm.purchaseDate),
        remark: dialogForm.remark || ''
      }
      await createPurchaseAPI(payload)
      ElMessage.success('新增成功')
      dialogVisible.value = false
      loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })()
}

onMounted(async () => {
  try {
    await loadGoodsOptions()
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
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

.stock-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.4;
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
</style>
