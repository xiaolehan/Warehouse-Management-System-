<template>
  <div class="sales-return-container">
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
          <el-form-item label="退回商品">
            <el-input v-model="searchForm.keywords" placeholder="请输入退回商品" clearable></el-input>
          </el-form-item>
          <el-form-item label="退货公司名">
            <el-input v-model="searchForm.customerName" placeholder="请输入退货公司名" clearable></el-input>
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
            <el-button v-permission="{ roles: ['admin', 'employee'], deptCodes: ['sales'] }" type="success" :icon="Plus" @click="handleAdd">新建销售退货单</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="tableData" border style="width: 100%" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="returnNo" label="销售退货单号" width="150" />
        <el-table-column prop="orderNo" label="原销售单" width="150" />
        <!-- D110：一单 N 个退货行，列表汇总展示「首品名 等 N 种」，明细进详情 -->
        <el-table-column prop="goodsSummary" label="退回商品" min-width="160" show-overflow-tooltip />
        <el-table-column prop="customerName" label="退货公司名" width="140" show-overflow-tooltip />
        <el-table-column prop="reason" label="退货原因" show-overflow-tooltip />
        <el-table-column prop="totalQuantity" label="退货数量" width="100" />
        <el-table-column v-if="showPrice" prop="totalAmount" label="退货金额(元)" width="120" />
        <el-table-column prop="returnDate" label="退货日期" width="180" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="确认状态" width="120">
          <template #default="scope">
            <!-- D97：已作废/已冲抵单据状态列直接展示终态，避免与确认状态歧义 -->
            <el-tag v-if="scope.row.bizStatus === 2" type="info" size="small">已作废</el-tag>
            <el-tooltip v-else-if="scope.row.bizStatus === 3" content="作废时系统生成的负数冲抵记录，用于抵消原单的库存与金额" placement="top">
              <el-tag type="info" size="small">已冲抵</el-tag>
            </el-tooltip>
            <el-tag v-else :type="scope.row.confirmStatus === 2 ? 'success' : 'warning'" size="small">
              {{ scope.row.confirmStatusText || (scope.row.confirmStatus === 2 ? '已确认入库' : '待仓库确认') }}
            </el-tag>
            <el-tag v-if="voidPendingIds.has(scope.row.id)" type="warning" size="small" style="margin-left: 4px">作废审批中</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
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
                    确认入库
                  </el-button>
                </span>
              </el-tooltip>
              <el-tooltip v-if="showDeleteAction(scope.row)" content="当天未生效错单可删除（不留痕）；已生效或历史错单请用作废（留痕+仓储审批）" placement="top">
                <el-button
                  v-permission="{ roles: ['admin'], deptCodes: ['sales'] }"
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

    <el-dialog :title="dialogType === 'view' ? '销售退货详情' : '新增销售退货单'" v-model="dialogVisible" width="720px">
      <!-- D110：查看=头信息 + 退货行明细 -->
      <template v-if="dialogType === 'view'">
        <el-form label-width="100px" disabled>
          <el-row :gutter="16">
            <el-col :span="12"><el-form-item label="退货单号"><el-input :value="viewForm.returnNo" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="原销售单"><el-input :value="viewForm.orderNo" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="退货公司名"><el-input :value="viewForm.customerName" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="退货日期"><el-input :value="viewForm.returnDate" /></el-form-item></el-col>
            <!-- D128：客户联系人与手机号（无值显示 —） -->
            <el-col :span="12"><el-form-item label="客户联系人"><el-input :value="viewForm.customerContactName || '—'" /></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="手机号"><el-input :value="viewForm.customerPhone || '—'" /></el-form-item></el-col>
            <el-col v-if="showPrice" :span="12"><el-form-item label="退货总额"><el-input :value="viewForm.totalAmount"><template #append>元</template></el-input></el-form-item></el-col>
            <el-col :span="12"><el-form-item label="操作人"><el-input :value="viewForm.operator" /></el-form-item></el-col>
            <el-col :span="24"><el-form-item label="退货原因"><el-input :value="viewForm.reason" type="textarea" :rows="2" /></el-form-item></el-col>
          </el-row>
        </el-form>
        <el-form label-width="100px">
          <el-form-item label="退回明细">
            <el-table :data="viewForm.details" size="small" border style="width: 100%">
              <el-table-column type="index" label="#" width="50" align="center" />
              <el-table-column prop="goodsName" label="成品" min-width="140" />
              <el-table-column prop="quantity" label="退货数量" width="100" align="center" />
              <el-table-column v-if="showPrice" prop="unitPrice" label="退货单价(元)" width="110" />
              <el-table-column v-if="showPrice" prop="totalPrice" label="金额(元)" width="110" />
            </el-table>
          </el-form-item>
        </el-form>
      </template>

      <el-form v-else ref="dialogFormRef" :model="dialogForm" :rules="dialogRules" label-width="100px">
        <el-form-item label="来源销售单" prop="sourceSalesId">
          <el-select
            v-model="dialogForm.sourceSalesId"
            placeholder="请选择来源销售单"
            style="width: 100%"
            filterable
            @change="handleSourceSalesChange"
          >
            <el-option
              v-for="item in sourceSalesOptions"
              :key="item.id"
              :label="`${item.salesNo} | ${item.customerName || '未填客户'} | ${normalizeDateTime(item.operationTime)}`"
              :value="item.id"
            />
          </el-select>
          <div class="source-hint">按原销售单选择，下方按明细行填写退货数量（行级可退 = 原行数量 − 已退累计）</div>
        </el-form-item>
        <el-form-item label="退货公司名" prop="customerName">
          <el-select
            v-model="dialogForm.customerName"
            placeholder="选择或输入退货公司名"
            style="width: 100%"
            clearable
            filterable
            allow-create
            default-first-option
            :reserve-keyword="false"
          >
            <el-option v-for="name in customerNameOptions" :key="name" :label="name" :value="name" />
          </el-select>
        </el-form-item>
        <!-- D128：联系人/手机号选填，选中来源单后自动带出、可编辑 -->
        <el-row :gutter="16">
          <el-col :span="12">
            <el-form-item label="客户联系人">
              <el-input v-model="dialogForm.customerContactName" placeholder="请输入客户联系人（可选）"></el-input>
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="手机号">
              <el-input v-model="dialogForm.customerPhone" placeholder="请输入手机号（可选）"></el-input>
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item v-if="selectedSourceSales" label="退回明细" required>
          <div class="return-lines">
            <el-table :data="selectedSourceSales.lines" size="small" border style="width: 100%">
              <el-table-column prop="goodsName" label="成品" min-width="140" />
              <el-table-column prop="quantity" label="原行数量" width="90" align="center" />
              <el-table-column prop="returnableQuantity" label="可退数量" width="90" align="center" />
              <el-table-column label="退回数量" width="140">
                <template #default="scope">
                  <el-input-number
                    v-model="returnQty[scope.row.salesDetailId]"
                    :min="0"
                    :max="scope.row.returnableQuantity"
                    :precision="0"
                    size="small"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
              <el-table-column v-if="showPrice" label="退货单价" width="150">
                <template #default="scope">
                  <el-input-number
                    v-model="returnPrice[scope.row.salesDetailId]"
                    :min="0.01"
                    :precision="2"
                    :step="0.1"
                    size="small"
                    style="width: 100%"
                  />
                </template>
              </el-table-column>
            </el-table>
            <div class="return-lines-hint">不需要退的行保持 0 即可；同一来源行只允许一行退货</div>
          </div>
        </el-form-item>
        <el-form-item v-if="showPrice && selectedSourceSales" label="退货总额">
          <el-input :value="refundAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="退货日期" prop="returnDate">
          <el-date-picker
            v-model="dialogForm.returnDate"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择退货时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="退货原因" prop="reason">
          <el-input v-model="dialogForm.reason" type="textarea" placeholder="填写退换货原因"></el-input>
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
  confirmSalesReturnAPI,
  createSalesReturnAPI,
  deleteSalesReturnAPI,
  getReturnableSalesOptionsAPI,
  getSalesReturnDetailAPI,
  getSalesReturnPageAPI,
  getSalesReturnTimelineAPI
} from '@/api/business'

