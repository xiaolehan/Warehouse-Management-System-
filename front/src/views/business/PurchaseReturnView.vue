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
        <el-table-column prop="orderNo" label="原进货单" width="150" />
        <el-table-column prop="goodsName" label="退货物料" />
        <el-table-column prop="supplierName" label="退货至供应商" />
        <el-table-column prop="returnQuantity" label="退货数量" width="100" />
        <el-table-column v-if="showPrice" prop="returnAmount" label="退货金额(元)" width="120" />
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
        <el-table-column prop="reason" label="退货原因" show-overflow-tooltip />
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

    <el-dialog :title="dialogType === 'view' ? '查看退货信息' : '发起退货'" v-model="dialogVisible" width="500px">
      <el-form ref="dialogFormRef" :model="dialogForm" :rules="dialogRules" label-width="100px" :disabled="dialogType === 'view'">
        <el-form-item label="来源进货单" prop="sourcePurchaseId">
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
              :label="`${item.purchaseNo} | ${item.goodsName} | 可退:${item.returnableQuantity}`"
              :value="item.id"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="退货物料">
          <el-input :value="selectedSourcePurchase?.goodsName || '-'" disabled />
        </el-form-item>
        <el-form-item label="可退数量">
          <el-input :value="String(selectedSourcePurchase?.returnableQuantity ?? '-')" disabled />
        </el-form-item>
        <el-form-item label="退货数量" prop="returnQuantity">
          <el-input-number v-model="dialogForm.returnQuantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item v-if="showPrice" label="退货单价" prop="price">
          <el-input-number v-model="dialogForm.price" :min="0.01" :precision="2" :step="0.1" style="width: 100%" />
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
          <el-input v-model="dialogForm.reason" type="textarea" placeholder="请输入备注说明"></el-input>
        </el-form-item>
      </el-form>
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
import { QuestionFilled, Search, Refresh, Plus, Delete, DocumentRemove, DocumentDelete, Close, Check } from '@element-plus/icons-vue'
import { createApprovalOrderAPI, getPendingVoidBizIdsAPI } from '@/api/system'
import VoidConfirmDialog from '@/components/VoidConfirmDialog.vue'
import { hasBizDocumentWorkflowState, isBizDocumentDeleted, resolveBizDocumentState } from '@/utils/bizDocumentState'
import { getDeptCode, getRole, isSuperAdmin } from '@/utils/auth'
import {
  createPurchaseReturnAPI,
  deletePurchaseReturnAPI,
  getPurchaseReturnDetailAPI,
  getPurchaseReturnPageAPI,
  getReturnablePurchaseOptionsAPI,
  confirmOutPurchaseReturnAPI,
  completePurchaseReturnAPI
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
  // 已确认出库（>=2）的购退单，作废审批通过后须把退出去的货补回来
  if (!row || Number(row.confirmStatus) < 2) return null
  return { goodsName: row.goodsName, quantity: row.quantity, mode: 'return' }
})
// D36：退货金额列仅采购部门可见（进价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'purchase' || isSuperAdmin(userRole)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const sourcePurchaseOptions = ref([])
const selectedSourcePurchase = ref(null)

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const dialogFormRef = ref(null)
const dialogForm = reactive({ sourcePurchaseId: null, returnQuantity: 1, price: 0, returnDate: '', reason: '' })

const dialogRules = {
  sourcePurchaseId: [{ required: true, message: '请选择来源进货单', trigger: 'change' }],
  returnQuantity: [{ required: true, message: '请输入数量', trigger: 'blur' }],
  price: [{ required: true, message: '请输入退货单价', trigger: 'blur' }],
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
// 允许删除
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
// 构建后端所需的操作时间格式
const buildOperationTime = (selectedDate) => {
  if (!selectedDate) return undefined
  return String(selectedDate).replace(' ', 'T')
}

const loadSourcePurchaseOptions = async () => {
  const res = await getReturnablePurchaseOptionsAPI()
  sourcePurchaseOptions.value = res.data || []
}

const handleSourcePurchaseChange = (sourcePurchaseId) => {
  selectedSourcePurchase.value = sourcePurchaseOptions.value.find((item) => item.id === sourcePurchaseId) || null
  if (dialogType.value === 'add' && selectedSourcePurchase.value) {
    dialogForm.price = Number(selectedSourcePurchase.value.unitPrice || 0)
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
// 搜索
const handleSearch = () => {
  currentPage.value = 1
  loadList()
}
// 重置搜索
const resetSearch = () => {
  searchForm.keywords = ''
  searchForm.supplierName = ''
  searchForm.dateRange = []
  currentPage.value = 1
  loadList()
}
// 分页大小改变
const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  loadList()
}
// 当前页改变
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

// 新增退货单
const handleAdd = () => {
  dialogType.value = 'add'
  dialogFormRef.value?.clearValidate()
  selectedSourcePurchase.value = null
  // 重置表单数据
  Object.assign(dialogForm, { sourcePurchaseId: null, returnQuantity: 1, price: 0, returnDate: '', reason: '' })
  dialogVisible.value = true
}
// 查看退货单详情
const handleView = async (row) => {
  try {
    const res = await getPurchaseReturnDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    selectedSourcePurchase.value = {
      id: detail.sourcePurchaseId,
      purchaseNo: detail.sourcePurchaseNo,
      goodsName: detail.goodsName,
      returnableQuantity: detail.returnQuantity
    }
    Object.assign(dialogForm, {
      sourcePurchaseId: detail.sourcePurchaseId ?? null,
      returnQuantity: detail.returnQuantity ?? detail.quantity ?? 1,
      price: detail.unitPrice ?? (detail.returnAmount && detail.quantity ? Number(detail.returnAmount) / Number(detail.quantity) : 0),
      returnDate: normalizeDateTime(detail.returnDate || detail.operationTime || detail.createTime),
      reason: detail.reason || detail.remark || ''
    })
    dialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('撤销此退货单，相应库存将会扣回，继续吗？', '确认', { type: 'warning' }).then(async () => {
    await deletePurchaseReturnAPI(row.id)
    ElMessage.success('删除成功')
    row.__uiDeleted = true
    row.isDeleted = 1
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
  dialogFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    // 本地校验前置（非 API 错误，不入 try）：退货数量超可退
    if (selectedSourcePurchase.value && dialogForm.returnQuantity > selectedSourcePurchase.value.returnableQuantity) {
      ElMessage.warning(`退货数量超出可退数量，最多可退 ${selectedSourcePurchase.value.returnableQuantity}`)
      return
    }
    try {
      const payload = {
        sourcePurchaseId: dialogForm.sourcePurchaseId,
        quantity: dialogForm.returnQuantity,
        unitPrice: Number(dialogForm.price),
        operationTime: buildOperationTime(dialogForm.returnDate),
        remark: dialogForm.reason || ''
      }
      await createPurchaseReturnAPI(payload)
      ElMessage.success('退货开单成功')
      dialogVisible.value = false
      await loadSourcePurchaseOptions()
      loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })
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
</style>
