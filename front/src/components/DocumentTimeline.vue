<template>
  <div class="doc-timeline">
    <el-timeline v-if="nodes && nodes.length" class="timeline-body">
      <el-timeline-item
        v-for="node in nodes"
        :key="node.key"
        :type="nodeType(node)"
        :hollow="node.status === 'current'"
        :timestamp="formatTime(node.time)"
        placement="top"
      >
        <div class="node-line">
          <span :class="['node-title', titleClass(node)]">{{ node.title }}</span>
          <span v-if="node.description" class="node-desc">{{ node.description }}</span>
        </div>
      </el-timeline-item>
    </el-timeline>
    <el-empty v-else :description="emptyText" :image-size="60" />
  </div>
</template>

<script setup>
// D104：单据流程时间线通用渲染组件（「谁在哪一步做了什么」）——
// 纯展示，节点数据由各业务视图调各自 API 后传入；已作废节点红色、作废审批中蓝色空心。
defineProps({
  nodes: { type: Array, default: () => [] },
  emptyText: { type: String, default: '暂无流程记录' }
})

const nodeType = (node) => {
  if (node.key === 'voided') return 'danger'
  if (node.status === 'done') return 'success'
  if (node.status === 'current') return 'primary'
  return 'info'
}

const titleClass = (node) => {
  if (node.key === 'voided') return 'node-title--voided'
  return `node-title--${node.status}`
}

const formatTime = (val) => {
  if (!val) return ''
  return String(val).replace('T', ' ').slice(0, 16)
}
</script>

<style scoped>
.doc-timeline {
  margin-top: 8px;
  border-top: 1px dashed #e4e7ed;
  padding-top: 12px;
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

.node-title--voided {
  color: #f56c6c;
  font-weight: 600;
}

.node-desc {
  font-size: 12px;
  color: #909399;
}
</style>
