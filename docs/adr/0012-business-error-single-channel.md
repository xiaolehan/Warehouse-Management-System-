# 业务错误提示单通道（拦截器统一提示，页面零样板）

> 状态：accepted（D90；废除"每个页面手写 `res.code` 检查 + 错误 toast"的分散约定）

阶段 25 测试确诊的问题 #1：供应商被商品引用后删除，后端守卫正常返回「该供应商下仍有关联商品，无法删除」，前端却**零反馈**——弹窗一关像什么都没发生。同类隐患遍布全站。

## 背景

- 后端 `GlobalExceptionHandler` 把**全部**异常（含 `@Valid` 参数校验、未登录、越权）封装为 HTTP 200 + `Result{code,msg}`；实测未登录/越权也是 HTTP 200 + body code 401/403，真 HTTP 错误码基本只剩网络层失败。
- 前端 request.js 响应拦截器成功通道曾是 `response => response.data` 原样透传，**从不检查 body code**；错误回调只处理 HTTP 401/403（对本后端近乎死代码）。于是每个页面必须手写 `if (res.code !== 200)` 检查并自行提示——全站 36 文件共 172 处检查 + 约 130 处 catch toast，漏一处静默一处。
- 更隐蔽的第二层：19 处确认弹窗链末尾的 `.catch(() => {})`，本意是吞 ElMessageBox「取消」分支的 reject，实际把页面已检查并抛出的业务错误一并吞掉——问题 #1 的**直接病灶**（两层各漏一半，消息到不了用户）。

## 决策

1. **单通道**：request.js 响应拦截器成功回调对 `body.code !== 200` 统一 `ElMessage.error(msg)` 并 `Promise.reject(new Error(msg))`；错误回调对非 401/403 的传输失败统一 toast「网络异常，请稍后重试」。**页面一律不再手写 API 错误检查/提示**（写了就是双重提示），只写成功提示、表单校验提示与业务逻辑；业务错误永不流入页面成功路径。
2. **豁免口 `{ silent: true }`**：单个请求可跳过全局提示（仍 reject），仅限轮询等有意静默场景——当前唯一用点是 MessageCenter 15 秒角标轮询（防会话过期/网络抖动时 toast 风暴）。新增用点须注释理由。
3. **blob 透传**：导出类响应（`responseType: blob/arraybuffer`）无 Result 封装，直接透传不做 code 检查；导出失败的 JSON 错误体由 `saveBlobAs` 的既有探测自理（独立下载通道，页面 catch 提示保留）。
4. **el-upload 通道**（BomView/StocktakeView）：`http-request` 手动回调机制保留（规避 el-upload 重复回调的既有设计）；`onError` 回调内与拦截器重复的通用 toast 删除，`onSuccess` 收到的必为成功载荷（code 检查解包）；成功载荷 sanity 检查（如缺 path）保留静态文案提示。
5. **全站清扫口径**：删除 172 处死 code 检查与约 130 处重复 toast；保留成功提示、表单校验提示、状态回滚/`finally`、带注释的有意静默 catch；本地校验 throw 与 API throw 混用同一 catch 的个案拆分为「本地校验 warning 前置 + catch 静默」（PurchaseReturnView.submitForm 已拆分）。

## Consequence

- 任何业务错误**必有一条且仅一条**用户可见提示；后端每条守卫文案 100% 可达用户——大量文案将首次对用户可见，重测时需顺带评审文案质量。
- 新页面零样板：无需 `res.code` 检查、无需错误 catch；该约定约束所有未来前端代码（违反即双重提示）。
- 19 处 `.catch(() => {})` 保留：吞 ElMessageBox 取消的本职仍在，业务错误已被拦截器先行提示，不再吞"用户可见的东西"。
- AI 助手问答失败保留「拦截器 toast + 聊天内错误泡」双通道：聊天泡是对话流内的持久留痕，与瞬时 toast 语义不同；是否给 AI 问答接 silent 豁免留作评审项。
- 观测（未收口，后续候选）：未登录/越权实为 HTTP 200 + body code 401/403，拦截器 HTTP 状态分支近乎死代码；会话过期后用户停留原页靠 toast 感知（与改前行为一致），「body code 401 → 清登录态并跳转 /login」列为后续优化候选。
- 无后端改动，无数据迁移；前端净删约 300 行样板。
