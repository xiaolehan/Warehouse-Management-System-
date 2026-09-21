import request from '@/utils/request'

// D67：商品下拉收口到 base.js（单一出口），此处转导出保持既有调用方不改
export { getGoodsOptionsAPI } from './base'

export const getPurchasePageAPI = (params) => request.get('/business/purchases/page', { params })
export const getPurchaseDetailAPI = (id) => request.get(`/business/purchases/${id}`)
export const getReturnablePurchaseOptionsAPI = (params) => request.get('/business/purchases/options/returnable', { params })
// D124：批量「最近成交价」（已入库+正常最新明细价→标准进价→null），到货提交预填单价用。
// silent：仓储管理员也能进到货弹窗（路由双部门）但无进价权限，预填静默降级为手填，不弹全局错误（ADR-0012 豁免）
export const getLatestPurchasePricesAPI = (goodsIds) => request.get('/business/purchases/latest-prices', { params: { goodsIds: goodsIds.join(',') }, silent: true })
export const createPurchaseAPI = (data) => request.post('/business/purchases', data)
export const deletePurchaseAPI = (id) => request.delete(`/business/purchases/${id}`)
export const voidPurchaseAPI = (id, data) => request.put(`/business/purchases/${id}/void`, data)
export const arrivePurchaseAPI = (id) => request.put(`/business/purchases/${id}/arrive`)
export const confirmReceivePurchaseAPI = (id) => request.put(`/business/purchases/${id}/confirm-receive`)
// D104：单据流程时间线——谁在哪一步做了什么
export const getPurchaseTimelineAPI = (id) => request.get(`/business/purchases/${id}/timeline`)

export const getProductionPageAPI = (params) => request.get('/business/production/page', { params })
export const getProductionDetailAPI = (id) => request.get(`/business/production/${id}`)
export const createProductionAPI = (data) => request.post('/business/production', data)
export const deleteProductionAPI = (id) => request.delete(`/business/production/${id}`)
export const voidProductionAPI = (id, data) => request.put(`/business/production/${id}/void`, data)

export const getPurchaseReturnPageAPI = (params) => request.get('/business/purchase-returns/page', { params })
export const getPurchaseReturnDetailAPI = (id) => request.get(`/business/purchase-returns/${id}`)
export const createPurchaseReturnAPI = (data) => request.post('/business/purchase-returns', data)
export const deletePurchaseReturnAPI = (id) => request.delete(`/business/purchase-returns/${id}`)
export const voidPurchaseReturnAPI = (id, data) => request.put(`/business/purchase-returns/${id}/void`, data)
export const confirmOutPurchaseReturnAPI = (id) => request.put(`/business/purchase-returns/${id}/confirm-out`)
export const completePurchaseReturnAPI = (id) => request.put(`/business/purchase-returns/${id}/complete`)
export const getPurchaseReturnTimelineAPI = (id) => request.get(`/business/purchase-returns/${id}/timeline`)

export const getSalesPageAPI = (params) => request.get('/business/sales/page', { params })
export const getSalesDetailAPI = (id) => request.get(`/business/sales/${id}`)
// D71：履约时间线（销售单详情）；D70：生产建单关联销售单下拉
export const getSalesTimelineAPI = (id) => request.get(`/business/sales/${id}/timeline`)
export const getLinkableSalesOptionsAPI = (params) => request.get('/business/sales/options/linkable', { params })

