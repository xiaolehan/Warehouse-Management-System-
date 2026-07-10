const APPROVAL_PENDING = 1
const APPROVAL_APPROVED = 2
const APPROVAL_REJECTED = 3
const APPROVAL_PROCESSING = 4

const ACTION_VOID = 'void'
const ACTION_VOID_RED = 'void_red'
const ACTION_PRICE_DEVIATION_CONFIRM = 'price_deviation_confirm'

export const isBizDocumentDeleted = (row = {}) => Boolean(row?.__uiDeleted || Number(row?.isDeleted || 0) === 1)

export const resolveBizDocumentState = (row = {}) => {
  if (isBizDocumentDeleted(row)) {
    return { label: '已删除', type: 'danger' }
  }

  const approvalStatus = Number(row?.approvalStatus || 0)
  const approvalAction = String(row?.approvalRequestAction || '').trim().toLowerCase()

  if (approvalStatus === APPROVAL_PENDING || approvalStatus === APPROVAL_PROCESSING) {
    return { label: '待审批', type: 'warning' }
  }

  if (approvalStatus === APPROVAL_REJECTED) {
    return { label: '驳回', type: 'danger' }
  }

  if (approvalStatus === APPROVAL_APPROVED) {
    // 仅作废类审批通过才显示作废态；价格偏离审批通过不等于作废，交由 confirmStatus 表达出库态
    if (approvalAction === ACTION_VOID_RED) {
      return { label: '作废并红冲成功', type: 'success' }
    }
    if (approvalAction === ACTION_VOID) {
      return { label: '作废成功', type: 'success' }
    }
    // price_deviation_confirm 已通过：不返回作废文案，落空让后续 confirmStatus 分支处理
  }

  if (Number(row?.bizStatus || 0) === 2) {
    return { label: '作废成功', type: 'success' }
  }

  if (Number(row?.bizStatus || 0) === 3) {
    return { label: '作废并红冲成功', type: 'success' }
  }

  return null
}

export const hasBizDocumentWorkflowState = (row = {}) => Boolean(resolveBizDocumentState(row))