const searchForm = reactive({ keywords: '', customerName: '', dateRange: [] })
const userRole = getRole()
const userDept = getDeptCode()
// D36：退货金额列仅销售部门可见（售价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'sales' || isSuperAdmin(userRole)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const sourceSalesOptions = ref([])

// D94：作废说明弹窗 + 行内「作废审批中」状态（仅 admin 拉取，与作废按钮可见性一致）
const voidDialogVisible = ref(false)
const voidTarget = ref(null)
const voidSubmitting = ref(false)
const voidPendingIds = ref(new Set())
const isSalesAdmin = userRole === 'admin' && userDept === 'sales'
const isWarehouseAdmin = userRole === 'admin' && userDept === 'warehouse'
const voidStockEffect = computed(() => {
  const row = voidTarget.value
  // 已确认入库的销退单，作废审批通过后须把退回来的货扣回去（D110：按明细行回冲，提示按汇总）
  if (!row || row.confirmStatus !== 2) return null
  return { goodsName: row.goodsSummary || '多成品', quantity: row.totalQuantity, mode: 'deduct' }
})

// 来源销售单的历史客户公司名（去重）作为下拉提示；允许手动输入任意文字
const customerNameOptions = computed(() => {
  const seen = new Set()
  const list = []
  for (const item of sourceSalesOptions.value) {
    const name = (item.customerName || '').trim()
    if (name && !seen.has(name)) {
      seen.add(name)
      list.push(name)
    }
  }
  return list
})

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const dialogFormRef = ref(null)
const selectedSourceSales = ref(null)
// D110：行级退货录入——来源明细行 id → 退回数量 / 退货单价（默认原行单价）
const returnQty = reactive({})
const returnPrice = reactive({})
const dialogForm = reactive({ sourceSalesId: null, returnDate: '', reason: '', customerName: '', customerContactName: '', customerPhone: '' })
const viewForm = reactive({ returnNo: '', orderNo: '', customerName: '', customerContactName: '', customerPhone: '', returnDate: '', totalAmount: '', operator: '', reason: '', details: [] })

