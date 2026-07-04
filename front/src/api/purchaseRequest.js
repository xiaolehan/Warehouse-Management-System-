import request from '@/utils/request'

export const getPurchaseRequestPageAPI = (params) => request.get('/business/purchase-requests/page', { params })
export const getPurchaseRequestDetailAPI = (id) => request.get(`/business/purchase-requests/${id}`)
export const getShortageGoodsAPI = () => request.get('/business/purchase-requests/shortage-goods')
export const createPurchaseRequestAPI = (data) => request.post('/business/purchase-requests', data)
export const processPurchaseRequestAPI = (id) => request.put(`/business/purchase-requests/${id}/process`)
export const receivePurchaseRequestAPI = (id, data) => request.put(`/business/purchase-requests/${id}/receive`, data)
export const rejectPurchaseRequestAPI = (id, data) => request.put(`/business/purchase-requests/${id}/reject`, data)
export const deletePurchaseRequestAPI = (id) => request.delete(`/business/purchase-requests/${id}`)
