<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item label="任务单号">
        <el-input v-model="searchForm.orderNo" placeholder="请输入任务单号" clearable />
      </el-form-item>
      <el-form-item label="成品名称">
        <el-input v-model="searchForm.goodsName" placeholder="请输入成品名称" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
      </el-form-item>
    </el-form>

    <el-alert
      type="info" :closable="false" show-icon
      title="首测与成品测均合格后，订单进入待入库，方可生产入库；NG 需处置（返工后重测 / 报废）。"
      style="margin-bottom: 12px"
    />

    <el-table :data="tableData" border style="width: 100%" v-loading="loading">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="orderNo" label="任务单号" min-width="130" />
      <el-table-column prop="goodsName" label="成品名称" min-width="170" />
      <el-table-column prop="quantity" label="数量" width="80" align="center" />
      <el-table-column prop="statusText" label="订单状态" width="100" align="center">
        <template #default="{ row }">
          <el-tag :type="statusTagType(row.status)">{{ row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="qcState" label="质检进度" min-width="220">
        <template #default="{ row }">
          <span>首测 <el-tag size="small" :type="pointTagType(row.qcState?.firstStatus)">{{ row.qcState?.firstStatusText || '未测' }}</el-tag></span>
          <span style="margin-left: 12px">成品测 <el-tag size="small" :type="pointTagType(row.qcState?.finalStatus)">{{ row.qcState?.finalStatusText || '未测' }}</el-tag></span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" align="center">
        <template #default="{ row }">
          <el-button size="small" type="primary" :icon="Edit" @click="openQc(row)">质检</el-button>
        </template>
      </el-table-column>
      <template #empty>暂无可质检的生产任务单（生产中/待入库）</template>
    </el-table>

    <div style="display: flex; justify-content: flex-end; margin-top: 12px">
      <el-pagination
        background layout="total, sizes, prev, pager, next"
        :total="total" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="[10, 20, 50]"
        @size-change="handleSizeChange" @current-change="handleCurrentChange"
      />
    </div>

    <!-- 质检详情弹窗 -->
    <el-dialog v-model="qcVisible" title="生产质检" width="640px" destroy-on-close>
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" style="margin-bottom: 12px">
          <el-descriptions-item label="任务单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="成品">{{ detail.goodsName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.quantity }} {{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">
            <el-tag :type="statusTagType(detail.status)">{{ detail.statusText }}</el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <el-row :gutter="12" style="margin-bottom: 12px">
          <el-col :span="12">
            <div class="point-card">
              <div class="point-title">首测</div>
              <el-tag :type="pointTagType(qcState?.firstStatus)">{{ qcState?.firstStatusText || '未测' }}</el-tag>
              <div v-if="qcState?.firstStatus === 'ng'" style="margin-top: 8px">
                <el-button size="small" type="warning" @click="doDispose('first', 'REWORK')">返工重测</el-button>
                <el-button size="small" type="danger" @click="doDispose('first', 'SCRAP')">报废</el-button>
              </div>
            </div>
          </el-col>
          <el-col :span="12">
            <div class="point-card">
              <div class="point-title">成品测</div>
              <el-tag :type="pointTagType(qcState?.finalStatus)">{{ qcState?.finalStatusText || '未测' }}</el-tag>
              <div v-if="qcState?.finalStatus === 'ng'" style="margin-top: 8px">
                <el-button size="small" type="warning" @click="doDispose('final', 'REWORK')">返工重测</el-button>
                <el-button size="small" type="danger" @click="doDispose('final', 'SCRAP')">报废</el-button>
              </div>
            </div>
          </el-col>
        </el-row>

        <el-divider content-position="left">录入测试结果</el-divider>
        <el-form :inline="true" :model="recordForm">
          <el-form-item label="测点">
            <el-select v-model="recordForm.testPoint" placeholder="选择测点" style="width: 120px">
              <el-option label="首测" value="first" />
              <el-option label="成品测" value="final" />
            </el-select>
          </el-form-item>
          <el-form-item label="结果">
            <el-select v-model="recordForm.result" placeholder="选择结果" style="width: 110px">
              <el-option label="合格 OK" value="OK" />
              <el-option label="不合格 NG" value="NG" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="recordForm.result === 'NG'" label="不合格原因">
            <el-input v-model="recordForm.reason" placeholder="NG 必填" clearable style="width: 180px" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" :icon="Check" @click="doRecord">提交</el-button>
          </el-form-item>
        </el-form>

        <el-divider content-position="left">测试记录</el-divider>
        <el-table :data="qcState?.records || []" border size="small" max-height="220">
          <el-table-column prop="testPointText" label="测点" width="80" />
          <el-table-column prop="result" label="结果" width="80">
            <template #default="{ row }">
              <el-tag size="small" :type="row.result === 'OK' ? 'success' : 'danger'">{{ row.result }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="testerName" label="测试人" width="90" />
          <el-table-column prop="reason" label="原因" min-width="120" />
          <el-table-column prop="dispositionText" label="处置" width="90" />
          <el-table-column prop="createTime" label="时间" width="160" />
        </el-table>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { reactive, ref, onMounted } from 'vue'
import { Search, Refresh, Edit, Check } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getProductionOrderPageAPI, getProductionOrderDetailAPI } from '@/api/business'
import { getQcSnapshotAPI, recordQcAPI, disposeQcAPI } from '@/api/business'

const statusOptions = [
  { value: 1, label: '待生产' },
  { value: 2, label: '生产中' },
  { value: 3, label: '待入库' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已作废' }
]
const searchForm = reactive({ orderNo: '', goodsName: '' })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const qcVisible = ref(false)
const detail = ref(null)
const qcState = ref(null)
const recordForm = reactive({ testPoint: 'first', result: 'OK', reason: '' })

const dispText = (d) => (d === 'REWORK' ? '返工' : d === 'SCRAP' ? '报废' : '')

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      orderNo: searchForm.orderNo || undefined,
      goodsName: searchForm.goodsName || undefined
    }
    // 质检范围：生产中 + 待入库
    const res = await getProductionOrderPageAPI(params)
    if (res.code !== 200) throw new Error(res.msg || '查询失败')
    const pageData = res.data || {}
    tableData.value = (pageData.records || []).filter((r) => r.status === 2 || r.status === 3)
    total.value = (pageData.total || 0)
  } catch (error) {
    ElMessage.error(error.message || '加载质检列表失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => { currentPage.value = 1; loadList() }
const resetSearch = () => { searchForm.orderNo = ''; searchForm.goodsName = ''; currentPage.value = 1; loadList() }
const handleSizeChange = (v) => { pageSize.value = v; currentPage.value = 1; loadList() }
const handleCurrentChange = () => loadList()

const openQc = async (row) => {
  try {
    const res = await getProductionOrderDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '详情查询失败')
    detail.value = res.data || {}
    qcState.value = detail.value.qcState || null
    // 记录里的处置显示为中文
    ;(qcState.value?.records || []).forEach((r) => { r.dispositionText = dispText(r.disposition) })
    recordForm.testPoint = 'first'
    recordForm.result = 'OK'
    recordForm.reason = ''
    qcVisible.value = true
  } catch (error) {
    ElMessage.error(error.message || '加载质检详情失败')
  }
}

const doRecord = async () => {
  if (!recordForm.testPoint) { ElMessage.warning('请选择测点'); return }
  if (recordForm.result === 'NG' && !recordForm.reason) { ElMessage.warning('NG 时必须填写不合格原因'); return }
  try {
    const res = await recordQcAPI({
      orderId: detail.value.id,
      testPoint: recordForm.testPoint,
      result: recordForm.result,
      reason: recordForm.reason || undefined
    })
    if (res.code !== 200) throw new Error(res.msg || '提交失败')
    ElMessage.success('测试结果已录入')
    await openQc(detail.value)
    await loadList()
  } catch (error) {
    ElMessage.error(error.message || '提交测试结果失败')
  }
}

const doDispose = async (testPoint, disposition) => {
  const label = testPoint === 'first' ? '首测' : '成品测'
  const dn = disposition === 'REWORK' ? '返工' : '报废'
  ElMessageBox.confirm(
    `确认对「${label}」的不合格记录执行「${dn}」？` + (disposition === 'SCRAP' ? '报废将终结该订单。' : '返工后需重测。'),
    '处置确认', { type: 'warning' }
  ).then(async () => {
    try {
      const res = await disposeQcAPI({ orderId: detail.value.id, testPoint, disposition })
      if (res.code !== 200) throw new Error(res.msg || '处置失败')
      ElMessage.success(`已${dn}`)
      await openQc(detail.value)
      await loadList()
    } catch (error) {
      ElMessage.error(error.message || '处置失败')
    }
  }).catch(() => {})
}

const statusTagType = (s) => (s === 1 ? 'info' : s === 2 ? 'warning' : s === 3 ? 'primary' : s === 4 ? 'success' : 'danger')
const pointTagType = (st) => (st === 'ok' ? 'success' : st === 'ng' ? 'danger' : st === 'rework' ? 'warning' : st === 'scrap' ? 'info' : 'info')

onMounted(loadList)
</script>

<style scoped>
.point-card {
  border: 1px solid #dcdfe6;
  border-radius: 6px;
  padding: 12px;
}
.point-title {
  font-weight: 600;
  margin-bottom: 8px;
}
</style>