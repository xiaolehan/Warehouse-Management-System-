<template>
  <div class="production-container">
    <el-card>
      <div class="search-box">
        <div class="top-right-help">
          <span class="help-label">作废:</span>
          <el-tooltip content="当天单据可直接删除；历史单据作废将直接冲减库存并标记作废。" placement="left">
            <el-icon class="void-help-icon"><QuestionFilled /></el-icon>
          </el-tooltip>
        </div>
        <el-form :inline="true" :model="searchForm">
          <el-form-item label="成品名称">
            <el-input v-model="searchForm.keywords" placeholder="请输入成品名称" clearable></el-input>
          </el-form-item>
          <!-- D107：确认状态筛选（待确认=生产端提交的入库申请） -->
          <el-form-item label="确认状态">
            <el-select v-model="searchForm.confirmStatus" placeholder="全部" clearable style="width: 140px">
              <el-option label="待确认" :value="1" />
              <el-option label="已确认入库" :value="2" />
              <el-option label="已驳回" :value="3" />
            </el-select>
          </el-form-item>
          <el-form-item label="入库日期">
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
            <el-button v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" type="success" :icon="Plus" @click="handleAdd">新增生产入库</el-button>
          </el-form-item>
        </el-form>
      </div>

      <el-table :data="tableData" border style="width: 100%" v-loading="loading">
        <el-table-column type="index" label="序号" width="60" align="center" />
        <el-table-column prop="orderNo" label="入库单号" width="150" />
        <el-table-column prop="goodsName" label="成品名称" />
        <!-- D107：生产端提交的入库申请展示来源任务单 -->
        <el-table-column label="来源任务单" width="140">
          <template #default="scope">
            <span v-if="scope.row.productionOrderNo">{{ scope.row.productionOrderNo }}</span>
            <span v-else style="color:#909399">手动录入</span>
          </template>
        </el-table-column>
        <el-table-column label="生产单价(元)" width="120">
          <template #default="scope">{{ scope.row.unitPrice != null ? scope.row.unitPrice : '—' }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="入库数量" width="100" />
        <el-table-column label="总金额(元)" width="120">
          <template #default="scope">{{ scope.row.totalAmount != null ? scope.row.totalAmount : '—' }}</template>
        </el-table-column>
        <el-table-column prop="productionDate" label="入库日期" width="180" />
        <el-table-column prop="operator" label="操作人" width="100" />
        <el-table-column label="确认状态" width="110" align="center">
          <template #default="scope">
            <el-tooltip v-if="scope.row.confirmStatus === 3 && scope.row.rejectReason" :content="'驳回原因：' + scope.row.rejectReason" placement="top">
              <el-tag type="danger" size="small">已驳回</el-tag>
            </el-tooltip>
            <el-tag v-else-if="scope.row.confirmStatus === 1" type="warning" size="small">待确认</el-tag>
            <el-tag v-else type="success" size="small">已确认入库</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="scope">
            <div class="action-group">
              <el-button size="small" type="primary" link @click="handleView(scope.row)">查看</el-button>
              <!-- D107：待确认申请 → 仓储管理员「确认入库 / 驳回」 -->
              <template v-if="isPendingConfirm(scope.row)">
                <el-button
                  v-if="isWarehouseAdmin" size="small" type="success" link
                  @click="handleConfirmInbound(scope.row)"
                >确认入库</el-button>
                <el-button
                  v-if="isWarehouseAdmin" size="small" type="danger" link
                  @click="handleRejectInbound(scope.row)"
                >驳回</el-button>
                <span v-if="!isWarehouseAdmin" class="action-disabled">待仓储确认</span>
              </template>
              <el-tooltip v-else-if="showDeleteAction(scope.row)" content="当天错单可直接删除（不留痕）；历史错单请用作废（留痕）" placement="top">
                <el-button
                  v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                  size="small"
                  type="danger"
                  link
                  @click="handleDelete(scope.row)"
                >
                  删除
                </el-button>
              </el-tooltip>
              <template v-else-if="showVoidActions(scope.row)">
                <el-tooltip content="历史错单作废留痕，立即生效并冲减库存" placement="top">
                  <span>
                    <el-button
                      v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                      size="small"
                      type="warning"
                      link
                      :disabled="!canVoid(scope.row)"
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

    <el-dialog :title="dialogType === 'view' ? '查看生产入库信息' : '新增生产入库'" v-model="dialogVisible" width="500px">
      <el-form ref="dialogFormRef" :model="dialogForm" :rules="dialogRules" label-width="100px" :disabled="dialogType === 'view'">
        <el-form-item label="成品名称" prop="goodsId">
          <el-select v-model="dialogForm.goodsId" placeholder="请选择商品" style="width: 100%">
            <el-option v-for="item in goodsOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="入库数量" prop="quantity">
          <el-input-number v-model="dialogForm.quantity" :min="1" style="width: 100%" />
        </el-form-item>
        <el-form-item label="生产单价" prop="price">
          <el-input-number v-model="dialogForm.price" :min="0" :precision="2" :step="0.1" placeholder="可选，不填则不记录成本" style="width: 100%" />
        </el-form-item>
        <el-form-item label="总金额" prop="totalAmount">
          <el-input :value="totalAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="入库日期" prop="productionDate">
          <el-date-picker
            v-model="dialogForm.productionDate"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择入库时间"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="dialogForm.remark" type="textarea" placeholder="请输入备注"></el-input>
        </el-form-item>
        <!-- D107：确认信息（仅查看态展示） -->
        <template v-if="dialogType === 'view'">
          <el-form-item label="来源任务单">
            <el-input :value="dialogForm.productionOrderNo || '手动录入'" disabled />
          </el-form-item>
          <el-form-item label="确认状态">
            <el-tag :type="confirmTagType(dialogForm.confirmStatus)" size="small">{{ confirmStatusText(dialogForm.confirmStatus) }}</el-tag>
            <span v-if="dialogForm.confirmerName" style="margin-left: 8px; color: #909399">
              {{ dialogForm.confirmerName }} {{ normalizeDateTime(dialogForm.confirmTime) }}
            </span>
          </el-form-item>
          <el-form-item v-if="dialogForm.confirmStatus === 3" label="驳回原因">
            <el-input :value="dialogForm.rejectReason || '—'" disabled />
          </el-form-item>
        </template>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
          <el-button v-if="dialogType !== 'view'" type="primary" :icon="Check" @click="submitForm">确定</el-button>
        </span>
      </template>
    </el-dialog>

    <!-- D94：作废说明弹窗（生产入库为直废型，立即生效、原因可选） -->
    <VoidConfirmDialog
      v-model="voidDialogVisible"
      :stock-effect="voidStockEffect"
      :direct="true"
      :reason-required="false"
      :submitting="voidSubmitting"
      @confirm="submitVoid"
    />
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled, Search, Refresh, Plus, Close, Check } from '@element-plus/icons-vue'
import VoidConfirmDialog from '@/components/VoidConfirmDialog.vue'
import { hasBizDocumentWorkflowState, isBizDocumentDeleted, resolveBizDocumentState } from '@/utils/bizDocumentState'
import { getDeptCode, getRole, isSuperAdmin } from '@/utils/auth'
import {
  confirmProductionInboundAPI,
  createProductionAPI,
  deleteProductionAPI,
  getGoodsOptionsAPI,
  getProductionDetailAPI,
  getProductionPageAPI,
  rejectProductionInboundAPI,
  voidProductionAPI
} from '@/api/business'

