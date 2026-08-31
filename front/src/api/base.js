import request from '@/utils/request'

export const getSupplierPageAPI = (params) => request.get('/base/suppliers/page', { params })
export const getSupplierOptionsAPI = () => request.get('/base/suppliers/options')
export const getSupplierDetailAPI = (id) => request.get(`/base/suppliers/${id}`)
export const createSupplierAPI = (data) => request.post('/base/suppliers', data)
export const updateSupplierAPI = (id, data) => request.put(`/base/suppliers/${id}`, data)
export const deleteSupplierAPI = (id) => request.delete(`/base/suppliers/${id}`)

export const getGoodsPageAPI = (params) => request.get('/base/goods/page', { params })
export const getStockWarningPageAPI = (params) => request.get('/base/goods/page', {
	params: { ...params, warningOnly: true }
})
export const getGoodsOptionsAPI = () => request.get('/base/goods/options')
export const getGoodsProductOptionsAPI = () => request.get('/base/goods/options', { params: { type: 'product' } })
export const getGoodsMaterialOptionsAPI = () => request.get('/base/goods/options', { params: { type: 'material' } })
export const getGoodsDetailAPI = (id) => request.get(`/base/goods/${id}`)
export const createGoodsAPI = (data) => request.post('/base/goods', data)
export const updateGoodsAPI = (id, data) => request.put(`/base/goods/${id}`, data)
export const deleteGoodsAPI = (id) => request.delete(`/base/goods/${id}`)

export const getBomPageAPI = (params) => request.get('/base/bom/page', { params })
export const getBomDetailAPI = (id) => request.get(`/base/bom/${id}`)
export const createBomAPI = (data) => request.post('/base/bom', data)
export const updateBomAPI = (id, data) => request.put(`/base/bom/${id}`, data)
export const deleteBomAPI = (id) => request.delete(`/base/bom/${id}`)
