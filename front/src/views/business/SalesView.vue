<template>
  <div class="sales-container">
    <el-card>
      <div class="search-box">
        <div class="top-right-help">
          <span class="help-label">作废:</span>
          <el-tooltip content="当天单据可直接删除；历史单据的作废会提交给仓储管理员审批，通过后才执行。" placement="left">
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
        <el-table-column prop="goodsName" label="出库商品" />
        <el-table-column prop="customerName" label="客户公司名" width="140" show-overflow-tooltip />
        <el-table-column prop="remark" label="备注" show-overflow-tooltip />
        <el-table-column prop="quantity" label="销售数量" width="100" />
        <!-- D69：仓储视角当前库存列——待确认且缺货的行标红，提示先协调生产/采购再确认出库 -->
        <el-table-column v-if="isWarehouseUser" label="当前库存" width="130">
          <template #default="scope">
            <span v-if="isShortageRow(scope.row)" class="stock-shortage-text">库存不足（需{{ scope.row.quantity }}/现存{{ scope.row.stock ?? 0 }}）</span>
            <span v-else>{{ scope.row.stock ?? '—' }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="showPrice" prop="salesPrice" label="销售均价(元)" width="120" />
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
            <el-tag :type="scope.row.confirmStatus === 2 ? 'success' : 'warning'" size="small">
              {{ scope.row.confirmStatusText || (scope.row.confirmStatus === 2 ? '已确认出库' : '待仓库确认') }}
            </el-tag>
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
              <el-button
                v-if="scope.row.confirmStatus === 1 && !isBizDocumentDeleted(scope.row)"
                v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }"
                size="small"
                type="success"
                link
                @click="handleConfirm(scope.row)"
              >
                确认出库
              </el-button>
              <el-button
                v-if="showDeleteAction(scope.row)"
                v-permission="{ roles: ['admin', 'employee'], deptCodes: ['sales'] }"
                size="small"
                type="danger"
                link
                @click="handleDelete(scope.row)"
              >
                删除
              </el-button>
              <template v-else-if="showVoidActions(scope.row)">
                <el-button
                  v-permission="{ roles: ['admin'], deptCodes: ['sales'] }"
                  size="small"
                  type="warning"
                  link
                  :disabled="!canVoid(scope.row)"
                  @click="handleVoid(scope.row)"
                >
                  作废
                </el-button>
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

    <el-dialog :title="dialogType === 'view' ? '销售单详情' : '新增销售单'" v-model="dialogVisible" width="500px">
      <el-form ref="dialogFormRef" :model="dialogForm" :rules="dialogRules" label-width="100px" :disabled="dialogType === 'view'">
        <el-form-item label="客户公司名" prop="customerName">
          <el-input v-model="dialogForm.customerName" placeholder="请输入客户公司名（可选）"></el-input>
        </el-form-item>
        <el-form-item label="合同编号" prop="contractNo">
          <el-input v-model="dialogForm.contractNo" placeholder="请输入合同编号（可选）"></el-input>
        </el-form-item>
        <el-form-item label="出库商品" prop="goodsId">
          <el-select v-model="dialogForm.goodsId" placeholder="请选择商品" style="width: 100%">
            <el-option v-for="item in goodsOptions" :key="item.id" :label="`${item.name}（库存 ${item.stock || 0}${item.unit ? ' ' + item.unit : ''}）`" :value="item.id" />
          </el-select>
          <div v-if="selectedStock !== null" class="stock-hint">当前库存：{{ selectedStock }} {{ selectedUnit }}<span v-if="selectedSalePrice"> ｜ 标准售价：¥{{ selectedSalePrice }}</span></div>
        </el-form-item>
        <el-form-item label="备注" prop="remark">
          <el-input v-model="dialogForm.remark" placeholder="请输入备注说明"></el-input>
        </el-form-item>
        <el-form-item label="出库数量" prop="quantity">
          <el-input-number v-model="dialogForm.quantity" :min="1" style="width: 100%" />
          <!-- D69：缺货提示（不拦截建单），现货不足将通知生产管理员排产 -->
          <div v-if="isShortage" class="shortage-hint">⚠ 当前库存不足（需 {{ dialogForm.quantity }} / 现存 {{ selectedStock }}），建单后将通知生产排产，可在详情查看履约进度</div>
        </el-form-item>
        <el-form-item v-if="showPrice" label="销售单价" prop="unitPrice">
          <el-input-number v-model="dialogForm.unitPrice" :min="0.01" :precision="2" :step="0.1" style="width: 100%" />
          <div v-if="isPriceDeviated" class="price-deviation-hint">⚠ 销售价偏离标准售价 {{ priceDeviationPct }}%，超 {{ priceDeviationThresholdPct }}% 阈值，提交后将需超管审批后仓储方可确认出库</div>
        </el-form-item>
        <el-form-item v-if="showPrice" label="是否含税" prop="taxIncluded">
          <el-radio-group v-model="dialogForm.taxIncluded">
            <el-radio :value="1">含税</el-radio>
            <el-radio :value="0">不含税</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="showPrice" label="销售总额" prop="totalAmount">
          <el-input :value="totalAmountText" disabled>
            <template #append>元</template>
          </el-input>
        </el-form-item>
        <el-form-item label="出库日期" prop="salesDate">
          <el-date-picker
            v-model="dialogForm.salesDate"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="请选择出库时间"
            style="width: 100%"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <span class="dialog-footer">
          <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
          <el-button v-if="dialogType !== 'view'" type="primary" :icon="Check" @click="submitForm">确定新增</el-button>
        </span>
      </template>
      <!-- D71：履约时间线（类淘宝物流），仅详情态展示 -->
      <SalesTimeline v-if="dialogType === 'view' && dialogVisible" :sales-id="currentViewId" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { QuestionFilled, Search, Refresh, Plus, Delete, DocumentRemove, DocumentDelete, Close, Check } from '@element-plus/icons-vue'