// D107：确认/驳回入库申请 = 仓储管理员专属（与后端 requireInboundConfirmAccess 对齐）
const userRole = getRole()
const userDept = getDeptCode()
const isWarehouseAdmin = userRole === 'admin' && userDept === 'warehouse'

const searchForm = reactive({
  keywords: '',
  confirmStatus: null,
  dateRange: []
})

const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)
const loading = ref(false)
const goodsOptions = ref([])

const tableData = ref([])

const dialogVisible = ref(false)
const dialogType = ref('add')
const dialogFormRef = ref(null)
const dialogForm = reactive({
  goodsId: null,
  quantity: 1,
  price: null,
  productionDate: '',
  remark: '',
  // D107：确认信息（查看态展示）
  productionOrderNo: '',
  confirmStatus: null,
  confirmerName: '',
  confirmTime: '',
  rejectReason: ''
})

// D107：确认状态文案/标签色（null 视为迁移前存量行=已确认入库）
const confirmStatusText = (s) => (s === 1 ? '待确认' : s === 3 ? '已驳回' : '已确认入库')
const confirmTagType = (s) => (s === 1 ? 'warning' : s === 3 ? 'danger' : 'success')

const totalAmountText = computed(() => {
  const qty = Number(dialogForm.quantity || 0)
  const price = Number(dialogForm.price || 0)
  if (!price) return '—'
  return (qty * price).toFixed(2)
})

// D94：作废说明弹窗（生产入库直废型，无审批流，故无「作废审批中」行内状态）
const voidDialogVisible = ref(false)
const voidTarget = ref(null)
const voidSubmitting = ref(false)
const voidStockEffect = computed(() => {
  const row = voidTarget.value
  // 生产入库单创建即入库，作废须把入进来的货扣回去
  if (!row) return null
  return { goodsName: row.goodsName, quantity: row.quantity, mode: 'deduct' }
})

const dialogRules = {
  goodsId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入入库数量', trigger: 'blur' }],
  productionDate: [{ required: true, message: '请选择入库日期', trigger: 'change' }]
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
  return row?.productionDate || row?.operationTime || row?.createTime || ''
}

const canDelete = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  return toDateOnly(resolveBizDate(row)) === localToday()
}

