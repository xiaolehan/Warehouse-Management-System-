<template>
  <div class="annual-stats-container">
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <div class="toolbar-title">
          <span class="title-text">年度经营统计</span>
          <span class="title-tip">采购额按入库确认时间归年 · 销售额按开单时间归年 · 退货按自身发生年冲减 · 已排除作废/红冲单</span>
        </div>
        <el-button type="primary" :icon="Download" :loading="exporting" @click="handleExport">导出</el-button>
      </div>
    </el-card>

    <el-card v-loading="loading" style="margin-bottom: 20px;">
      <div ref="barChartRef" style="height: 360px;"></div>
    </el-card>

    <el-card v-loading="loading">
      <el-table :data="statsList" border stripe empty-text="暂无年度数据">
        <el-table-column prop="year" label="年份" width="100" align="center" />
        <el-table-column label="采购总支出" align="right" min-width="140">
          <template #default="{ row }">{{ formatCurrency(row.purchaseAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售总营收" align="right" min-width="140">
          <template #default="{ row }">{{ formatCurrency(row.salesAmount) }}</template>
        </el-table-column>
        <el-table-column label="销售成本" align="right" min-width="140">
          <template #default="{ row }">{{ formatCurrency(row.salesCost) }}</template>
        </el-table-column>
        <el-table-column label="毛利" align="right" min-width="140">
          <template #default="{ row }">
            <span :class="Number(row.grossProfit) >= 0 ? 'amount-success' : 'amount-danger'">
              {{ formatCurrency(row.grossProfit) }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="毛利率" align="right" width="110">
          <template #default="{ row }">
            <span v-if="row.grossProfitRate === null || row.grossProfitRate === undefined">—</span>
            <span v-else :class="Number(row.grossProfitRate) >= 0 ? 'amount-success' : 'amount-danger'">
              {{ formatRate(row.grossProfitRate) }}
            </span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Download } from '@element-plus/icons-vue'
import { loadECharts } from '@/utils/echartsLoader'
import { saveBlobAs, localDateString } from '@/utils/download'
import { getAnnualStatsAPI, exportAnnualStatsAPI } from '@/api/business'

const loading = ref(false)
const exporting = ref(false)
const statsList = ref([])
const barChartRef = ref(null)

let barChart = null
let echarts = null

const formatCurrency = (value) => {
  const num = Number(value ?? 0)
  return num.toLocaleString('zh-CN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
}

const formatRate = (value) => `${Number(value).toFixed(1)}%`

const fetchData = async () => {
  loading.value = true
  try {
    const res = await getAnnualStatsAPI()
    statsList.value = res.data || []
    await nextTick()
    await initChart()
    updateChart()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const initChart = async () => {
  if (!echarts) {
    echarts = await loadECharts()
  }
  if (!barChart && barChartRef.value) {
    barChart = echarts.init(barChartRef.value)
    window.addEventListener('resize', handleResize)
  }
}

const updateChart = () => {
  if (!barChart) return
  // 图表按年份正序（趋势从左到右），表格保持倒序
  const ascending = [...statsList.value].sort((a, b) => a.year - b.year)
  const years = ascending.map((row) => `${row.year}年`)
  const seriesData = (key) => ascending.map((row) => Number(row[key] ?? 0))

  barChart.setOption({
    title: { text: '年度采购 / 销售 / 毛利', left: 'center' },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0 },
    grid: { left: '3%', right: '4%', bottom: '12%', containLabel: true },
    xAxis: { type: 'category', data: years.length ? years : ['暂无数据'] },
    yAxis: { type: 'value', name: '单位：元' },
    series: [
      { name: '采购总支出', type: 'bar', data: seriesData('purchaseAmount'), itemStyle: { color: '#E6A23C' } },
      { name: '销售总营收', type: 'bar', data: seriesData('salesAmount'), itemStyle: { color: '#409EFF' } },
      { name: '毛利', type: 'bar', data: seriesData('grossProfit'), itemStyle: { color: '#67C23A' } }
    ]
  }, true)
}

const handleResize = () => {
  barChart?.resize()
}

const handleExport = async () => {
  exporting.value = true
  try {
    const blob = await exportAnnualStatsAPI()
    await saveBlobAs(blob, `年度经营统计-${localDateString()}.xlsx`)
    ElMessage.success('导出成功')
  } catch (error) {
    // saveBlobAs 探测出的业务错误（JSON 错误体）在此提示；传输错误已由拦截器统一提示
    if (!error?.isAxiosError) ElMessage.error(error.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

onMounted(fetchData)

onBeforeUnmount(() => {
  window.removeEventListener('resize', handleResize)
  barChart?.dispose()
  barChart = null
})
</script>

<style scoped>
.annual-stats-container {
  padding: 4px;
}

.toolbar-card {
  margin-bottom: 20px;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.toolbar-title {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.title-text {
  font-size: 16px;
  font-weight: 600;
}

.title-tip {
  font-size: 12px;
  color: #909399;
}

.amount-success {
  color: #67c23a;
  font-weight: 600;
}

.amount-danger {
  color: #f56c6c;
  font-weight: 600;
}
</style>
