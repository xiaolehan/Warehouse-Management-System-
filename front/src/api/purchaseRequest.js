import request from '@/utils/request'

export const getPurchaseRequestPageAPI = (params) => request.get('/business/purchase-requests/page', { params })
export const getPurchaseRequestDetailAPI = (id) => request.get(`/business/purchase-requests/${id}`)
export const getShortageGoodsAPI = () => request.get('/business/purchase-requests/shortage-goods')
export const createPurchaseRequestAPI = (data) => request.post('/business/purchase-requests', data)
export const processPurchaseRequestAPI = (id) => request.put(`/business/purchase-requests/${id}/process`)
export const arrivePurchaseRequestAPI = (id, data) => request.put(`/business/purchase-requests/${id}/arrive`, data)
export const confirmReceivePurchaseRequestAPI = (id) => request.put(`/business/purchase-requests/${id}/confirm-receive`)
export const arriveCancelPurchaseRequestAPI = (id) => request.put(`/business/purchase-requests/${id}/arrive-cancel`)
export const arriveRejectPurchaseRequestAPI = (id) => request.put(`/business/purchase-requests/${id}/arrive-reject`)
export const rejectPurchaseRequestAPI = (id, data) => request.put(`/business/purchase-requests/${id}/reject`, data)
export const deletePurchaseRequestAPI = (id) => request.delete(`/business/purchase-requests/${id}`)
