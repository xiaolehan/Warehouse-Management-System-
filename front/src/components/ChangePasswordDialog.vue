<template>
  <el-dialog title="修改密码" :model-value="modelValue" width="440px" @update:model-value="handleVisibleChange" @closed="handleClosed">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="84px" @submit.prevent>
      <el-form-item label="旧密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入旧密码" autocomplete="off" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="8–20位，含字母和数字" autocomplete="new-password" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirmPassword">
        <el-input v-model="form.confirmPassword" type="password" show-password placeholder="请再次输入新密码" autocomplete="new-password" @keyup.enter="handleSubmit" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="handleVisibleChange(false)">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="handleSubmit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { changePasswordAPI } from '@/api/user'
import { validatePassword, createConfirmPasswordValidator } from '@/utils/password'

const props = defineProps({
  modelValue: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue', 'success'])

const formRef = ref(null)
const submitting = ref(false)
const form = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const validateNewDifferent = (_rule, value, callback) => {
  if (value && value === form.oldPassword) {
    callback(new Error('新密码不能与旧密码相同'))
    return
  }
  callback()
}

const rules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [
    { validator: validatePassword, trigger: 'blur' },
    { validator: validateNewDifferent, trigger: 'blur' }
  ],
  confirmPassword: [{ validator: createConfirmPasswordValidator(() => form.newPassword), trigger: 'blur' }]
}

const resetForm = () => {
  form.oldPassword = ''
  form.newPassword = ''
  form.confirmPassword = ''
  formRef.value?.clearValidate()
}

const handleVisibleChange = (visible) => {
  emit('update:modelValue', visible)
}

const handleClosed = () => {
  resetForm()
}

watch(() => props.modelValue, (visible) => {
  if (visible) {
    resetForm()
  }
})

const handleSubmit = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid || submitting.value) return
    submitting.value = true
    try {
      await changePasswordAPI({ oldPassword: form.oldPassword, newPassword: form.newPassword })
      ElMessage.success('密码修改成功，请使用新密码重新登录')
      emit('update:modelValue', false)
      emit('success')
    } catch {
      // 业务错误（如旧密码不正确）已由拦截器统一提示
    } finally {
      submitting.value = false
    }
  })
}
</script>
