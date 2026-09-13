<template>
  <div class="log-page">
    <div class="paper-layer" aria-hidden="true">
      <span class="paper-grain"></span>
      <span class="paper-line"></span>
    </div>

    <el-card class="panel" shadow="never">
      <template #header>
        <div class="head-wrap">
          <p class="kicker">AUDIT TRAIL</p>
          <h2>登录日志</h2>
        </div>
      </template>

      <el-form :inline="true" :model="searchForm" class="filter-row">
        <el-form-item label="用户名" class="control-sm">
          <el-input v-model="searchForm.username" clearable placeholder="输入用户名" />
        </el-form-item>
        <el-form-item label="IP" class="control-sm">
          <el-input v-model="searchForm.ip" clearable placeholder="输入 IP" />
        </el-form-item>
        <el-form-item label="结果" class="control-xs">
          <el-select v-model="searchForm.successFlag" clearable placeholder="全部">
            <el-option :value="1" label="成功" />
            <el-option :value="0" label="失败" />
          </el-select>
        </el-form-item>
        <el-form-item label="时间区间" class="control-range">
          <el-date-picker
            v-model="searchForm.dateRange"
            type="daterange"
            range-separator="至"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
          />
        </el-form-item>
        <el-form-item class="control-actions">
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        </el-form-item>
      </el-form>

      <div class="toolbar">
        <span class="toolbar-tip">日志永久保留；删除为物理删除，删除动作会写入操作日志留痕</span>
        <div class="toolbar-actions">
          <el-button type="danger" :icon="Delete" :disabled="selectedRows.length === 0" @click="handleBatchDelete">
            批量删除{{ selectedRows.length > 0 ? `（${selectedRows.length}）` : '' }}
          </el-button>
          <el-button type="danger" plain :icon="Delete" @click="handleDeleteByQuery">按条件删除</el-button>
        </div>
      </div>

      <el-table
        :data="tableData"
        border
        stripe
        v-loading="loading"
        empty-text="暂无登录日志"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" />
        <el-table-column prop="loginTime" label="登录时间" width="180">
          <template #default="scope">{{ formatTime(scope.row.loginTime) }}</template>
        </el-table-column>
        <el-table-column prop="username" label="用户名" width="120" />
        <el-table-column prop="ip" label="IP" width="160" />
        <el-table-column prop="successFlag" label="结果" width="90" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.successFlag === 1 ? 'success' : 'danger'" effect="light">
              {{ scope.row.successFlag === 1 ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="failReason" label="失败原因" min-width="180" show-overflow-tooltip>
          <template #default="scope">{{ scope.row.failReason || '-' }}</template>
        </el-table-column>
        <el-table-column prop="userAgent" label="UA" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="scope">
            <el-button link size="small" type="primary" @click="openDetail(scope.row)">详情</el-button>
            <el-button link size="small" type="danger" @click="handleDelete(scope.row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          @size-change="handleSizeChange"
          @current-change="handleCurrentChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="登录日志详情" size="520px">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="登录时间">{{ formatTime(detail.loginTime) }}</el-descriptions-item>
        <el-descriptions-item label="用户 ID">{{ detail.userId ?? '-' }}</el-descriptions-item>
        <el-descriptions-item label="用户名">{{ detail.username || '-' }}</el-descriptions-item>
        <el-descriptions-item label="IP">{{ detail.ip || '-' }}</el-descriptions-item>
        <el-descriptions-item label="结果">{{ detail.successFlag === 1 ? '成功' : '失败' }}</el-descriptions-item>
        <el-descriptions-item label="失败原因">{{ detail.failReason || '-' }}</el-descriptions-item>
        <el-descriptions-item label="User-Agent">{{ detail.userAgent || '-' }}</el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Delete } from '@element-plus/icons-vue'
import {
  deleteLoginLogAPI,
  deleteLoginLogsAPI,
  deleteLoginLogsByQueryAPI,
  getLoginLogDetailAPI,
  getLoginLogPageAPI
} from '@/api/audit'

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)
const selectedRows = ref([])

const detailVisible = ref(false)
const detail = reactive({})

const searchForm = reactive({
  username: '',
  ip: '',
  successFlag: undefined,
  dateRange: []
})

const formatTime = (val) => {
  if (!val) return '-'
  return String(val).replace('T', ' ')
}

const buildQueryParams = () => {
  const hasRange = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
  return {
    username: searchForm.username || undefined,
    ip: searchForm.ip || undefined,
    successFlag: searchForm.successFlag,
    startDate: hasRange ? searchForm.dateRange[0] : undefined,
    endDate: hasRange ? searchForm.dateRange[1] : undefined
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const res = await getLoginLogPageAPI({
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      ...buildQueryParams()
    })
    if (res.code !== 200) throw new Error(res.msg || '加载登录日志失败')
    tableData.value = res.data?.records || []
    total.value = Number(res.data?.total || 0)
  } catch (error) {
    ElMessage.error(error.message || '加载登录日志失败')
  } finally {
    loading.value = false
  }
}

