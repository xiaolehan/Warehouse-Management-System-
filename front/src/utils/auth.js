const TOKEN_KEY = "token"
const ROLE_KEY = "role"
const DEPT_ID_KEY = "deptId"
const DEPT_CODE_KEY = "deptCode"
const DEPT_NAME_KEY = "deptName"
const USERNAME_KEY = "username"
const REAL_NAME_KEY = "realName"
const USER_ID_KEY = "userId"

export const normalizeRole = (role) => String(role || "").trim().toLowerCase()

export const normalizeDeptCode = (deptCode) => String(deptCode || "").trim().toLowerCase()

const setOptionalStorage = (key, value) => {
  if (value === undefined || value === null || value === "") {
    localStorage.removeItem(key)
    return
  }
  localStorage.setItem(key, String(value))
}

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ""

export const getRole = () => normalizeRole(localStorage.getItem(ROLE_KEY))

export const getDeptId = () => {
  const value = localStorage.getItem(DEPT_ID_KEY)
  if (value === null || value === "") {
    return null
  }
  const parsed = Number(value)
  return Number.isNaN(parsed) ? null : parsed
}

export const getDeptCode = () => normalizeDeptCode(localStorage.getItem(DEPT_CODE_KEY))

export const getDeptName = () => localStorage.getItem(DEPT_NAME_KEY) || ""

// 当前登录用户 id（D85 盘点行归属判断等场景）
export const getUserId = () => {
  const raw = localStorage.getItem(USER_ID_KEY)
  const n = Number(raw)
  return raw && Number.isFinite(n) ? n : null
}

export const getAuthContext = () => ({
  role: getRole(),
  deptId: getDeptId(),
  deptCode: getDeptCode(),
  deptName: getDeptName(),
  username: localStorage.getItem(USERNAME_KEY) || "",
  realName: localStorage.getItem(REAL_NAME_KEY) || ""
})

export const setToken = (token) => {
  localStorage.setItem(TOKEN_KEY, token || "")
}

export const setRole = (role) => {
  localStorage.setItem(ROLE_KEY, normalizeRole(role))
}

export const setUserInfo = (userInfo = {}) => {
  setRole(userInfo.role)
  setOptionalStorage(USER_ID_KEY, userInfo.id)
  setOptionalStorage(USERNAME_KEY, userInfo.username)
  setOptionalStorage(REAL_NAME_KEY, userInfo.realName)
  setOptionalStorage(DEPT_ID_KEY, userInfo.deptId)
  setOptionalStorage(DEPT_CODE_KEY, normalizeDeptCode(userInfo.deptCode))
  setOptionalStorage(DEPT_NAME_KEY, userInfo.deptName)
}

export const clearAuth = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(ROLE_KEY)
  localStorage.removeItem(DEPT_ID_KEY)
  localStorage.removeItem(DEPT_CODE_KEY)
  localStorage.removeItem(DEPT_NAME_KEY)
  localStorage.removeItem(USERNAME_KEY)
  localStorage.removeItem(REAL_NAME_KEY)
  localStorage.removeItem(USER_ID_KEY)
}

export const hasRole = (currentRole, allowRoles = []) => {
  if (!currentRole || !Array.isArray(allowRoles)) {
    return false
  }

  const normalizedRole = normalizeRole(currentRole)
  const normalizedAllowRoles = allowRoles.map((item) => normalizeRole(item))

  return normalizedAllowRoles.includes(normalizedRole)
    || (normalizedRole === "superadmin" && normalizedAllowRoles.includes("admin"))
}

export const hasDeptAccess = (currentDeptCode, allowDeptCodes = [], currentRole = getRole()) => {
  if (!Array.isArray(allowDeptCodes) || allowDeptCodes.length === 0) {
    return true
  }
  if (isSuperAdmin(currentRole)) {
    return true
  }
  const normalizedDeptCode = normalizeDeptCode(currentDeptCode)
  const normalizedAllowDeptCodes = allowDeptCodes.map((item) => normalizeDeptCode(item))
  return normalizedAllowDeptCodes.includes(normalizedDeptCode)
}

export const isSuperAdmin = (role) => normalizeRole(role) === "superadmin"

export const isAdminRole = (role) => normalizeRole(role) === "admin"

export const isEmployeeRole = (role) => normalizeRole(role) === "employee"

export const isDeptAdmin = (role, deptCode) => isAdminRole(role) && Boolean(normalizeDeptCode(deptCode))

export const isDeptEmployee = (role, deptCode) => isEmployeeRole(role) && Boolean(normalizeDeptCode(deptCode))

export const canAccessRoles = (currentRole, allowRoles = []) => hasRole(currentRole, allowRoles)

// 路由 meta 鉴权组合判定（roles + deptCodes）：守卫按 reason 出文案，组件侧用 canAccessRouteMeta 预判可达性
export const checkRouteAccess = (meta = {}, currentRole = getRole(), currentDeptCode = getDeptCode()) => {
  if (meta.roles && !canAccessRoles(currentRole, meta.roles)) return { ok: false, reason: 'role' }
  if (Array.isArray(meta.deptCodes) && meta.deptCodes.length > 0 && !hasDeptAccess(currentDeptCode, meta.deptCodes, currentRole)) return { ok: false, reason: 'dept' }
  return { ok: true, reason: null }
}

export const canAccessRouteMeta = (meta = {}, currentRole = getRole(), currentDeptCode = getDeptCode()) =>
  checkRouteAccess(meta, currentRole, currentDeptCode).ok
