import axios from "axios"
import { ElMessage } from "element-plus"
import { clearAuth, getToken } from "@/utils/auth"

const request = axios.create({
  baseURL: "/api",
  timeout: 5000
})

request.interceptors.request.use(config => {
  const token = getToken()
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
}, error => Promise.reject(error))

// ADR-0012 业务错误提示单通道：后端业务错误封装为 HTTP 200 + body.code≠200，
// 统一在此提示并 reject。页面一律不再手写 API 错误提示（写了会双重提示），
// 也不再需要手写 res.code 检查——业务错误不会流入页面成功路径。
// 豁免口：单个请求可传 { silent: true } 跳过提示仍 reject（仅限轮询/批量降级等有意静默场景，
// 当前两处：MessageCenter 角标 15s 轮询、工作需求附件批量加载按个降级）。
request.interceptors.response.use(
  response => {
    const data = response.data
    // 导出类等二进制响应无 Result 封装，直接透传
    if (data instanceof Blob || response.config.responseType === "blob" || response.config.responseType === "arraybuffer") {
      return data
    }
    if (data && typeof data.code === "number" && data.code !== 200) {
      const msg = data.msg || "操作失败"
      if (!response.config.silent) {
        ElMessage.error(msg)
      }
      return Promise.reject(new Error(msg))
    }
    return data
  },
  error => {
    const status = error?.response?.status
    if (status === 401) {
      clearAuth()
      if (window.location.pathname !== "/login") {
        window.location.href = "/login"
      }
    } else if (status === 403) {
      ElMessage.error("无权限访问该页面")
      if (window.location.pathname !== "/403") {
        window.location.href = "/403"
      }
    } else if (!error.config?.silent) {
      ElMessage.error("网络异常，请稍后重试")
    }
    return Promise.reject(error)
  }
)

export default request
