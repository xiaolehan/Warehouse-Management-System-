import { defineStore } from "pinia"
import { ref } from "vue"
import {
  clearAuth,
  getAuthContext,
  getDeptCode,
  getDeptId,
  getDeptName,
  getRole,
  getToken,
  getUserId,
  setToken as persistToken,
  setUserInfo as persistUserInfo
} from "@/utils/auth"

export const useUserStore = defineStore("user", () => {
  const initialContext = getAuthContext()
  const token = ref(getToken())
  const role = ref(getRole())
  const deptId = ref(getDeptId())
  const deptCode = ref(getDeptCode())
  const deptName = ref(getDeptName())
  const username = ref(initialContext.username)
  const realName = ref(initialContext.realName)
  const userId = ref(getUserId())

  const setToken = (newToken) => {
    token.value = newToken
    persistToken(newToken)
  }

  const setUserInfo = (userInfo = {}) => {
    persistUserInfo(userInfo)
    role.value = getRole()
    deptId.value = getDeptId()
    deptCode.value = getDeptCode()
    deptName.value = getDeptName()
    username.value = userInfo.username || ""
    realName.value = userInfo.realName || ""
    userId.value = getUserId()
  }

  const clearToken = () => {
    token.value = ""
    role.value = ""
    deptId.value = null
    deptCode.value = ""
    deptName.value = ""
    username.value = ""
    realName.value = ""
    userId.value = null
    clearAuth()
  }

  return { token, role, deptId, deptCode, deptName, username, realName, userId, setToken, setUserInfo, clearToken }
})
