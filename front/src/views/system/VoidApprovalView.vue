<template>
  <el-card>
    <!-- D120 单页共管：销售管理员只看价格偏离确认行，仓储管理员/超管只看作废类行（行级由后端过滤） -->
    <el-alert
      v-if="isSalesApprover"
      type="info"
      :closable="false"
      title="价格偏离审批：仅显示「价格偏离确认」类审批，通过后仓储方可确认出库"
      style="margin-bottom: 12px"
    />
    <el-alert
      v-else
      type="info"
      :closable="false"
      title="作废审批：仅显示作废类审批（价格偏离审批由销售管理员处理）"
      style="margin-bottom: 12px"
    />
    <el-form :inline="true" :model="searchForm" class="search-form">
      <el-form-item label="审批单号">
        <el-input v-model="searchForm.approvalNo" placeholder="请输入审批单号" clearable />
      </el-form-item>
      <el-form-item label="业务类型">
        <el-select v-model="searchForm.bizType" placeholder="全部" clearable style="width: 160px;">
          <el-option v-for="item in bizTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="申请动作">
        <el-select v-model="searchForm.requestAction" placeholder="全部" clearable style="width: 160px;">
          <el-option v-for="item in actionFilterOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 140px;">
          <el-option label="待审批" :value="1" />
          <el-option label="已通过" :value="2" />
          <el-option label="已驳回" :value="3" />
        </el-select>
      </el-form-item>
      <el-form-item class="search-actions">
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData" border style="width: 100%" v-loading="loading">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="approvalNo" label="审批单号" width="180" />
      <el-table-column label="业务类型" width="120">
        <template #default="scope">
          {{ bizTypeLabel(scope.row.bizType) }}
        </template>
      </el-table-column>
      <el-table-column prop="bizNo" label="业务单号" width="170" />
      <el-table-column label="申请动作" width="120">
        <template #default="scope">
          {{ actionLabel(scope.row.requestAction) }}
        </template>
      </el-table-column>
      <el-table-column prop="requestReason" label="申请原因" min-width="180" show-overflow-tooltip />
      <el-table-column prop="requesterName" label="申请人" width="120" />
      <el-table-column label="状态" width="100">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="申请时间" width="170">
        <template #default="scope">{{ formatDateTime(scope.row.createTime) }}</template>
      </el-table-column>
      <el-table-column prop="approverName" label="审批人" width="120" />
      <el-table-column label="审批时间" width="170">
        <template #default="scope">{{ formatDateTime(scope.row.approvedAt || scope.row.rejectedAt) }}</template>
      </el-table-column>
      <el-table-column prop="approveRemark" label="审批备注" min-width="160" show-overflow-tooltip />
      <el-table-column label="操作" width="210" fixed="right">
        <template #default="scope">
          <!-- D137：详情置首（ADR-0007），全部行可见——价格偏离行展示建单时点价格快照 -->
          <el-button size="small" type="primary" link @click="openDetail(scope.row)">详情</el-button>
          <el-button
            v-if="scope.row.status === 1"
            size="small"
            type="success"
            link
            @click="handleApprove(scope.row)"
          >
            通过
          </el-button>
          <el-button
            v-if="scope.row.status === 1"
            size="small"
            type="warning"
            link
            @click="handleReject(scope.row)"
          >
            驳回
          </el-button>
          <span v-else class="muted-text">已处理</span>
        </template>
      </el-table-column>
    </el-table>

    <div class="pager-box">
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

    <!-- D137 审批详情弹窗（只读，动作仍在列表）：价格偏离行展示建单时点价格快照，作废类展示单据信息 -->
    <el-dialog v-model="detailVisible" title="审批详情" width="780px">
      <div v-loading="detailLoading">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="审批单号" :span="2">{{ detail?.approvalNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="业务类型">{{ bizTypeLabel(detail?.bizType) }}</el-descriptions-item>
          <el-descriptions-item label="业务单号">{{ detail?.bizNo || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请动作">{{ actionLabel(detail?.requestAction) }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail?.status)">{{ statusLabel(detail?.status) }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="审批人">{{ detail?.approverName || '-' }}</el-descriptions-item>
          <el-descriptions-item label="审批时间">{{ formatDateTime(detail?.approvedAt || detail?.rejectedAt) }}</el-descriptions-item>
          <el-descriptions-item label="审批备注" :span="2">{{ detail?.approveRemark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请原因" :span="2">{{ detail?.requestReason || '-' }}</el-descriptions-item>
        </el-descriptions>

        <!-- Q2a 建单时点快照：仅价格偏离确认类且有快照时展示（Q7a 无快照降级为仅原因文本） -->
        <template v-if="snapshotRows.length">
          <div class="snapshot-title">
            价格偏离明细（建单时点快照，审批阈值 {{ snapshotThreshold ?? '-' }}%）
          </div>
          <el-table :data="snapshotRows" border size="small">
            <el-table-column prop="lineNo" label="行号" width="60" />
            <el-table-column prop="goodsName" label="商品" min-width="140" show-overflow-tooltip />
            <el-table-column prop="quantity" label="数量" width="80" />
            <el-table-column label="本次售价" width="100">
              <template #default="scope">{{ money(scope.row.unitPrice) }}</template>
            </el-table-column>
            <el-table-column label="标准售价" width="100">
              <template #default="scope">{{ money(scope.row.standardSalePrice) }}</template>
            </el-table-column>
            <el-table-column label="偏离金额" width="110">
              <template #default="scope">
                <span :class="scope.row.deviationAmount > 0 ? 'deviation-up' : 'deviation-down'">
                  {{ scope.row.deviationAmount > 0 ? '+' : '' }}{{ money(scope.row.deviationAmount) }}
                </span>
              </template>
            </el-table-column>
            <el-table-column label="偏离%" width="90">
              <template #default="scope">{{ scope.row.deviationPercent }}%</template>
            </el-table-column>
          </el-table>
        </template>

        <div class="dialog-footer">
          <el-button @click="detailVisible = false">关闭</el-button>
        </div>
      </div>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import {
  approveApprovalOrderAPI,
  getApprovalOrderDetailAPI,
  getApprovalOrderPageAPI,
  rejectApprovalOrderAPI
} from '@/api/system'
import { getDeptCode, getRole } from '@/utils/auth'

// D120：价格偏离审批行只归销售管理员处理（与后端 requireApproverAccess 行级过滤一致）
const isSalesApprover = getRole() === 'admin' && getDeptCode() === 'sales'

const bizTypeOptions = [
  { label: '进货单', value: 'purchase' },
  { label: '进货退货单', value: 'purchase_return' },
  { label: '销售单', value: 'sales' },
  { label: '销售退货单', value: 'sales_return' }
]

const actionOptions = [
  { label: '作废', value: 'void' },
  { label: '价格偏离确认', value: 'price_deviation_confirm' }
]

// D120：筛选项按角色收窄（行级数据仍由后端过滤兜底）
const actionFilterOptions = computed(() =>
  isSalesApprover
    ? actionOptions.filter((o) => o.value === 'price_deviation_confirm')
    : actionOptions.filter((o) => o.value !== 'price_deviation_confirm')
)

const searchForm = reactive({
  approvalNo: '',
  bizType: '',
  requestAction: '',
  status: null
})

const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

// D137 详情弹窗状态（只读，动作仍在列表）
const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const money = (val) => Number(val ?? 0).toFixed(2)

const formatDateTime = (val) => {
  if (!val) return '-'
  return String(val).replace('T', ' ')
}

const bizTypeLabel = (val) => {
  return bizTypeOptions.find((item) => item.value === val)?.label || val || '-'
}

const actionLabel = (val) => {
  if (val === 'void_red') return '作废并冲抵' // D74：入口已隐藏，历史记录仍可辨识
  return actionOptions.find((item) => item.value === val)?.label || val || '-'
}

const statusLabel = (status) => {
  if (status === 1) return '待审批'
  if (status === 2) return '已通过'
  if (status === 3) return '已驳回'
  return '未知'
}

const statusTagType = (status) => {
  if (status === 1) return 'warning'
  if (status === 2) return 'success'
  if (status === 3) return 'danger'
  return 'info'
}

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      approvalNo: searchForm.approvalNo || undefined,
      bizType: searchForm.bizType || undefined,
      requestAction: searchForm.requestAction || undefined,
      status: searchForm.status === null ? undefined : searchForm.status
    }
    const res = await getApprovalOrderPageAPI(params)
    const pageData = res.data || {}
    tableData.value = pageData.records || []
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
  searchForm.approvalNo = ''
  searchForm.bizType = ''
  searchForm.requestAction = ''
  searchForm.status = null
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

const handleApprove = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('可填写审批备注（选填）', '审批通过', {
      confirmButtonText: '通过',
      cancelButtonText: '取消',
      inputValue: ''
    })

    await approveApprovalOrderAPI(row.id, { remark: value || '' })
    ElMessage.success('审批通过')
    await loadList()
  } catch {
    // 取消或业务错误已统一提示
  }
}

const handleReject = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请输入驳回原因（选填）', '审批驳回', {
      confirmButtonText: '驳回',
      cancelButtonText: '取消',
      inputValue: ''
    })

    await rejectApprovalOrderAPI(row.id, { remark: value || '' })
    ElMessage.success('已驳回审批申请')
    await loadList()
  } catch {
    // 取消或业务错误已统一提示
  }
}