const canVoid = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  return toDateOnly(resolveBizDate(row)) !== localToday()
}

const resolveState = (row) => resolveBizDocumentState(row)

// D107 确认状态：1-待仓库确认（生产端申请），2-已确认入库（加过库存），3-已驳回（未入库存）
const isPendingConfirm = (row) => Number(row?.confirmStatus || 0) === 1
const isStockBearing = (row) => row?.confirmStatus == null || Number(row.confirmStatus) === 2

const stateTextClass = (row) => {
  const state = resolveState(row)
  if (!state) return ''
  if (state.type === 'success') return 'state-success'
  if (state.type === 'danger') return 'state-danger'
  if (state.type === 'warning') return 'state-warning'
  return ''
}

const showDeleteAction = (row) => !hasBizDocumentWorkflowState(row) && isStockBearing(row) && canDelete(row)

const showVoidActions = (row) => !hasBizDocumentWorkflowState(row) && isStockBearing(row) && canVoid(row)

const buildOperationTime = (selectedDate) => {
  if (!selectedDate) return undefined
  return String(selectedDate).replace(' ', 'T')
}

const loadGoodsOptions = async () => {
  const res = await getGoodsOptionsAPI({ type: 'product' }) // D67：生产入库只选成品
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
      confirmStatus: searchForm.confirmStatus ?? undefined,
      startDate: hasDateRange ? searchForm.dateRange[0] : undefined,
      endDate: hasDateRange ? searchForm.dateRange[1] : undefined
    }
    const res = await getProductionPageAPI(params)
    const pageData = res.data || {}
    tableData.value = (pageData.records || []).map((item) => ({
      ...item,
      productionDate: normalizeDateTime(item.productionDate || item.operationTime || item.createTime)
    }))
    total.value = pageData.total || 0
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadList()
}

const resetSearch = () => {
  searchForm.keywords = ''
  searchForm.confirmStatus = null
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
  Object.assign(dialogForm, { goodsId: null, quantity: 1, price: null, productionDate: '', remark: '' })
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    const res = await getProductionDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    Object.assign(dialogForm, {
      goodsId: detail.goodsId ?? null,
      quantity: detail.quantity ?? 1,
      price: detail.unitPrice ?? null,
      productionDate: normalizeDateTime(detail.productionDate || detail.operationTime || detail.createTime),
      remark: detail.remark || '',
      productionOrderNo: detail.productionOrderNo || '',
      confirmStatus: detail.confirmStatus ?? 2,
      confirmerName: detail.confirmerName || '',
      confirmTime: detail.confirmTime || '',
      rejectReason: detail.rejectReason || ''
    })
    dialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确定要删除该生产入库记录吗？删除后关联库存将会变更！', '警告', {
    confirmButtonText: '确定',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(async () => {
    await deleteProductionAPI(row.id)
    ElMessage.success('删除成功')
    loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

// D107：仓储确认成品入库——此刻才加库存，生产任务单同步转已完成
const handleConfirmInbound = (row) => {
  ElMessageBox.confirm(
    `确认入库申请「${row.orderNo}」（${row.goodsName} × ${row.quantity}）？确认后成品库存增加，来源生产任务单转为已完成。`,
    '确认入库', { type: 'warning' }
  ).then(async () => {
    await confirmProductionInboundAPI(row.id)
    ElMessage.success('已确认入库，成品库存已增加')
    loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

// D107：仓储驳回入库申请（附原因，通知生产提交人重新提交）
const handleRejectInbound = (row) => {
  ElMessageBox.prompt('请填写驳回原因（将通知生产提交人核对后重新提交）', '驳回入库申请', {
    confirmButtonText: '确定驳回',
    cancelButtonText: '取消',
    inputPlaceholder: '如：实物数量与申请不符',
    inputValidator: (v) => (v && v.trim() ? true : '驳回原因不能为空')
  }).then(async ({ value }) => {
    await rejectProductionInboundAPI(row.id, { reason: value.trim() })
    ElMessage.success('已驳回，已通知生产提交人')
    loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

// D94：先弹说明弹窗（后果/立即生效/留痕），确认后再作废
const handleVoid = (row) => {
  voidTarget.value = row
  voidDialogVisible.value = true
}

const submitVoid = async (reason) => {
  if (!voidTarget.value) return
  voidSubmitting.value = true
  try {
    await voidProductionAPI(voidTarget.value.id, { reason: reason || '', createRedFlush: false })
    ElMessage.success('已作废')
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
    try {
      const rawPrice = Number(dialogForm.price || 0)
      const payload = {
        goodsId: dialogForm.goodsId,
        quantity: dialogForm.quantity,
        unitPrice: rawPrice > 0 ? rawPrice : undefined,
        operationTime: buildOperationTime(dialogForm.productionDate),
        remark: dialogForm.remark || ''
      }
      await createProductionAPI(payload)
      ElMessage.success('新增成功')
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
</style>