const refundAmountText = computed(() => {
  if (!selectedSourceSales.value) return '0.00'
  return selectedSourceSales.value.lines
    .reduce((sum, line) => sum + Number(returnQty[line.salesDetailId] || 0) * Number(returnPrice[line.salesDetailId] || 0), 0)
    .toFixed(2)
})

const dialogRules = {
  sourceSalesId: [{ required: true, message: '请选择来源销售单', trigger: 'change' }],
  returnDate: [{ required: true, message: '请选择退货日期', trigger: 'change' }]
}

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
  return row?.returnDate || row?.operationTime || row?.createTime || ''
}

// D95：删除=当天未生效错单（无痕）；作废=已生效或历史错单（留痕+仓储审批）——与后端守卫同口径，杜绝「删不掉又无作废入口」死锁
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

const loadSourceSalesOptions = async () => {
  const res = await getReturnableSalesOptionsAPI()
  sourceSalesOptions.value = res.data || []
}

const handleSourceSalesChange = (sourceSalesId) => {
  selectedSourceSales.value = sourceSalesOptions.value.find((item) => item.id === sourceSalesId) || null
  // 清空上一单的行级录入；单价默认取原行单价
  Object.keys(returnQty).forEach((k) => delete returnQty[k])
  Object.keys(returnPrice).forEach((k) => delete returnPrice[k])
  if (dialogType.value === 'add' && selectedSourceSales.value) {
    for (const line of selectedSourceSales.value.lines || []) {
      returnPrice[line.salesDetailId] = Number(line.unitPrice || 0)
    }
    // 选中来源单后自动带出该公司名（仍可手动修改）
    dialogForm.customerName = selectedSourceSales.value.customerName || dialogForm.customerName || ''
    // D128：联系人/手机号同款带出（仍可手动修改；来源单无值时保留已填内容，镜像 customerName 范式）
    dialogForm.customerContactName = selectedSourceSales.value.customerContactName || dialogForm.customerContactName || ''
    dialogForm.customerPhone = selectedSourceSales.value.customerPhone || dialogForm.customerPhone || ''
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
    const res = await getSalesReturnPageAPI(params)
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
  // D98：仓储 admin 也拉取——「确认入库」主操作者，作废审批中需禁用+提示（端点 @RequireAdmin 仓储可过）
  if (!isSalesAdmin && !isWarehouseAdmin) {
    voidPendingIds.value = new Set()
    return
  }
  try {
    const res = await getPendingVoidBizIdsAPI('sales_return')
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
  selectedSourceSales.value = null
  Object.keys(returnQty).forEach((k) => delete returnQty[k])
  Object.keys(returnPrice).forEach((k) => delete returnPrice[k])
  Object.assign(dialogForm, { sourceSalesId: null, returnDate: '', reason: '', customerName: '' })
  dialogVisible.value = true
}

// D104：查看态加载单据流程时间线
const timelineNodes = ref([])
const loadTimeline = async (id) => {
  try {
    const res = await getSalesReturnTimelineAPI(id)
    timelineNodes.value = res.data?.nodes || []
  } catch {
    timelineNodes.value = []
    // 业务错误已由拦截器统一提示
  }
}

const handleView = async (row) => {
  try {
    const res = await getSalesReturnDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    loadTimeline(row.id)
    Object.assign(viewForm, {
      returnNo: detail.returnNo || '',
      orderNo: detail.orderNo || detail.sourceSalesNo || '',
      customerName: detail.customerName || '',
      customerContactName: detail.customerContactName || '',
      customerPhone: detail.customerPhone || '',
      returnDate: normalizeDateTime(detail.returnDate || detail.operationTime || detail.createTime),
      totalAmount: detail.totalAmount ?? '—',
      operator: detail.operator || detail.operatorName || '',
      reason: detail.reason || detail.remark || '',
      details: detail.details || []
    })
    dialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('删除此退货记录？', '确认', { type: 'warning' }).then(async () => {
    await deleteSalesReturnAPI(row.id)
    ElMessage.success('删除成功')
    row.__uiDeleted = true
    row.isDeleted = 1
  }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleConfirm = (row) => {
  ElMessageBox.confirm('确认入库将按明细行把退货数量加回库存，确认继续吗？', '确认入库', { type: 'warning' }).then(async () => {
    await confirmSalesReturnAPI(row.id)
    ElMessage.success('已确认入库')
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
      bizType: 'sales_return',
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
  dialogFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    // D110：按明细行组装退货项（数量>0 的行）；同一来源行只允许一行（后端同口径校验）
    const lines = selectedSourceSales.value?.lines || []
    const items = lines
      .filter((line) => Number(returnQty[line.salesDetailId] || 0) > 0)
      .map((line) => ({
        sourceSalesDetailId: line.salesDetailId,
        quantity: Number(returnQty[line.salesDetailId]),
        unitPrice: showPrice ? Number(returnPrice[line.salesDetailId]) : undefined
      }))
    if (!items.length) {
      ElMessage.warning('请至少为一行填写大于 0 的退回数量')
      return
    }
    const overLine = lines.find((line) => Number(returnQty[line.salesDetailId] || 0) > line.returnableQuantity)
    if (overLine) {
      ElMessage.warning(`「${overLine.goodsName}」退货数量超出可退数量，最多可退 ${overLine.returnableQuantity}`)
      return
    }
    try {
      const payload = {
        sourceSalesId: dialogForm.sourceSalesId,
        items,
        customerName: dialogForm.customerName || undefined,
        customerContactName: dialogForm.customerContactName || undefined,
        customerPhone: dialogForm.customerPhone || undefined,
        operationTime: buildOperationTime(dialogForm.returnDate),
        remark: dialogForm.reason || ''
      }
      await createSalesReturnAPI(payload)
      ElMessage.success('销售退货新增成功')
      dialogVisible.value = false
      await loadSourceSalesOptions()
      loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })
}

onMounted(async () => {
  // D96：可退销售单选项仅建单角色（销售部门成员/超管）需要；仓储进页面只做确认入库，不拉取避免无权限提示
  if (userDept === 'sales' || isSuperAdmin(userRole)) {
    try {
      await loadSourceSalesOptions()
    } catch {
      // 选项加载失败不阻断列表
    }
  }
  try {
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

.source-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 4px;
}

.return-lines {
  width: 100%;
}

.return-lines-hint {
  margin-top: 6px;
  font-size: 12px;
  color: #909399;
}
</style>
