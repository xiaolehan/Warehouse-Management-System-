import request from '@/utils/request'

// 登录日志查询
export const getLoginLogPageAPI = (params) => request.get('/system/audit/login-logs/page', { params })
export const getLoginLogDetailAPI = (id) => request.get(`/system/audit/login-logs/${id}`)

// 操作日志查询
export const getOperationLogPageAPI = (params) => request.get('/system/audit/operation-logs/page', { params })
export const getOperationLogDetailAPI = (id) => request.get(`/system/audit/operation-logs/${id}`)

// D78：操作日志删除（仅超管，物理删除，删除动作留痕）
export const deleteOperationLogAPI = (id) => request.delete(`/system/audit/operation-logs/${id}`)
export const deleteOperationLogsAPI = (ids) => request.post('/system/audit/operation-logs/batch-delete', ids)
export const deleteOperationLogsByQueryAPI = (data) => request.post('/system/audit/operation-logs/delete-by-query', data)

// D78：登录日志删除（仅超管）
export const deleteLoginLogAPI = (id) => request.delete(`/system/audit/login-logs/${id}`)
export const deleteLoginLogsAPI = (ids) => request.post('/system/audit/login-logs/batch-delete', ids)
export const deleteLoginLogsByQueryAPI = (data) => request.post('/system/audit/login-logs/delete-by-query', data)

// D79：操作日志 xlsx 导出（跟随筛选条件；导出行数多，放宽超时）
export const exportOperationLogsAPI = (params) =>
  request.get('/system/audit/operation-logs/export', { params, responseType: 'blob', timeout: 60000 })
