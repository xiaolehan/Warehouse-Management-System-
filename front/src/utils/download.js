// 导出下载共享助手：后端异常按项目约定返回 HTTP 200 + JSON 错误体（GlobalExceptionHandler），
// responseType:'blob' 时该 JSON 也会被包成 Blob，直接保存会得到损坏文件——先探测再落盘。
// 注意：blob 通道的响应不会经过 request.js 拦截器（成功分支直接透传 Blob），
// 因此会话失效（code=401）也在这里处理，与拦截器的 clearAuth+跳登录行为对齐（D122）。
import { clearAuth } from '@/utils/auth'

// 与 request.js 错误分支同口径的会话失效处理：清凭证并跳登录页
const handleExpiredSession = () => {
  clearAuth()
  if (window.location.pathname !== '/login') {
    window.location.href = '/login'
  }
}

/**
 * 保存后端导出接口返回的 blob 为文件。
 * 若 blob 实为 JSON 错误体（鉴权失败/后端异常），解析 msg 并抛错，不保存；
 * 其中 401（会话失效，后端封为 HTTP 200+JSON）额外清凭证并跳登录，避免「点了没反应」式假死。
 */
export const saveBlobAs = async (blob, filename) => {
  if (blob?.type?.includes('json')) {
    let message = '导出失败'
    let code = null
    try {
      const body = JSON.parse(await blob.text())
      message = body?.msg || message
      code = body?.code ?? null
    } catch {
      // 解析失败沿用默认提示
    }
    if (code === 401) {
      handleExpiredSession()
    }
    throw new Error(message)
  }
  const url = window.URL.createObjectURL(new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/** 本地时区日期（YYYY-MM-DD），与后端 Content-Disposition 的 LocalDate.now() 口径一致 */
export const localDateString = () => {
  const d = new Date()
  const month = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${month}-${day}`
}
