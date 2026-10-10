// 密码规则（与后端 PasswordPolicyUtil 保持一致）：8–20 位，须同时包含字母和数字
export const PASSWORD_RULE_MESSAGE = '密码长度为8–20位，须同时包含字母和数字'

export const PASSWORD_SPACE_MESSAGE = '密码首尾不能包含空格'

export const hasEdgeWhitespace = (value) => typeof value === 'string' && value !== value.trim()

export const isPasswordValid = (value) => {
  if (!value) return false
  if (hasEdgeWhitespace(value)) return false
  if (value.length < 8 || value.length > 20) return false
  if (!/[A-Za-z]/.test(value)) return false
  if (!/\d/.test(value)) return false
  return true
}

// Element Plus 表单校验器：必填 + 密码规则（含首尾空格拒绝）
export const validatePassword = (_rule, value, callback) => {
  if (!value) {
    callback(new Error('密码不能为空'))
    return
  }
  if (hasEdgeWhitespace(value)) {
    callback(new Error(PASSWORD_SPACE_MESSAGE))
    return
  }
  if (!isPasswordValid(value)) {
    callback(new Error(PASSWORD_RULE_MESSAGE))
    return
  }
  callback()
}

// 初始密码校验器：选填，填写时才校验密码规则
export const validateOptionalPassword = (_rule, value, callback) => {
  if (value && hasEdgeWhitespace(value)) {
    callback(new Error(PASSWORD_SPACE_MESSAGE))
    return
  }
  if (value && !isPasswordValid(value)) {
    callback(new Error(PASSWORD_RULE_MESSAGE))
    return
  }
  callback()
}

// 确认密码校验器工厂：与指定来源字段保持一致
export const createConfirmPasswordValidator = (getExpected, label = '新密码') => (_rule, value, callback) => {
  if (!value) {
    callback(new Error(`请再次输入${label}`))
    return
  }
  if (value !== getExpected()) {
    callback(new Error('两次输入的密码不一致'))
    return
  }
  callback()
}
