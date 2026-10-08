<template>
  <div class="split-order-view">
    <el-card shadow="never">
      <!-- 查询区 -->
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="单号">
          <el-input v-model="searchForm.splitNo" placeholder="拆分单号" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 150px">
            <el-option v-for="opt in statusOptions" :key="opt.value" :label="opt.label" :value="opt.value" />
          </el-select>
        </el-form-item>
        <el-form-item label="销售单号">
          <el-input v-model="searchForm.salesOrderNo" placeholder="关联销售单号" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="成品">
          <el-input v-model="searchForm.goodsName" placeholder="成品名称" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
          <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="splitNo" label="拆分单号" width="190" />
        <el-table-column prop="salesOrderNo" label="关联销售单" width="190" />
        <el-table-column label="成品" min-width="160">
          <template #default="{ row }">
            {{ row.goodsName }}
            <span v-if="row.spec || row.material" style="color:#909399">（{{ [row.spec, row.material].filter(Boolean).join(' / ') }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="quantity" label="拆分数量" width="90" align="center" />
        <el-table-column label="状态" width="130">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="退料单" width="170">
          <template #default="{ row }">
            <span v-if="row.returnPickNo">{{ row.returnPickNo }}</span>
            <el-tag v-if="row.returnPickNo && row.status !== 5" type="danger" size="small" style="margin-left:4px">拆分退料</el-tag>
            <span v-else-if="row.returnPickNo && row.status === 5" style="color:#909399;font-size:12px">（拆分退料）</span>
            <span v-else style="color:#c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="claimUserName" label="生产领取人" width="100" />
        <el-table-column label="完成方式" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.status === 5" :type="row.finishType === 1 ? 'success' : 'info'" size="small">{{ row.finishTypeText }}</el-tag>
            <span v-else style="color:#c0c4cc">—</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="160">
          <template #default="{ row }">{{ fmtTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="230" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button v-if="row.status === 1" link type="primary" size="small"
              v-permission="{ roles: ['admin', 'employee'], deptCodes: ['production'] }" @click="handleClaim(row)">领取</el-button>
            <el-button v-if="row.status === 2" link type="success" size="small"
              v-permission="{ roles: ['admin', 'employee'], deptCodes: ['warehouse'] }" @click="handleConfirmOutbound(row)">确认成品出库</el-button>
            <el-button v-if="row.status === 3 && canProductionSide(row)" link type="success" size="small"
              @click="handleConfirmReceipt(row)">确认领到成品</el-button>
            <!-- 会话 67：已提交退料单（returnPickNo 有值）后禁再提交，防重复退料 -->
            <el-button v-if="row.status === 4 && canProductionSide(row) && !row.returnPickNo" link type="primary" size="small"
              @click="openReturnDialog(row)">提交拆分退料</el-button>
            <el-button v-if="[3, 4].includes(row.status) && canProductionSide(row)" link type="warning" size="small"
              @click="handleAbandon(row)">放弃拆分</el-button>
            <el-button v-if="[1, 2].includes(row.status) && canProductionSide(row)" link type="warning" size="small"
              @click="handleAbandonEarly(row)">放弃拆分</el-button>
            <el-button v-if="row.status === 7" link type="success" size="small"
              v-permission="{ roles: ['admin', 'employee'], deptCodes: ['warehouse'] }" @click="handleConfirmRestock(row)">确认成品回库</el-button>
            <el-button v-if="[1, 2].includes(row.status)" link type="danger" size="small"
              v-permission="{ roles: ['admin', 'employee'], deptCodes: ['warehouse'] }" @click="handleVoid(row)">作废</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- 分页 -->
      <el-pagination
        v-model:current-page="pageNum" v-model:page-size="pageSize"
        :total="total" :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next" style="margin-top: 12px; justify-content: flex-end"
        @size-change="load" @current-change="load"
      />
    </el-card>

    <!-- 详情弹窗 -->
    <el-dialog v-model="detailVisible" :title="`成品拆分单 - ${detail.splitNo || ''}`" width="760px">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="拆分单号">{{ detail.splitNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ detail.statusText }}</el-descriptions-item>
        <el-descriptions-item label="关联销售单">{{ detail.salesOrderNo }}</el-descriptions-item>
        <el-descriptions-item label="关联退料单">{{ detail.returnPickNo || '—' }}</el-descriptions-item>
        <el-descriptions-item label="成品">
          {{ detail.goodsName }}
          <span v-if="detail.spec || detail.material" style="color:#909399">（{{ [detail.spec, detail.material].filter(Boolean).join(' / ') }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="拆分数量">{{ detail.quantity }}</el-descriptions-item>
        <el-descriptions-item label="发起人">{{ detail.initiatorName || '—' }}（{{ fmtTime(detail.initTime) }}）</el-descriptions-item>
        <el-descriptions-item label="生产领取">{{ detail.claimUserName || '—' }}（{{ fmtTime(detail.claimTime) }}）</el-descriptions-item>
        <el-descriptions-item label="出库确认">{{ detail.outboundConfirmUserName || '—' }}（{{ fmtTime(detail.outboundConfirmTime) }}）</el-descriptions-item>
        <el-descriptions-item label="收货确认">{{ detail.receiptConfirmUserName || '—' }}（{{ fmtTime(detail.receiptConfirmTime) }}）</el-descriptions-item>
        <el-descriptions-item label="完成方式">{{ detail.status === 5 ? (detail.finishTypeText || '—') : '—' }}</el-descriptions-item>
        <el-descriptions-item label="放弃人">{{ detail.abandonUserName || '—' }}（{{ fmtTime(detail.abandonTime) }}）</el-descriptions-item>
      </el-descriptions>
      <div style="margin-top: 12px; font-weight: 600; font-size: 13px">拆分物料（BOM × 拆分数量快照）</div>
      <!-- 会话 67：拆分中且已提交退料单时，仓储端在详情里得到收料入库引导（决策 7a——原状：仓储无处操作） -->
      <el-alert
        v-if="isWarehouseAdmin && detail.status === 4 && detail.returnPickNo"
        type="warning" :closable="false" style="margin-top: 8px"
      >
        <template #title>
          拆分退料单 {{ detail.returnPickNo }} 已提交，请前往
          <el-link type="primary" style="vertical-align:baseline" @click="gotoPickList(detail)">领料单页</el-link>
          对该退料单「收料」完成入库。
        </template>
      </el-alert>
      <el-table :data="detail.details || []" border size="small" style="margin-top: 8px">
        <el-table-column label="物料" min-width="150">
          <template #default="{ row }">
            {{ row.goodsName }}
            <span v-if="row.spec || row.material" style="color:#909399">（{{ [row.spec, row.material].filter(Boolean).join(' / ') }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="requiredQuantity" label="需求量" width="100" align="center" />
        <!-- D140：生产端详情回显退料明细——已退量/差异备注；未提交退料或退料已撤销显示「—」 -->
        <el-table-column label="已退量" width="90" align="center">
          <template #default="{ row }">{{ row.returnedQuantity ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="差异备注" min-width="140">
          <template #default="{ row }">{{ row.returnDiffReason || '—' }}</template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button v-if="detail.status === 4 && canProductionSide(detail) && !detail.returnPickNo" type="primary" size="small" @click="openReturnDialog(detail)">提交拆分退料</el-button>
      </template>
    </el-dialog>

    <!-- 提交拆分退料弹窗（状态 4；默认全量预填，可减量+差异备注） -->
    <el-dialog v-model="returnDialogVisible" :title="`提交拆分退料 - ${returnRow.splitNo || ''}`" width="720px" :close-on-click-modal="false">
      <el-alert
        title="拆分完成后将拆出的物料退回仓库入库：按需减量并填写差异备注（损耗/丢失等），提交后由仓储确认回流入库。"
        type="info" :closable="false" style="margin-bottom: 12px"
      />
      <el-table :data="returnItems" border size="small">
        <el-table-column label="物料" min-width="150">
          <template #default="{ row }">
            {{ row.goodsName }}
            <span v-if="row.spec || row.material" style="color:#909399">（{{ [row.spec, row.material].filter(Boolean).join(' / ') }}）</span>
          </template>
        </el-table-column>
        <el-table-column prop="requiredQuantity" label="应退量" width="90" align="center" />
        <el-table-column label="退料数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="0" :max="row.requiredQuantity" controls-position="right" style="width: 120px" />
          </template>
      </el-table-column>
        <el-table-column label="差异备注" min-width="160">
          <template #default="{ row }">
            <el-input v-if="row.requiredQuantity - row.quantity > 0" v-model="row.diffReason" size="small" maxlength="200" placeholder="必填：损耗/丢失等原因" />
            <span v-else style="color:#c0c4cc">—</span>
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="returnDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="doSubmitReturn">提交退料</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { useRoute, useRouter } from 'vue-router'
import {
  getSplitOrderPageAPI, getSplitOrderDetailAPI, claimSplitOrderAPI, confirmOutboundAPI,
  confirmReceiptAPI, abandonSplitOrderAPI, submitSplitReturnAPI, confirmRestockAPI, voidSplitOrderAPI
} from '@/api/splitOrder'
import { useUserStore } from '@/stores/user'

// ADR-0020/D116：成品拆分单（行终止后已入库未出库成品的处置；仓储+生产双端协同）
const userStore = useUserStore()

const loading = ref(false)
const tableData = ref([])
const pageNum = ref(1)
const pageSize = ref(10)
const total = ref(0)

const searchForm = ref({
  splitNo: '',
  status: null,
  salesOrderNo: '',
  goodsName: ''
})

const statusOptions = [
  { value: 1, label: '待生产领取' },
  { value: 2, label: '待仓储确认成品出库' },
  { value: 3, label: '待生产确认收货' },
  { value: 4, label: '拆分中' },
  { value: 5, label: '已完成' },
  { value: 6, label: '已作废' },
  { value: 7, label: '待仓储确认成品回库' }
]

const statusTagType = (s) => {
  if (s === 5) return 'success'
  if (s === 6) return 'info'
  if (s === 7) return 'warning'
  if (s === 1) return 'info'
  return 'warning'
}

const fmtTime = (t) => (t ? String(t).replace('T', ' ').slice(0, 16) : '')

// 生产端操作人 = 生产管理员 或 已领取该单的生产员工（与后端 requireProductionSideAccess 同口径）
const canProductionSide = (row) => {
  if (userStore.role === 'admin' && userStore.deptCode === 'production') return true
  return userStore.deptCode === 'production' && !!row.claimUserName && row.claimUserName === userStore.realName
}

// 会话 67（决策 7a）：仓储管理员标识——详情里对「拆分中+已提交退料单」给收料入库引导
const isWarehouseAdmin = computed(() => userStore.role === 'admin' && userStore.deptCode === 'warehouse')
const router = useRouter()
const gotoPickList = (row) => {
  detailVisible.value = false
  router.push({ path: '/business/pick-list', query: { splitOrderId: String(row.id) } })
}

const load = async () => {
  loading.value = true
  try {
    const res = await getSplitOrderPageAPI({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      splitNo: searchForm.value.splitNo || undefined,
      status: searchForm.value.status ?? undefined,
      salesOrderNo: searchForm.value.salesOrderNo || undefined,
      goodsName: searchForm.value.goodsName || undefined
    })
    const page = res.data || {}
    tableData.value = page.records || []
    total.value = page.total || 0
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  pageNum.value = 1
  load()
}

const resetSearch = () => {
  searchForm.value = { splitNo: '', status: null, salesOrderNo: '', goodsName: '' }
  pageNum.value = 1
  load()
}

// 详情
const detailVisible = ref(false)
const detail = ref({})
const openDetail = async (row) => {
  try {
    const res = await getSplitOrderDetailAPI(row.id)
    detail.value = res.data || {}
    detailVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 会话 67（决策 7a）：消息深链 ?splitOrderId= → 直接打开该拆分单详情后清 query（D75/ProductionOrderView 范式）
const route = useRoute()
watch(() => route.query.splitOrderId, (v) => {
  if (v) {
    openDetail({ id: Number(v) })
    router.replace({ query: {} })
  }
}, { immediate: true })

// 生产领取（状态 1→2）
const handleClaim = async (row) => {
  try {
    await claimSplitOrderAPI(row.id)
    ElMessage.success('已领取，请到仓库确认成品出库')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 仓储确认成品出库（状态 2→3，成品库存减少）
const handleConfirmOutbound = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认对拆分单 ${row.splitNo} 出库成品「${row.goodsName}」× ${row.quantity}？出库后成品库存减少。`,
      '确认成品出库',
      { type: 'warning' }
    )
  } catch (e) {
    if (e === 'cancel') return
  }
  try {
    await confirmOutboundAPI(row.id)
    ElMessage.success('成品出库已确认，库存已减少')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 生产确认领到成品（状态 3→4）
const handleConfirmReceipt = async (row) => {
  try {
    await confirmReceiptAPI(row.id)
    ElMessage.success('已确认领到成品，可开始拆分')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 生产放弃拆分（状态 1/2 → 直接完成=放弃回库；状态 3/4 → 待仓储确认成品回库）
const handleAbandonEarly = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认放弃拆分 ${row.splitNo}？成品尚未出库，将直接完成（成品留在库存）。`,
      '放弃拆分',
      { type: 'warning' }
    )
  } catch (e) {
    if (e === 'cancel') return
  }
  try {
    await abandonSplitOrderAPI(row.id)
    ElMessage.success('已放弃拆分，成品留在库存')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleAbandon = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认放弃拆分 ${row.splitNo}？成品已出库，需仓库确认成品回库（库存加回）。`,
      '放弃拆分',
      { type: 'warning' }
    )
  } catch (e) {
    if (e === 'cancel') return
  }
  try {
    await abandonSplitOrderAPI(row.id)
    ElMessage.success('已放弃拆分，待仓库确认成品回库')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 仓储确认成品回库（状态 7→5 完成方式=放弃回库，成品库存加回）
const handleConfirmRestock = async (row) => {
  try {
    await ElMessageBox.confirm(
      `确认成品「${row.goodsName}」× ${row.quantity} 回库？回库后成品库存加回，拆分单以「放弃回库」完成。`,
      '确认成品回库',
      { type: 'warning' }
    )
  } catch (e) {
    if (e === 'cancel') return
  }
  try {
    await confirmRestockAPI(row.id)
    ElMessage.success('成品回库已确认，库存已加回')
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// 仓储作废（状态 1/2，仅仓储管理员）
const handleVoid = async (row) => {
  try {
    const { value } = await ElMessageBox.prompt('请填写作废原因（必填）', '作废拆分单', {
      confirmButtonText: '确认作废',
      cancelButtonText: '取消',
      inputValidator: (v) => (v && v.trim() ? true : '作废原因不能为空')
    })
    await voidSplitOrderAPI(row.id, { reason: value.trim() })
    ElMessage.success('拆分单已作废')
    load()
  } catch (e) {
    if (e === 'cancel') return
  }
}

// 提交拆分退料（状态 4；默认全量预填，减量需差异备注）
const returnDialogVisible = ref(false)
const returnRow = ref({})
const returnItems = ref([])
const returnSubmitting = ref(false)

const openReturnDialog = async (row) => {
  try {
    const res = await getSplitOrderDetailAPI(row.id)
    const d = res.data || {}
    returnRow.value = d
    returnItems.value = (d.details || []).map((it) => ({ ...it, quantity: it.requiredQuantity, diffReason: '' }))
    returnDialogVisible.value = true
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const doSubmitReturn = async () => {
  // 减量行差异备注必填（前端预检，后端同守卫）
  const items = returnItems.value
    .filter((i) => i.quantity >= 0)
    .map((i) => ({ goodsId: i.goodsId, quantity: i.quantity, diffReason: i.diffReason && i.diffReason.trim() ? i.diffReason.trim() : null }))
  const diffRows = items.filter((i, idx) => {
    const src = returnItems.value[idx]
    return src.requiredQuantity - src.quantity > 0 && !(i.diffReason && i.diffReason.trim())
  })
  if (diffRows.length) {
    ElMessage.warning('减量退料的行需填写差异备注说明原因（损耗/丢失等）')
    return
  }
  returnSubmitting.value = true
  try {
    await submitSplitReturnAPI(returnRow.value.id, { items })
    ElMessage.success('拆分退料单已提交，待仓储确认入库')
    returnDialogVisible.value = false
    load()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    returnSubmitting.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.search-form {
  margin-bottom: 4px;
}
</style>