const openDetail = async (row) => {
  try {
    const res = await getLoginLogDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '加载详情失败')
    Object.assign(detail, res.data || {})
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(error.message || '加载详情失败')
  }
}

const handleSelectionChange = (rows) => {
  selectedRows.value = rows
}

// ---------- D78：删除三形态（单条 / 批量 / 按条件），物理删除且动作留痕 ----------
const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确定删除该条登录日志吗？（${formatTime(row.loginTime)} ${row.username || ''} ${row.ip || ''}）删除为物理移除，删除动作会留痕。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteLoginLogAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '删除失败')
    ElMessage.success('已删除')
    loadList()
  } catch (error) {
    ElMessage.error(error.message || '删除失败')
  }
}

const handleBatchDelete = async () => {
  const count = selectedRows.value.length
  try {
    await ElMessageBox.confirm(
      `确定删除选中的 ${count} 条登录日志吗？删除为物理移除，删除动作会留痕。`,
      '批量删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteLoginLogsAPI(selectedRows.value.map((row) => row.id))
    if (res.code !== 200) throw new Error(res.msg || '批量删除失败')
    ElMessage.success(`已删除 ${res.data ?? count} 条`)
    loadList()
  } catch (error) {
    ElMessage.error(error.message || '批量删除失败')
  }
}

const handleDeleteByQuery = async () => {
  const params = buildQueryParams()
  const hasFilter = Object.values(params).some((val) => val !== undefined)
  if (!hasFilter) {
    ElMessage.warning('按条件删除须至少设置一个筛选条件，防止误清空全部日志')
    return
  }
  try {
    await ElMessageBox.confirm(
      `将按当前筛选条件删除登录日志，当前命中约 ${total.value} 条（以后端实际删除数为准）。删除为物理移除，删除动作会留痕。确定继续吗？`,
      '按条件删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  try {
    const res = await deleteLoginLogsByQueryAPI(params)
    if (res.code !== 200) throw new Error(res.msg || '按条件删除失败')
    ElMessage.success(`已删除 ${res.data ?? 0} 条`)
    handleSearch()
  } catch (error) {
    ElMessage.error(error.message || '按条件删除失败')
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadList()
}

const handleReset = () => {
  searchForm.username = ''
  searchForm.ip = ''
  searchForm.successFlag = undefined
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

onMounted(() => {
  loadList()
})
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=Cinzel:wght@500;700&family=Manrope:wght@400;500;700&display=swap');


.log-page {
  --paper: #f4efe6;
  --paper-soft: #f8f3eb;

  position: relative;
  min-height: calc(100vh - 120px);
  padding: 20px;
  border: 1px solid rgba(51, 44, 35, 0.16);
  border-radius: 8px;
  overflow: hidden;
  font-family: 'Manrope', 'Segoe UI', sans-serif;
  background:
    radial-gradient(circle at 8% 10%, rgba(198, 176, 147, 0.16), transparent 36%),
    radial-gradient(circle at 90% 6%, rgba(160, 183, 201, 0.14), transparent 30%),
    linear-gradient(135deg, var(--paper) 0%, var(--paper-soft) 100%);
}

.paper-layer {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.paper-grain {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background-image: radial-gradient(rgba(0, 0, 0, 0.11) 0.45px, transparent 0.45px);
  background-size: 4px 4px;
}

.paper-line {
  position: absolute;
  inset: 12px;
  border: 1px solid rgba(51, 44, 35, 0.1);
}

.panel {
  position: relative;
  z-index: 1;
  border: 1px solid rgba(44, 37, 31, 0.15);
  background: rgba(255, 255, 255, 0.64);
}

.kicker {
  margin: 0;
  font-size: 11px;
  letter-spacing: 0.3em;
  color: #2e4e66;
}

.head-wrap h2 {
  margin: 4px 0 0;
  font-family: 'Cinzel', 'Times New Roman', serif;
}

.filter-row {
  margin-bottom: 8px;
}

:deep(.filter-row .control-sm .el-input) {
  width: 140px;
}

:deep(.filter-row .control-xs .el-select) {
  width: 110px;
}

:deep(.filter-row .control-range .el-date-editor.el-input__wrapper),
:deep(.filter-row .control-range .el-date-editor.el-range-editor) {
  width: 250px;
}

:deep(.filter-row .control-actions .el-form-item__content) {
  display: inline-flex;
  gap: 8px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.toolbar-tip {
  font-size: 12px;
  color: rgba(51, 44, 35, 0.55);
}

.toolbar-actions {
  display: inline-flex;
  gap: 8px;
}

.pager {
  margin-top: 16px;
  display: flex;
  justify-content: flex-end;
}

@media (max-width: 768px) {
  .log-page {
    padding: 12px;
  }
}
</style>


