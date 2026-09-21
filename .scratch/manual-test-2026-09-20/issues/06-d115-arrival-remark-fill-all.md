# 06 · D115 到货备注「统一填充应用到全部行」

Type: task
Status: ready-for-agent
Blocked by: 无

## 范围

### 前端（PurchaseRequestView.vue）
- 认领/修改到货计划弹窗，在现有「统一填充：[日期选择] 应用到全部行」旁加同款一组：
  「统一填充：[el-input/el-input + 应用按钮] 到货备注 → 应用到全部行」；
- applyRemarkToAll：forEach 覆盖**全部行** arrivalRemark（与 applyDateToAll 同口径，不跳过已填行），个别行再手改；
- 表格下方常驻格式说明与列头问号 tooltip 维持不动（D109）。

### 后端
- 无改动（整单保存接口已接收全部行 arrivalRemark）。

### 测试
- build 通过；手测：填一次备注→应用→20 行全覆盖→改个别行→保存→重新打开认领/修改弹窗值正确。

## 验收
- 16/20 行同供应商时只需填一次；行为与日期统一填充完全对称。
