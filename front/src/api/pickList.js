import request from '@/utils/request'

export const getPickListPageAPI = (params) => request.get('/business/pick-lists/page', { params })
export const getPickListDetailAPI = (id) => request.get(`/business/pick-lists/${id}`)
export const issuePickListAPI = (id) => request.put(`/business/pick-lists/${id}/issue`)
export const confirmPickListAPI = (id) => request.put(`/business/pick-lists/${id}/confirm`)
export const rejectPickListAPI = (id, data) => request.put(`/business/pick-lists/${id}/reject`, data)
export const deletePickListAPI = (id) => request.delete(`/business/pick-lists/${id}`)
export const batchDeletePickListsAPI = (ids) => request.post('/business/pick-lists/batch-delete', ids)

// 生产单领料
export const createProductionPickAPI = (orderId) => request.post(`/business/production-orders/${orderId}/pick`)
export const getProductionPickListAPI = (orderId) => request.get(`/business/production-orders/${orderId}/pick`)

// 生产单退料
export const createProductionReturnAPI = (orderId, data) => request.post(`/business/production-orders/${orderId}/return`, data)

// D73：终止退料预览 + 手动终止生产任务单
export const getProductionReturnableAPI = (orderId) => request.get(`/business/production-orders/${orderId}/returnable`)
export const terminateProductionOrderAPI = (orderId, data) => request.post(`/business/production-orders/${orderId}/terminate`, data)
