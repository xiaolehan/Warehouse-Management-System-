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
// D67：商品下拉唯一出口（business.js 转导出本函数）；type 过滤由调用方传入
export const getGoodsOptionsAPI = (params = {}) => request.get('/base/goods/options', { params })
// 成品下拉支持 hasBom 附加过滤（生产任务单下达只列有有效 BOM 的成品）
export const getGoodsProductOptionsAPI = (params = {}) => getGoodsOptionsAPI({ type: 'product', ...params })
export const getGoodsMaterialOptionsAPI = () => getGoodsOptionsAPI({ type: 'material' })
export const getGoodsDetailAPI = (id) => request.get(`/base/goods/${id}`)
export const createGoodsAPI = (data) => request.post('/base/goods', data)
export const updateGoodsAPI = (id, data) => request.put(`/base/goods/${id}`, data)
export const deleteGoodsAPI = (id) => request.delete(`/base/goods/${id}`)
// D102：物料进价历史（仅采购/超管可见；弹窗展示该物料全部有效已入库采购记录，最近在上）
export const getGoodsPurchasePriceHistoryAPI = (id) => request.get(`/base/goods/${id}/purchase-price-history`)

export const getBomPageAPI = (params) => request.get('/base/bom/page', { params })
export const getBomDetailAPI = (id) => request.get(`/base/bom/${id}`)
export const createBomAPI = (data) => request.post('/base/bom', data)
export const updateBomAPI = (id, data) => request.put(`/base/bom/${id}`, data)
export const deleteBomAPI = (id, force = false) => request.delete(`/base/bom/${id}`, { params: force ? { force: true } : {} })
// D66：删除前检查——返回 unfinishedOrderCount/goodsName，供前端软保护二次确认
export const getBomDeleteCheckAPI = (id) => request.get(`/base/bom/${id}/delete-check`)
export const getBomExportAPI = (id) => request.get(`/base/bom/${id}/export`, { responseType: 'blob' })
export const getBomTemplateAPI = () => request.get('/base/bom/template', { responseType: 'blob' })
