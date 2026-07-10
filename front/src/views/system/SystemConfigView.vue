<template>
  <div class="config-page">
    <el-card class="panel" shadow="never">
      <template #header>
        <div class="card-head">
          <div>
            <p class="kicker">SYSTEM CONFIG</p>
            <h2>系统参数</h2>
          </div>
        </div>
      </template>

      <el-table :data="tableData" v-loading="loading" border empty-text="暂无参数">
        <el-table-column prop="configName" label="参数名称" min-width="160" />
        <el-table-column prop="configKey" label="参数键" min-width="220" />
        <el-table-column prop="configValue" label="当前值" width="140" align="center" />
        <el-table-column prop="remark" label="说明" min-width="260" show-overflow-tooltip />
        <el-table-column label="操作" width="120" align="center">
          <template #default="scope">
            <el-button
              v-if="scope.row.configKey === 'price_deviation_threshold'"
              size="small"
              type="primary"
              link
              :icon="Edit"
              @click="openEdit(scope.row)"
            >
              修改
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-dialog v-model="editVisible" title="修改价格偏离阈值" width="440px">
        <el-form label-width="120px">
          <el-form-item label="当前阈值">
            <span class="muted-text">{{ editRow?.configValue }} ({{ currentPctText }})</span>
          </el-form-item>
          <el-form-item label="新阈值（%）">
            <el-input-number
              v-model="editPct"
              :min="1"
              :max="99"
              :step="1"
              :precision="0"
              controls-position="right"
              style="width: 200px"
            />
            <div class="tip-text">
              销售单价偏离标准售价超过此比例时需超管审批。<br />
              例如填 10，表示偏离超过 10% 触发审批。
            </div>
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="editVisible = false">取消</el-button>
          <el-button type="primary" :loading="saving" @click="handleSave">保存</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Edit } from '@element-plus/icons-vue'
import {
  getSystemConfigListAPI,
  updatePriceDeviationThresholdAPI
} from '@/api/config'

const tableData = ref([])
const loading = ref(false)

const editVisible = ref(false)
const editRow = ref(null)
const editPct = ref(5)
const saving = ref(false)

const currentPctText = computed(() => {
  const v = Number(editRow.value?.configValue)
  return Number.isFinite(v) ? `${Math.round(v * 100)}%` : '-'
})

const loadList = async () => {
  loading.value = true
  try {
    const res = await getSystemConfigListAPI()
    if (res.code !== 200) {
      throw new Error(res.msg || '加载参数失败')
    }
    tableData.value = res.data || []
  } catch (error) {
    ElMessage.error(error.message || '加载参数失败')
  } finally {
    loading.value = false
  }
}

const openEdit = (row) => {
  editRow.value = row
  const v = Number(row.configValue)
  editPct.value = Number.isFinite(v) ? Math.round(v * 100) : 5
  editVisible.value = true
}

const handleSave = async () => {
  const value = Number(editPct.value) / 100
  if (!Number.isFinite(value) || value <= 0 || value >= 1) {
    ElMessage.error('阈值需在 1~99% 之间')
    return
  }
  saving.value = true
  try {
    const res = await updatePriceDeviationThresholdAPI({ value })
    if (res.code !== 200) {
      throw new Error(res.msg || '保存失败')
    }
    ElMessage.success('阈值已更新')
    editVisible.value = false
    await loadList()
  } catch (error) {
    ElMessage.error(error.message || '保存失败')
  } finally {
    saving.value = false
  }
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.config-page {
  padding: 16px;
}
.panel {
  border-radius: 8px;
}
.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.kicker {
  margin: 0;
  font-size: 12px;
  letter-spacing: 1px;
  color: #909399;
}
.card-head h2 {
  margin: 4px 0 0 0;
  font-size: 18px;
}
.muted-text {
  color: #909399;
}
.tip-text {
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
  line-height: 1.6;
}
</style>
