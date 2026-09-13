import request from '@/utils/request'

// 库存盘点（阶段 23，ADR-0010：无删除，只有取消）
export const getStocktakePageAPI = (params) => request.get('/business/stocktake/page', { params })
export const getStocktakeDetailAPI = (id) => request.get(`/business/stocktake/${id}`)
export const getStocktakeGoodsOptionsAPI = (params) => request.get('/business/stocktake/goods-options', { params })
export const createStocktakeAPI = (data) => request.post('/business/stocktake', data)
export const entryStocktakeAPI = (id, data) => request.put(`/business/stocktake/${id}/entry`, data)
export const submitStocktakeAPI = (id) => request.put(`/business/stocktake/${id}/submit`)
export const reviewStocktakeAPI = (id) => request.put(`/business/stocktake/${id}/review`)
export const rejectStocktakeAPI = (id, data) => request.put(`/business/stocktake/${id}/reject`, data)
export const cancelStocktakeAPI = (id, data) => request.put(`/business/stocktake/${id}/cancel`, data)

// D85：负责人候选（仓储部门启用成员）与改派（盘点中 admin 级）
export const getStocktakeAssigneeOptionsAPI = () => request.get('/business/stocktake/assignee-options')
export const assignStocktakeAPI = (id, data) => request.put(`/business/stocktake/${id}/assign`, data)

// 盲盘导出（blind=true 默认无账面数；false=明盘）与 xlsx 回填——导出表即模板
export const exportStocktakeAPI = (id, blind) =>
  request.get(`/business/stocktake/${id}/export`, { params: { blind }, responseType: 'blob', timeout: 60000 })
export const importStocktakeAPI = (id, file) => {
  const form = new FormData()
  form.append('file', file)
  return request.post(`/business/stocktake/${id}/import`, form, {
    headers: { 'Content-Type': 'multipart/form-data' }
  })
}
