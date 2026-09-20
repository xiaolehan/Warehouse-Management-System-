// 预警中心/预警卡片可见部门（前端单一数据源；与后端 AuthzService.WARNING_DEPT_CODES 对齐）
export const WARNING_DEPT_CODES = ['warehouse', 'purchase', 'sales', 'production']

// 系统默认供应商（D100 锚点 id=1；与后端 GoodsService.DEFAULT_SUPPLIER_ID 对齐）。
// 物料挂此供应商=未知物料待匹配（D109）
export const DEFAULT_SUPPLIER_ID = 1