import { createApprovalOrderAPI } from '@/api/system'
import { getPriceDeviationThresholdAPI } from '@/api/config'
import { hasBizDocumentWorkflowState, isBizDocumentDeleted, resolveBizDocumentState } from '@/utils/bizDocumentState'
import { isEmployeeRole, getRole } from '@/utils/auth'
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

const searchForm = reactive({ keywords: '', customerName: '', dateRange: [] })
const userRole = getRole()
const userDept = getDeptCode()
// D36：销售金额列仅销售部门可见（售价）；仓储看库存不看价格；超管全见
const showPrice = userDept === 'sales' || isSuperAdmin(userRole)
// D69：仓储视角展示当前库存列并标红缺货待确认单
const isWarehouseUser = userDept === 'warehouse'
const isShortageRow = (row) =>
  Number(row?.confirmStatus) === 1 && !isBizDocumentDeleted(row) && Number(row?.quantity) > Number(row?.stock ?? 0)
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
const dialogForm = reactive({ goodsId: null, remark: '', quantity: 1, unitPrice: 0, salesDate: '', customerName: '', contractNo: '', taxIncluded: 0 })

const totalAmountText = computed(() => {
  const qty = Number(dialogForm.quantity || 0)
  const price = Number(dialogForm.unitPrice || 0)
  return (qty * price).toFixed(2)
})

const selectedGoods = computed(() => goodsOptions.value.find((i) => i.id === dialogForm.goodsId) || null)
const selectedStock = computed(() => (selectedGoods.value ? selectedGoods.value.stock ?? 0 : null))
const selectedUnit = computed(() => selectedGoods.value?.unit || '')
const selectedSalePrice = computed(() => selectedGoods.value?.salePrice ?? null)
// 价格偏离比例（D30：阈值由超管在系统参数页配置，默认 5%）
const priceDeviationThreshold = ref(0.05) // 比例小数，如 0.05
const priceDeviationThresholdPct = computed(() => Math.round(priceDeviationThreshold.value * 100))
const priceDeviationPct = computed(() => {
  const sp = selectedSalePrice.value
  const up = dialogForm.unitPrice
  if (!sp || sp <= 0 || !up || up <= 0) return 0
  return Math.round(Math.abs(up - sp) / sp * 100)
})
const isPriceDeviated = computed(() => priceDeviationPct.value > priceDeviationThresholdPct.value)
// D69：建单缺货提示（零库存/超卖允许建单）
const isShortage = computed(() => {
  const s = selectedStock.value
  return s !== null && Number(dialogForm.quantity) > s
})

