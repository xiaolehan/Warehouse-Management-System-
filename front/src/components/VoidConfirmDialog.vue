<template>
  <!-- D94：作废说明弹窗——把「会发生什么」写在点下按钮之前，替代原只问原因的 prompt -->
  <el-dialog
    :model-value="modelValue"
    title="作废单据（纠错留痕）"
    width="540px"
    :close-on-click-modal="false"
    @update:model-value="$emit('update:modelValue', $event)"
    @closed="reset"
  >
    <div class="void-confirm">
      <p class="void-confirm__lead">
        作废 = 此单<b>当作没发生过</b>，仅用于纠正录错的单据；若业务真实发生（货真的退了/卖了），请走对应业务单。
      </p>
      <ul class="void-confirm__list">
        <li>
          <b>库存影响：</b>
          <template v-if="stockEffect">
            {{ stockEffect.goodsName }} × {{ stockEffect.quantity }} 将
            <template v-if="stockEffect.mode === 'deduct'">扣回库存（-{{ stockEffect.quantity }}）；若{{ direct ? '当前' : '审批通过时' }}库存不足，作废将失败</template>
            <template v-else>回补库存（+{{ stockEffect.quantity }}）</template>
          </template>
          <template v-else>不涉及库存变动</template>
        </li>
        <li>
          <b>生效流程：</b>{{ direct ? '确认后立即生效。' : '提交后进入作废审批，仓储管理员通过后才生效；被驳回则单据维持原样。' }}
        </li>
        <li><b>留痕：</b>单据不会消失，将标记「已作废」并保留原因，列表随时可查。</li>
      </ul>
      <el-input
        v-model="reason"
        type="textarea"
        :rows="2"
        :placeholder="reasonRequired ? '请填写作废原因（必填，审批人据此判断）' : '作废原因（选填，默认：手工作废）'"
        maxlength="200"
        show-word-limit
      />
    </div>
    <template #footer>
      <el-button @click="$emit('update:modelValue', false)">取消</el-button>
      <el-button type="danger" :loading="submitting" @click="handleConfirm">{{ direct ? '确认作废' : '确认提交审批' }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'

const props = defineProps({
  modelValue: { type: Boolean, default: false },
  // 库存后果：{ goodsName, quantity, mode: 'deduct'(扣回) | 'return'(回补) }；null = 不涉及库存变动
  stockEffect: { type: Object, default: null },
  // true = 直接作废立即生效（生产入库/生产任务单）；false = 提交作废审批，仓储 admin 通过后生效
  direct: { type: Boolean, default: false },
  // D94：审批流四类单原因必填（审批人据此判断）；直接作废两类维持选填
  reasonRequired: { type: Boolean, default: false },
  submitting: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'confirm'])

const reason = ref('')

const handleConfirm = () => {
  const value = reason.value.trim()
  if (props.reasonRequired && !value) {
    ElMessage.warning('请填写作废原因')
    return
  }
  emit('confirm', value)
}

const reset = () => {
  reason.value = ''
}
</script>

<style scoped>
.void-confirm__lead {
  margin: 0 0 10px;
  color: #475569;
  line-height: 1.6;
}

.void-confirm__list {
  margin: 0 0 14px;
  padding-left: 18px;
  color: #475569;
  line-height: 1.8;
}
</style>
