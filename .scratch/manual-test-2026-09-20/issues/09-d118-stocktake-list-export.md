# 09 · D118 盘点列表行操作直接导出

Type: task
Status: ready-for-agent
Blocked by: 无

前提更正：导出功能**已存在**于详情弹窗（盲盘/明盘 + 导入回填模板，GET /business/stocktake/{id}/export?blind=），用户问题是入口太深找不到。Q11=A。

## 范围

### 前端（StocktakeView.vue）
- 列表行操作在「详情」旁加「导出」按钮（Download 图标，权限同详情：仓储部门可见即可；导出端点本身无额外写权限）；
- 点击直接调 exportStocktakeAPI(id, false)（**明盘**，含账面数）→ saveBlobAs 下载，loading 防连点；
- 详情弹窗内的「盲盘导出勾选 + 导出盘点表 + 导入回填」原样保留。

### 后端
- 无改动（复用现有端点）。

### 测试
- build；手测：列表点导出→xlsx 含账面数；详情弹窗盲盘导出不含账面数；两条路下载文件名/格式一致。

## 验收
- 不进详情弹窗，列表上一键可导。