// D137 详情弹窗：按 id 拉取详情（行级过滤由后端保证，越权报「审批单不存在」）
const openDetailById = async (id) => {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    const res = await getApprovalOrderDetailAPI(id)
    detail.value = res.data || null
  } catch {
    detailVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

const openDetail = (row) => openDetailById(row.id)

// Q2a/Q7a：requestDetail 快照 JSON 解析（存量无快照或解析失败时 rows 为空 → 仅展示原因文本）
const snapshotData = computed(() => {
  const raw = detail.value?.requestDetail
  if (!raw) return null
  try {
    const obj = JSON.parse(raw)
    if (!obj || !Array.isArray(obj.rows) || !obj.rows.length) return null
    return obj
  } catch {
    return null
  }
})
const snapshotRows = computed(() => snapshotData.value?.rows || [])
const snapshotThreshold = computed(() => snapshotData.value?.thresholdPercent ?? null)

// D75 深链范式：消息 ?approvalId= 直达详情，消费后清 query 防刷新重复弹窗
const route = useRoute()
const router = useRouter()
watch(() => route.query.approvalId, (v) => {
  if (v) {
    const id = Number(v)
    if (Number.isFinite(id)) {
      openDetailById(id)
    }
    router.replace({ query: {} })
  }
}, { immediate: true })

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.search-form {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
}

.search-form :deep(.search-actions) {
  margin-left: auto;
}

.pager-box {
  margin-top: 20px;
  display: flex;
  justify-content: flex-end;
}

.muted-text {
  color: #909399;
  font-size: 12px;
}

.snapshot-title {
  margin: 16px 0 8px;
  font-weight: 600;
  font-size: 14px;
  color: #303133;
}

/* 红涨绿跌：高于标准价红、低于标准价绿 */
.deviation-up { color: #f56c6c; }
.deviation-down { color: #67c23a; }

.dialog-footer {
  margin-top: 16px;
  text-align: right;
}
</style>
