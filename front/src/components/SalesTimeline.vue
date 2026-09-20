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
          <span v-if="line.stock != null" class="line-stock" :class="{ 'line-stock-short': line.quantity > line.stock }">
            现存 {{ line.stock }}
          </span>
          <!-- D71：预计可交付为系统最佳估计，非对客承诺；手工修正(生产确认)优先 -->
          <el-tag :type="estimateTagType(line)" size="small">{{ line.estimatedDeliveryText || '—' }}</el-tag>
        </div>
        <DocumentTimeline
          v-if="line.nodes && line.nodes.length"
          :nodes="line.nodes"
          empty-text="暂无履约进度"
        />
        <el-empty v-else description="暂无履约进度" :image-size="60" />
      </div>
    </template>
    <el-empty v-else-if="!loading" description="暂无履约进度" :image-size="60" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { getSalesTimelineAPI } from '@/api/business'
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
</style>
