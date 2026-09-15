<template>
  <div class="sales-timeline" v-loading="loading">
    <div class="timeline-header">
      <span class="timeline-title">履约进度</span>
      <!-- D71：预计可交付为系统最佳估计，非对客承诺；手工修正(生产确认)优先 -->
      <el-tag v-if="timeline && timeline.estimatedDeliveryText" :type="estimateTagType" size="small">
        {{ timeline.estimatedDeliveryText }}
      </el-tag>
    </div>
    <el-timeline v-if="timeline && timeline.nodes && timeline.nodes.length" class="timeline-body">
      <el-timeline-item
        v-for="node in timeline.nodes"
        :key="node.key"
        :type="nodeType(node.status)"
        :hollow="node.status === 'current'"
        :timestamp="formatTime(node.time)"
        placement="top"
      >
        <div class="node-line">
          <span :class="['node-title', `node-title--${node.status}`]">{{ node.title }}</span>
          <span v-if="node.description" class="node-desc">{{ node.description }}</span>
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else-if="!loading" description="暂无履约进度" :image-size="60" />
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { getSalesTimelineAPI } from '@/api/business'

// D71：销售单履约时间线（类淘宝物流），数据源 GET /business/sales/{id}/timeline
const props = defineProps({
  salesId: { type: [Number, String], default: null }
})

const loading = ref(false)
const timeline = ref(null)

const estimateTagType = computed(() => {
  const source = timeline.value?.estimatedSource
  if (source === 'manual') return 'success'   // 生产手工确认
  if (source === 'system') return 'warning'   // 系统推算
  return 'info'
})

const nodeType = (status) => {
  if (status === 'done') return 'success'
  if (status === 'current') return 'primary'
  return 'info'
}

const formatTime = (val) => {
  if (!val) return ''
  return String(val).replace('T', ' ').slice(0, 16)
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

.timeline-body {
  padding-left: 4px;
}

.node-line {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.node-title {
  font-size: 13px;
}

.node-title--done {
  color: #67c23a;
}

.node-title--current {
  color: #409eff;
  font-weight: 600;
}

.node-title--pending {
  color: #909399;
}

.node-desc {
  font-size: 12px;
  color: #909399;
}
</style>
