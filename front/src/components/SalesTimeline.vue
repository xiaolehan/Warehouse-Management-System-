<template>
  <div class="sales-timeline" v-loading="loading">
    <div class="timeline-header">
      <!-- D110 决策⑦：履约时间线按明细行逐行展示（方案 A） -->
      <span class="timeline-title">履约进度（按明细行）</span>
    </div>
    <template v-if="timeline && lines.length">
      <div v-for="line in lines" :key="line.salesDetailId" class="timeline-line">
        <div class="line-header">
          <span class="line-goods">{{ line.goodsName }} × {{ line.quantity }}</span>
          <span v-if="line.stock != null && !lineTerminated(line)" class="line-stock" :class="{ 'line-stock-short': line.quantity > line.stock }">
            现存 {{ line.stock }}
          </span>
          <!-- 需求一 Q22：行已终止（时间线只有 下单+行终止 两节点） -->
          <el-tag v-if="lineTerminated(line)" type="info" size="small">已终止</el-tag>
          <!-- D71：预计可交付为系统最佳估计，非对客承诺；手工修正(生产确认)优先 -->
          <el-tag :type="estimateTagType(line)" size="small">{{ line.estimatedDeliveryText || '—' }}</el-tag>
        </div>
        <DocumentTimeline
          v-if="line.nodes && line.nodes.length"
          :nodes="line.nodes"
          empty-text="暂无履约进度"
        />
        <el-empty v-else description="暂无履约进度" :image-size="60" />
        <!-- ADR-0020/D116：行终止后已入库未出库成品的处置（保留成品 / 发起拆分） -->
        <div v-if="lineTerminated(line) && hasDisposition(line)" class="split-disposition">
          <div class="split-row">
            <el-tag v-if="splitStatusTag(line)" :type="splitStatusTag(line).type" size="small">{{ splitStatusTag(line).text }}</el-tag>
            <span v-if="line.splitOrderId" class="split-no">
              拆分单：<el-link type="primary" :underline="false" @click="$router.push('/business/split-order')">{{ line.splitOrderNo || ('#' + line.splitOrderId) }}</el-link>
            </span>
            <span v-if="line.splitStatus === 2" class="split-no">保留人：{{ line.splitKeepName || '—' }}（{{ fmtTime(line.splitKeepTime) }}）</span>
          </div>
          <div v-if="line.canHandleSplit" class="split-actions">
            <span class="split-hint">该行成品已入库 {{ line.unshippedInboundQty }} 件未出库，请处置：</span>
            <el-button type="success" size="small" @click="onKeep(line)">保留成品</el-button>
            <el-button type="primary" size="small" @click="openSplitDialog(line)">发起拆分</el-button>
          </div>
        </div>
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无履约进度" :image-size="60" />
    <!-- 发起拆分：数量确认（默认=已入库未出库量，可部分拆分） -->
    <el-dialog v-model="splitDialogVisible" title="发起成品拆分" width="460px" :close-on-click-modal="false">
      <el-alert
        :title="`对该行成品发起拆分：仓库确认成品出库（库存减少），生产领取任务、领到成品后开始拆分，拆分物料经退料单由仓库确认回流入库。`"
        type="info" :closable="false" style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="拆分数量">
          <el-input-number v-model="splitQty" :min="1" :max="splitLine ? splitLine.unshippedInboundQty : 1" controls-position="right" style="width: 160px" />
          <span class="split-hint" style="margin-left: 8px">上限 {{ splitLine ? splitLine.unshippedInboundQty : 0 }} 件，可部分拆分</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="splitDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="splitSubmitting" @click="doCreateSplit">确认发起</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getSalesTimelineAPI } from '@/api/business'
import { keepProductAPI, createSplitOrderAPI } from '@/api/splitOrder'
import DocumentTimeline from '@/components/DocumentTimeline.vue'

// D71/D110：销售单履约时间线（类淘宝物流，按明细行），数据源 GET /business/sales/{id}/timeline
const props = defineProps({
  salesId: { type: [Number, String], default: null }
})

const loading = ref(false)
const timeline = ref(null)

const lines = computed(() => timeline.value?.lines || [])

const estimateTagType = (line) => {
  const source = line?.estimatedSource
  if (source === 'manual') return 'success'   // 生产手工确认
  if (source === 'system') return 'warning'   // 系统推算
  return 'info'
}

// 需求一 Q22：行已终止（后端该行只有 下单+行终止 两节点，交付文本=「已终止」）
const lineTerminated = (line) => (line?.nodes || []).some((n) => n?.key === 'terminated')

// ==================== ADR-0020/D116：行终止成品处置 ====================

// 有任何处置信息可展示（按钮仅仓储管理员/超管可见——canHandleSplit 由后端按角色门控）
const hasDisposition = (line) =>
  !!line.canHandleSplit || line.splitStatus != null || line.splitOrderId != null

const splitStatusTag = (line) => {
  const s = line?.splitStatus
  if (s === 1) return { text: '待处置', type: 'warning' }
  if (s === 2) return { text: '已保留成品', type: 'success' }
  if (s === 3) return { text: '已发起拆分', type: 'warning' }
  if (s === 4) return { text: '拆分完成', type: 'success' }
  return null
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

const splitDialogVisible = ref(false)
const splitLine = ref(null)
const splitQty = ref(1)
const splitSubmitting = ref(false)

const onKeep = async (line) => {
  try {
    await ElMessageBox.confirm(
      `确认保留该行成品 ${line.unshippedInboundQty} 件？保留后成品留在库存，不再拆分退料。`,
      '保留成品',
      { type: 'warning' }
    )
  } catch (e) {
    if (e === 'cancel') return
  }
  try {
    await keepProductAPI(line.salesDetailId)
    ElMessage.success('已保留成品，库存不变')
    await load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const openSplitDialog = (line) => {
  splitLine.value = line
  splitQty.value = line.unshippedInboundQty || 1
  splitDialogVisible.value = true
}

const doCreateSplit = async () => {
  if (!splitLine.value) return
  splitSubmitting.value = true
  try {
    await createSplitOrderAPI({
      salesDetailId: splitLine.value.salesDetailId,
      quantity: splitQty.value
    })
    ElMessage.success('拆分单已创建，待生产领取')
    splitDialogVisible.value = false
    await load()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    splitSubmitting.value = false
  }
}

const load = async () => {
  if (!props.salesId) {
    timeline.value = null
    return
  }
  loading.value = true
  try {
    const res = await getSalesTimelineAPI(props.salesId)
    timeline.value = res.data || null
  } catch {
    timeline.value = null
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

watch(() => props.salesId, load, { immediate: true })
</script>

<style scoped>
.sales-timeline {
  margin-top: 8px;
  border-top: 1px dashed #e4e7ed;
  padding-top: 12px;
}

.timeline-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.timeline-title {
  font-weight: 600;
  color: #303133;
}

.timeline-line {
  margin-bottom: 16px;
}

.timeline-line:last-child {
  margin-bottom: 0;
}

.line-header {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 8px;
}

.line-goods {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
}

.line-stock {
  font-size: 12px;
  color: #909399;
}

.line-stock-short {
  color: #f56c6c;
  font-weight: 600;
}

.split-disposition {
  margin-top: 10px;
  padding: 8px 12px;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
}

.split-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.split-actions {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.split-hint {
  font-size: 12px;
  color: #909399;
}

.split-no {
  font-size: 12px;
  color: #606266;
}
</style>
