import request from '@/utils/request'

// 系统参数：价格偏离阈值（销售建单前端提示用，任意登录用户可读）
export const getPriceDeviationThresholdAPI = () =>
  request.get('/system/configs/price-deviation-threshold')

// 超管更新价格偏离阈值，data: { value: 0.05 }
export const updatePriceDeviationThresholdAPI = (data) =>
  request.put('/system/configs/price-deviation-threshold', data)

// 超管查看全部系统参数
export const getSystemConfigListAPI = () =>
  request.get('/system/configs')
