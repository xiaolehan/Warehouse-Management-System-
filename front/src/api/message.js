import request from '@/utils/request'

// config 可传 { silent: true } 豁免全局错误提示（ADR-0012，仅轮询等有意静默场景用）
export const getMessagePageAPI = (params, config) => request.get('/system/messages/page', { params, ...config })

export const getUnreadMessageCountAPI = () => request.get('/system/messages/unread-count')

export const markMessageReadAPI = (id) => request.put(`/system/messages/${id}/read`)

export const markAllMessagesReadAPI = () => request.put('/system/messages/read-all')

export const deleteAllReadMessagesAPI = () => request.delete('/system/messages/read')