export const getProductionOrderPageAPI = (params) => request.get('/business/production-order/page', { params })
export const getProductionOrderDetailAPI = (id) => request.get(`/business/production-order/${id}`)
// D114：任务单全动线时间线（谁在哪一步做了什么）
export const getProductionOrderTimelineAPI = (id) => request.get(`/business/production-order/${id}/timeline`)
export const createProductionOrderAPI = (data) => request.post('/business/production-order', data)
export const startProductionOrderAPI = (id) => request.post(`/business/production-order/${id}/start`)
export const completeProductionOrderAPI = (id) => request.post(`/business/production-order/${id}/complete`)
export const receiptProductionOrderAPI = (id) => request.post(`/business/production-order/${id}/receipt`)
// D107：撤销入库申请（生产端，仓储确认/驳回前可撤）
export const cancelProductionReceiptAPI = (id) => request.post(`/business/production-order/${id}/receipt-cancel`)
export const voidProductionOrderAPI = (id, reason) => request.post(`/business/production-order/${id}/void`, null, { params: { reason } })
// D71：生产手工修正预计完工时间（留痕 @AuditLog）
export const updateExpectedCompletionAPI = (id, data) => request.put(`/business/production-order/${id}/expected-completion`, data)
// 工序打卡（D64）：complete 打卡 / revoke 撤销（本人或生产管理员）
export const completeProductionStepAPI = (id, stepNo) => request.post(`/business/production-order/${id}/steps/${stepNo}/complete`)
export const revokeProductionStepAPI = (id, stepNo) => request.post(`/business/production-order/${id}/steps/${stepNo}/revoke`)

// D113：按销售单批量下达——候选销售单 / 预览明细行 / 提交下达
export const getBatchReleaseSalesOptionsAPI = () => request.get('/business/production-order/batch-release/sales-options')
export const getBatchReleasePreviewAPI = (salesOrderId) => request.get('/business/production-order/batch-release/preview', { params: { salesOrderId } })
export const batchReleaseProductionAPI = (data) => request.post('/business/production-order/batch-release', data)

// 生产质检
export const getQcSnapshotAPI = (orderId) => request.get(`/business/qc/order/${orderId}`)
export const recordQcAPI = (data) => request.post('/business/qc/record', data)
export const disposeQcAPI = (data) => request.post('/business/qc/dispose', data)
export const getReturnableSalesOptionsAPI = (params) => request.get('/business/sales/options/returnable', { params })
export const createSalesAPI = (data) => request.post('/business/sales', data)
export const deleteSalesAPI = (id) => request.delete(`/business/sales/${id}`)
export const voidSalesAPI = (id, data) => request.put(`/business/sales/${id}/void`, data)
export const confirmSalesAPI = (id) => request.put(`/business/sales/${id}/confirm`)

// D107：仓储确认/驳回生产端提交的成品入库申请（确认才加库存，任务单转已完成）
export const confirmProductionInboundAPI = (id) => request.put(`/business/production/${id}/confirm-inbound`)
export const rejectProductionInboundAPI = (id, data) => request.put(`/business/production/${id}/reject-inbound`, data)

export const getSalesReturnPageAPI = (params) => request.get('/business/sales-returns/page', { params })
export const getSalesReturnDetailAPI = (id) => request.get(`/business/sales-returns/${id}`)
export const createSalesReturnAPI = (data) => request.post('/business/sales-returns', data)
export const deleteSalesReturnAPI = (id) => request.delete(`/business/sales-returns/${id}`)
export const voidSalesReturnAPI = (id, data) => request.put(`/business/sales-returns/${id}/void`, data)
export const confirmSalesReturnAPI = (id) => request.put(`/business/sales-returns/${id}/confirm`)
export const getSalesReturnTimelineAPI = (id) => request.get(`/business/sales-returns/${id}/timeline`)

export const getChartOverviewAPI = (params) => request.get('/business/charts/overview', { params })
export const getChartTop5API = (params) => request.get('/business/charts/top5', { params })
export const getChartBrandRatioAPI = (params) => request.get('/business/charts/brand-ratio', { params })
export const getChartDailyTrendAPI = (params) => request.get('/business/charts/daily-trend', { params })

export const getAnnualStatsAPI = () => request.get('/business/annual-stats')
export const exportAnnualStatsAPI = () => request.get('/business/annual-stats/export', { responseType: 'blob' })

export const getChartProfitOverviewAPI = (params) => request.get('/business/charts/profit-overview', { params })
export const getChartProfitBrandTopAPI = (params) => request.get('/business/charts/profit-brand-top', { params })
export const getChartProfitDailyTrendAPI = (params) => request.get('/business/charts/profit-daily-trend', { params })
