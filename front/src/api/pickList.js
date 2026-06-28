import request from '@/utils/request'

export const getPickListPageAPI = (params) => request.get('/business/pick-lists/page', { params })
export const getPickListDetailAPI = (id) => request.get(`/business/pick-lists/${id}`)
export const createPickListAPI = (data) => request.post('/business/pick-lists', data)
export const issuePickListAPI = (id) => request.put(`/business/pick-lists/${id}/issue`)
export const confirmPickListAPI = (id) => request.put(`/business/pick-lists/${id}/confirm`)
export const rejectPickListAPI = (id, data) => request.put(`/business/pick-lists/${id}/reject`, data)
export const deletePickListAPI = (id) => request.delete(`/business/pick-lists/${id}`)