// D69：定制公司销售单=需求单，零库存/超卖均可建单（缺货自动通知生产排产，出库时仓储硬校验兜底），
// 故不再钳制数量、不再拦截库存为 0，仅在输入区提示缺货。

const dialogRules = {
  goodsId: [{ required: true, message: '请选择商品', trigger: 'change' }],
  quantity: [
    { required: true, message: '请输入数量', trigger: 'blur' }
  ],
  unitPrice: [{ required: true, message: '请输入销售单价', trigger: 'blur' }],
  salesDate: [{ required: true, message: '请选择销售日期', trigger: 'change' }]
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
  return row?.salesDate || row?.operationTime || row?.createTime || ''
}

const canDelete = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  // 员工仅可删除未出库（confirmStatus=1）的当天单，用于被驳回后改价重提；admin 可删已出库当天单（回补库存）
  if (isEmployeeRole(getRole()) && Number(row?.confirmStatus) !== 1) return false
  return toDateOnly(resolveBizDate(row)) === localToday()
}

const canVoid = (row) => {
  if (isBizDocumentDeleted(row)) return false
  if (row?.bizStatus !== 1) return false
  return toDateOnly(resolveBizDate(row)) !== localToday()
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

const buildOperationTime = (selectedDate) => {
  if (!selectedDate) return undefined
  return String(selectedDate).replace(' ', 'T')
}

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
  Object.assign(dialogForm, { goodsId: null, remark: '', quantity: 1, unitPrice: 0, salesDate: '', customerName: '', contractNo: '', taxIncluded: 0 })
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    const res = await getSalesDetailAPI(row.id)
    const detail = res.data || {}
    dialogType.value = 'view'
    currentViewId.value = row.id
    Object.assign(dialogForm, {
      goodsId: detail.goodsId ?? null,
      remark: detail.remark || '',
      quantity: detail.quantity ?? 1,
      unitPrice: detail.salesPrice ?? detail.unitPrice ?? 0,
      salesDate: normalizeDateTime(detail.salesDate || detail.operationTime || detail.createTime),
      customerName: detail.customerName || '',
      contractNo: detail.contractNo || '',
      taxIncluded: detail.taxIncluded ?? 0
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
  ElMessageBox.confirm('确认出库将从库存扣减该销售数量，确认继续吗？', '确认出库', { type: 'warning' }).then(async () => {
    await confirmSalesAPI(row.id)
    ElMessage.success('已确认出库')
    loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleVoid = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入作废原因', '作废单据', {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '默认: 手工作废',
      inputValue: ''
    })

    await createApprovalOrderAPI({
      bizType: 'sales',
      bizId: row.id,
      requestAction: 'void',
      reason: value || ''
    })

    ElMessage.success('作废审批已提交，等待仓储管理员处理')
    await loadList()
  } catch {
    // 取消或业务错误已统一提示
  }
}

const submitForm = () => {
  dialogFormRef.value.validate(async (valid) => {
    if (!valid) {
      return
    }
    try {
      const payload = {
        goodsId: dialogForm.goodsId,
        quantity: dialogForm.quantity,
        unitPrice: Number(dialogForm.unitPrice),
        operationTime: buildOperationTime(dialogForm.salesDate),
        customerName: dialogForm.customerName || undefined,
        contractNo: dialogForm.contractNo || undefined,
        taxIncluded: dialogForm.taxIncluded ?? 0,
        remark: dialogForm.remark || ''
      }
      await createSalesAPI(payload)
      ElMessage.success('销售完成')
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
.stock-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
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

.shortage-hint {
  margin-top: 4px;
  font-size: 12px;
  color: #e6a23c;
  line-height: 1.4;
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
