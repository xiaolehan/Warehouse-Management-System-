<template>
  <div class="pick-list-view">
    <el-card shadow="never">
      <!-- 查询区 -->
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="单号">
          <el-input v-model="searchForm.pickNo" placeholder="领料单号" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="searchForm.pickType" placeholder="全部" clearable style="width: 120px">
            <el-option label="领料" value="PICK" />
            <el-option label="补料" value="SUPPLY" />
            <el-option label="退料" value="RETURN" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="待发料" :value="1" />
            <el-option label="已发料" :value="2" />
            <el-option label="已完成" :value="3" />
            <el-option label="已驳回" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品名">
          <el-input v-model="searchForm.goodsName" placeholder="明细商品名" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="searchForm.dateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <div style="margin-bottom: 12px;">
        <el-button type="danger" :disabled="batchSelectedRows.length === 0" @click="handleBatchDelete" v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">
          批量撤销{{ batchSelectedRows.length > 0 ? `（${batchSelectedRows.length}）` : '' }}
        </el-button>
      </div>
      <el-table v-loading="loading" :data="tableData" border stripe @selection-change="handleBatchSelectionChange">
        <el-table-column type="selection" width="46" />
        <el-table-column prop="pickNo" label="单号" width="180" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.pickTypeText }}</template>
        </el-table-column>
        <el-table-column label="明细">
          <template #default="{ row }">
            <div v-for="d in row.details" :key="d.id" class="detail-line">
              {{ d.goodsName }} × {{ d.quantity }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="applicantName" label="申请人" width="100" />
        <el-table-column prop="operatorName" label="发料人" width="100" />
        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link size="small" type="primary" @click="handleView(row)">详情</el-button>
            <el-button link size="small" type="success" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleIssue(row)">发料</el-button>
            <el-button link size="small" type="warning" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleReject(row)">驳回</el-button>
            <el-button link size="small" type="success" v-if="row.status === 2 && isApplicant(row)" @click="handleConfirm(row)">确认收货</el-button>
            <el-button link size="small" type="danger" v-if="row.status === 1 && isApplicant(row)" @click="handleDelete(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="total" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="[10, 20, 50]" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
    </el-card>

    <!-- 详情对话框 -->
    <el-dialog v-model="viewVisible" title="领料单详情" width="760px">
      <el-descriptions :column="2" border v-if="viewData">
        <el-descriptions-item label="单号">{{ viewData.pickNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ viewData.pickTypeText }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ viewData.statusText }}</el-descriptions-item>
        <el-descriptions-item label="关联销售单">{{ viewData.sourceSalesId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ viewData.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="发料人">{{ viewData.operatorName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ formatTime(viewData.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="发料时间">{{ formatTime(viewData.operationTime) }}</el-descriptions-item>
        <el-descriptions-item label="确认时间">{{ formatTime(viewData.confirmTime) }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ viewData.remark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因" :span="2" v-if="viewData.rejectReason">{{ viewData.rejectReason }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="viewData?.details || []" border size="small" style="margin-top: 12px">
        <el-table-column label="序号" width="60" type="index" />
        <el-table-column prop="goodsName" label="物料" />
        <el-table-column label="规格" width="130">
          <template #default="{ row }">{{ row.spec || '—' }}</template>
        </el-table-column>
        <el-table-column label="材质" width="110">
          <template #default="{ row }">{{ row.material || '—' }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="150">
          <template #default="{ row }">{{ row.remark || '—' }}</template>
        </el-table-column>
        <el-table-column prop="quantity" label="数量" width="70" />
      </el-table>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectVisible" title="驳回领料单" width="480px">
      <el-form>
        <el-form-item label="驳回原因" required>
          <el-input v-model="rejectForm.reason" type="textarea" :rows="3" placeholder="请填写驳回原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted, h } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import {
  getPickListPageAPI, getPickListDetailAPI,
  issuePickListAPI, confirmPickListAPI, rejectPickListAPI, deletePickListAPI
} from '@/api/pickList'
import { batchDeletePickListsAPI } from '@/api/pickList.js'

const userStore = useUserStore()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ pickNo: '', pickType: '', status: '', goodsName: '', dateRange: [] })

const submitting = ref(false)

const viewVisible = ref(false)
const viewData = ref(null)

const rejectVisible = ref(false)
const rejectForm = reactive({ id: null, reason: '' })

const isApplicant = (row) => row.applicantName && row.applicantName === userStore.realName

const statusTagType = (status) => ({
  1: 'info', 2: 'warning', 3: 'success', 4: 'danger'
}[status] || 'info')

const formatTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '—'

const loadList = async () => {
  loading.value = true
  try {
    const hasDate = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      pickNo: searchForm.pickNo || undefined,
      pickType: searchForm.pickType || undefined,
      status: searchForm.status || undefined,
      goodsName: searchForm.goodsName || undefined,
      startDate: hasDate ? searchForm.dateRange[0] : undefined,
      endDate: hasDate ? searchForm.dateRange[1] : undefined
    }
    const res = await getPickListPageAPI(params)
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const handleSearch = () => { currentPage.value = 1; loadList() }
const resetSearch = () => {
  Object.assign(searchForm, { pickNo: '', pickType: '', status: '', goodsName: '', dateRange: [] })
  currentPage.value = 1; loadList()
}
const handleSizeChange = (v) => { pageSize.value = v; currentPage.value = 1; loadList() }
const handleCurrentChange = (v) => { currentPage.value = v; loadList() }

const handleView = async (row) => {
  try {
    const res = await getPickListDetailAPI(row.id)
    viewData.value = res.data
    viewVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// D63：发料确认逐行列出「物料（规格/材质）×数量」，消除仓储找货歧义
const issueDetailLines = (details) => (details || []).map(d => {
  const sm = [d.spec, d.material].filter(Boolean).join(' / ')
  return h('div', { style: 'line-height: 1.8' }, `${d.goodsName}（${sm || '—'}）× ${d.quantity}`)
})

const handleIssue = (row) => {
  const tip = row.pickTypeText === '退料' ? '确认退料入库？退料将回流入库。' : '确认发料？将扣减库存。'
  const hasLines = (row.details || []).length > 0
  ElMessageBox.confirm(
    hasLines
      ? h('div', null, [h('div', { style: 'margin-bottom: 8px' }, tip), ...issueDetailLines(row.details)])
      : tip,
    '发料确认', { type: 'warning' }
  )
    .then(async () => {
      await issuePickListAPI(row.id)
      ElMessage.success('发料成功')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleConfirm = (row) => {
  ElMessageBox.confirm('确认已收到物料？', '确认收货', { type: 'warning' })
    .then(async () => {
      await confirmPickListAPI(row.id)
      ElMessage.success('已确认收货')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

const handleReject = (row) => {
  rejectForm.id = row.id
  rejectForm.reason = ''
  rejectVisible.value = true
}

const submitReject = async () => {
  if (!rejectForm.reason) return ElMessage.warning('请填写驳回原因')
  submitting.value = true
  try {
    await rejectPickListAPI(rejectForm.id, { reason: rejectForm.reason })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    submitting.value = false
  }
}

// 手测问题 1（2026-09-23）：批量删除——尽力而为，能删的删，失败明细弹出
const batchSelectedRows = ref([])
const handleBatchSelectionChange = (val) => {
  batchSelectedRows.value = val
}
const handleBatchDelete = async () => {
  try {
    await ElMessageBox.confirm(`确认批量撤销选中的 ${batchSelectedRows.value.length} 张领料单吗？不满足条件的将跳过并提示。`, '警告', { type: 'warning' })
    const res = await batchDeletePickListsAPI(batchSelectedRows.value.map((r) => r.id))
    const data = res.data || {}
    if (data.failureCount > 0) {
      const detail = (data.failures || []).map((f) => `${f.name || f.id}：${f.reason}`).join('；')
      ElMessage.warning(`成功 ${data.successCount} 条，失败 ${data.failureCount} 条：${detail}`)
    } else {
      ElMessage.success(`成功撤销 ${data.successCount} 条`)
    }
    batchSelectedRows.value = []
    await loadList()
  } catch {
    // 用户取消或业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确认撤销该领料申请？', '警告', { type: 'warning' })
    .then(async () => {
      await deletePickListAPI(row.id)
      ElMessage.success('已撤销')
      loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

onMounted(loadList)
</script>

<style scoped>
.pick-list-view { padding: 12px; }
.search-form { margin-bottom: 8px; }
.detail-line { line-height: 1.6; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
