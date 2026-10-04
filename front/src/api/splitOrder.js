import request from '@/utils/request'

// ADR-0020/D116：成品拆分单（行终止后已入库未出库成品的处置）
export const getSplitOrderPageAPI = (params) => request.get('/business/split-orders/page', { params })
export const getSplitOrderDetailAPI = (id) => request.get(`/business/split-orders/${id}`)
export const createSplitOrderAPI = (data) => request.post('/business/split-orders', data)
export const keepProductAPI = (salesDetailId) => request.put(`/business/split-orders/keep/${salesDetailId}`)
export const claimSplitOrderAPI = (id) => request.put(`/business/split-orders/${id}/claim`)
export const confirmOutboundAPI = (id) => request.put(`/business/split-orders/${id}/confirm-outbound`)
export const confirmReceiptAPI = (id) => request.put(`/business/split-orders/${id}/confirm-receipt`)
export const abandonSplitOrderAPI = (id, data) => request.put(`/business/split-orders/${id}/abandon`, data)
export const submitSplitReturnAPI = (id, data) => request.post(`/business/split-orders/${id}/submit-return`, data)
export const confirmRestockAPI = (id) => request.put(`/business/split-orders/${id}/confirm-restock`)
export const voidSplitOrderAPI = (id, data) => request.put(`/business/split-orders/${id}/void`, data)
