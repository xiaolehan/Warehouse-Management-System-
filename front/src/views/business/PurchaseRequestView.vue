<template>
  <div class="purchase-request-view">
    <el-card shadow="never">
      <!-- 查询区 -->
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="单号">
          <el-input v-model="searchForm.requestNo" placeholder="采购申请单号" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="待采购" :value="1" />
            <el-option label="采购中" :value="2" />
            <el-option label="待入库确认" :value="5" />
            <el-option label="已入库" :value="3" />
            <el-option label="已驳回" :value="4" />
          </el-select>
        </el-form-item>
        <el-form-item label="商品名">
          <el-input v-model="searchForm.goodsName" placeholder="明细商品名" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker v-model="searchForm.dateRange" type="daterange" value-format="YYYY-MM-DD"
            start-placeholder="开始" end-placeholder="结束" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="handleSearch">查询</el-button>
          <el-button @click="resetSearch">重置</el-button>
          <el-button type="success" v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleManual">新建采购申请</el-button>
          <el-button v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleShortage">缺货识别建单</el-button>
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="requestNo" label="单号" width="180" />
        <el-table-column label="明细">
          <template #default="{ row }">
            <div v-for="d in row.details" :key="d.id" class="detail-line">
              {{ d.goodsName }} × {{ d.quantity }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)">{{ row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="applicantName" label="申请人" width="100" />
        <el-table-column prop="operatorName" label="采购处理人" width="110" />
        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="360" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleView(row)">详情</el-button>
            <!-- 采购：待采购 → 认领 / 驳回 -->
            <el-button link type="primary" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }" @click="handleProcess(row)">认领</el-button>
            <el-button link type="warning" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }" @click="handleReject(row)">驳回</el-button>
            <!-- 采购：采购中 → 到货提交 -->
            <el-button link type="success" v-if="row.status === 2"
              v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }" @click="handleArrive(row)">到货提交</el-button>
            <!-- 采购：待入库确认 → 撤回到货 -->
            <el-button link type="warning" v-if="row.status === 5"
              v-permission="{ roles: ['admin'], deptCodes: ['purchase'] }" @click="handleArriveCancel(row)">撤回到货</el-button>
            <!-- 仓储：待入库确认 → 确认入库 / 驳回入库 -->
            <el-button link type="success" v-if="row.status === 5"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleConfirmReceive(row)">确认入库</el-button>
            <el-button link type="danger" v-if="row.status === 5"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleArriveReject(row)">驳回入库</el-button>
            <!-- 仓储：待采购且本人 → 撤销 -->
            <el-button link type="danger" v-if="row.status === 1 && isApplicant(row)" @click="handleDelete(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="total" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="[10, 20, 50]" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
    </el-card>

    <!-- 缺货识别建单对话框 -->
    <el-dialog v-model="addVisible" title="缺货识别 — 生成采购申请单" width="760px" :close-on-click-modal="false">
      <el-alert v-if="!shortageGoods.length" title="当前无缺货商品（库存均高于预警阈值）" type="info" :closable="false" />
      <template v-else>
        <el-table :data="addForm.details" border size="small" @selection-change="handleSelectionChange">
          <el-table-column type="selection" width="45" />
          <el-table-column label="商品" min-width="180">
            <template #default="{ row }">{{ row.goodsName }}（{{ row.goodsCode }}）</template>
          </el-table-column>
          <el-table-column label="当前库存" width="100">
            <template #default="{ row }">{{ row.stock }}</template>
          </el-table-column>
          <el-table-column label="预警阈值" width="100">
            <template #default="{ row }">{{ row.warningStock }}</template>
          </el-table-column>
          <el-table-column label="采购数量" width="140">
            <template #default="{ row }">
              <el-input-number v-model="row.quantity" :min="1" controls-position="right" style="width: 120px" />
            </template>
          </el-table-column>
        </el-table>
        <el-form style="margin-top: 12px" label-width="60px">
          <el-form-item label="备注">
            <el-input v-model="addForm.remark" placeholder="备注（可选）" />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!selectedDetails.length" @click="submitAdd">提交申请单</el-button>
      </template>
    </el-dialog>

    <!-- 手动建单对话框 -->
    <el-dialog v-model="manualVisible" title="新建采购申请单" width="760px" :close-on-click-modal="false">
      <el-alert title="可申请采购任意在售商品；提交后将通知采购管理员处理。" type="info" :closable="false" style="margin-bottom: 12px" />
      <el-table :data="manualForm.details" border size="small">
        <el-table-column label="序号" width="60" type="index" />
        <el-table-column label="商品" min-width="220">
          <template #default="{ row }">
            <el-select v-model="row.goodsId" placeholder="请选择商品" filterable style="width: 100%">
              <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="采购数量" width="150">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="1" controls-position="right" style="width: 130px" />
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80">
          <template #default="{ $index }">
            <el-button link type="danger" @click="removeManualRow($index)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-button style="margin-top: 8px" :icon="Plus" @click="addManualRow">添加商品</el-button>
      <el-form style="margin-top: 12px" label-width="60px">
        <el-form-item label="备注">
          <el-input v-model="manualForm.remark" placeholder="备注（可选）" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="manualVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" :disabled="!manualForm.details.length" @click="submitManual">提交申请单</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="viewVisible" title="采购申请单详情" width="680px">
      <el-descriptions :column="2" border v-if="viewData">
        <el-descriptions-item label="单号">{{ viewData.requestNo }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ viewData.statusText }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ viewData.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="采购处理人">{{ viewData.operatorName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ formatTime(viewData.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="认领时间">{{ formatTime(viewData.operationTime) }}</el-descriptions-item>
        <el-descriptions-item label="到货时间">{{ formatTime(viewData.arriveTime) }}</el-descriptions-item>
        <el-descriptions-item label="入库确认人">{{ viewData.confirmerName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="入库时间">{{ formatTime(viewData.confirmTime) }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ viewData.remark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因" :span="2" v-if="viewData.rejectReason">{{ viewData.rejectReason }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="viewData?.details || []" border size="small" style="margin-top: 12px">
        <el-table-column label="序号" width="60" type="index" />
        <el-table-column prop="goodsName" label="商品" />
        <el-table-column prop="quantity" label="数量" width="100" />
        <el-table-column label="采购单价" width="120">
          <template #default="{ row }">{{ row.unitPrice ? '¥' + row.unitPrice : '—' }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- 到货提交对话框 -->
    <el-dialog v-model="receiveVisible" title="采购到货提交" width="720px" :close-on-click-modal="false">
      <el-alert title="提交后通知仓储管理员确认入库；确认前不增加库存" type="info" :closable="false" style="margin-bottom: 12px" />
      <el-table :data="receiveForm.items" border size="small">
        <el-table-column label="商品" min-width="180">
          <template #default="{ row }">{{ row.goodsName }}</template>
        </el-table-column>
        <el-table-column label="申请数量" width="100">
          <template #default="{ row }">{{ row.requestQuantity }}</template>
        </el-table-column>
        <el-table-column label="入库数量" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.quantity" :min="1" controls-position="right" style="width: 120px" />
          </template>
        </el-table-column>
        <el-table-column label="采购单价" width="160">
          <template #default="{ row }">
            <el-input-number v-model="row.unitPrice" :min="0.01" :precision="2" :step="0.1" controls-position="right" style="width: 140px" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="receiveVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitArrive">提交到货</el-button>
      </template>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectVisible" title="驳回采购申请单" width="480px">
      <el-form>
        <el-form-item label="驳回原因" required>
          <el-input v-model="rejectForm.reason" type="textarea" :rows="3" placeholder="请填写驳回原因" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitReject">确认驳回</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import {
  getPurchaseRequestPageAPI, getPurchaseRequestDetailAPI, getShortageGoodsAPI,
  createPurchaseRequestAPI, processPurchaseRequestAPI, arrivePurchaseRequestAPI,
  confirmReceivePurchaseRequestAPI, arriveCancelPurchaseRequestAPI, arriveRejectPurchaseRequestAPI,
  rejectPurchaseRequestAPI, deletePurchaseRequestAPI
} from '@/api/purchaseRequest'
import { getGoodsOptionsAPI } from '@/api/business'

const userStore = useUserStore()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ requestNo: '', status: '', goodsName: '', dateRange: [] })

const addVisible = ref(false)
const submitting = ref(false)
const shortageGoods = ref([])
const addForm = reactive({ remark: '', details: [] })
const selectedDetails = ref([])

const manualVisible = ref(false)
const goodsOptions = ref([])
const manualForm = reactive({ remark: '', details: [] })

const viewVisible = ref(false)
const viewData = ref(null)

const receiveVisible = ref(false)
const receiveForm = reactive({ id: null, items: [] })

const rejectVisible = ref(false)
const rejectForm = reactive({ id: null, reason: '' })

const isApplicant = (row) => row.applicantName && row.applicantName === userStore.realName

const statusTagType = (status) => ({
  1: 'info', 2: 'warning', 3: 'success', 4: 'danger', 5: 'warning'
}[status] || 'info')

const formatTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '—'

const loadList = async () => {
  loading.value = true
  try {
    const hasDate = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      requestNo: searchForm.requestNo || undefined,
      status: searchForm.status || undefined,
      goodsName: searchForm.goodsName || undefined,
      startDate: hasDate ? searchForm.dateRange[0] : undefined,
      endDate: hasDate ? searchForm.dateRange[1] : undefined
    }
    const res = await getPurchaseRequestPageAPI(params)
    if (res.code !== 200) throw new Error(res.msg || '查询失败')
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const handleSearch = () => { currentPage.value = 1; loadList() }
const resetSearch = () => {
  Object.assign(searchForm, { requestNo: '', status: '', goodsName: '', dateRange: [] })
  currentPage.value = 1; loadList()
}
const handleSizeChange = (v) => { pageSize.value = v; currentPage.value = 1; loadList() }
const handleCurrentChange = (v) => { currentPage.value = v; loadList() }

// 缺货识别建单
const handleShortage = async () => {
  try {
    const res = await getShortageGoodsAPI()
    if (res.code !== 200) throw new Error(res.msg || '查询缺货商品失败')
    shortageGoods.value = (res.data || []).map(g => ({ ...g, quantity: 1 }))
    addForm.remark = ''
    addForm.details = shortageGoods.value
    selectedDetails.value = []
    addVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '查询缺货商品失败')
  }
}

const handleSelectionChange = (val) => { selectedDetails.value = val }

const submitAdd = async () => {
  if (!selectedDetails.value.length) return ElMessage.warning('请勾选要采购的商品')
  if (selectedDetails.value.some(d => !d.quantity || d.quantity < 1)) return ElMessage.warning('采购数量必须大于0')
  const payload = {
    remark: addForm.remark || undefined,
    details: selectedDetails.value.map(d => ({ goodsId: d.id, quantity: d.quantity }))
  }
  submitting.value = true
  try {
    const res = await createPurchaseRequestAPI(payload)
    if (res.code !== 200) throw new Error(res.msg || '创建失败')
    ElMessage.success('采购申请单已提交，已通知采购管理员')
    addVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    submitting.value = false
  }
}

// 手动建单（可申请任意在售商品，不限于缺货）
const handleManual = async () => {
  if (!goodsOptions.value.length) {
    try {
      const res = await getGoodsOptionsAPI()
      if (res.code !== 200) throw new Error(res.msg || '加载商品失败')
      goodsOptions.value = res.data || []
    } catch (e) {
      ElMessage.error(e.message || '加载商品失败')
      return
    }
  }
  manualForm.remark = ''
  manualForm.details = [{ goodsId: null, quantity: 1 }]
  manualVisible.value = true
}
const addManualRow = () => { manualForm.details.push({ goodsId: null, quantity: 1 }) }
const removeManualRow = (index) => { manualForm.details.splice(index, 1) }
const submitManual = async () => {
  const details = manualForm.details
  if (!details.length) return ElMessage.warning('请至少添加一个商品')
  if (details.some(d => !d.goodsId)) return ElMessage.warning('请选择全部商品')
  if (details.some(d => !d.quantity || d.quantity < 1)) return ElMessage.warning('采购数量必须大于0')
  const ids = details.map(d => d.goodsId)
  if (new Set(ids).size !== ids.length) return ElMessage.warning('存在重复商品，请合并或删除')
  const payload = {
    remark: manualForm.remark || undefined,
    details: details.map(d => ({ goodsId: d.goodsId, quantity: d.quantity }))
  }
  submitting.value = true
  try {
    const res = await createPurchaseRequestAPI(payload)
    if (res.code !== 200) throw new Error(res.msg || '创建失败')
    ElMessage.success('采购申请单已提交，已通知采购管理员')
    manualVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    submitting.value = false
  }
}

const handleView = async (row) => {
  try {
    const res = await getPurchaseRequestDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '查询失败')
    viewData.value = res.data
    viewVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
  }
}

const handleProcess = (row) => {
  ElMessageBox.confirm('确认认领该采购申请单？认领后状态变为采购中。', '认领', { type: 'warning' })
    .then(async () => {
      const res = await processPurchaseRequestAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '认领失败')
      ElMessage.success('已认领，状态变为采购中')
      loadList()
    }).catch(() => {})
}

const handleArrive = async (row) => {
  try {
    const res = await getPurchaseRequestDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '查询明细失败')
    receiveForm.id = row.id
    receiveForm.items = (res.data?.details || []).map(d => ({
      detailId: d.id,
      goodsName: d.goodsName,
      requestQuantity: d.quantity,
      quantity: d.quantity,
      unitPrice: d.unitPrice ? Number(d.unitPrice) : null
    }))
    receiveVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载明细失败')
  }
}

const submitArrive = async () => {
  if (receiveForm.items.some(i => !i.quantity || i.quantity < 1)) return ElMessage.warning('到货数量必须大于0')
  if (receiveForm.items.some(i => !i.unitPrice || i.unitPrice <= 0)) return ElMessage.warning('请填写全部采购单价')
  const payload = {
    items: receiveForm.items.map(i => ({
      detailId: i.detailId,
      quantity: i.quantity,
      unitPrice: i.unitPrice
    }))
  }
  submitting.value = true
  try {
    const res = await arrivePurchaseRequestAPI(receiveForm.id, payload)
    if (res.code !== 200) throw new Error(res.msg || '到货提交失败')
    ElMessage.success('到货已提交，待仓储管理员确认入库')
    receiveVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '到货提交失败')
  } finally {
    submitting.value = false
  }
}

const handleConfirmReceive = (row) => {
  ElMessageBox.confirm('确认入库后将逐条增加库存，不可撤销。是否继续？', '确认入库', { type: 'warning' })
    .then(async () => {
      const res = await confirmReceivePurchaseRequestAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '确认入库失败')
      ElMessage.success('已确认入库，库存已增加')
      loadList()
    }).catch(() => {})
}

const handleArriveCancel = (row) => {
  ElMessageBox.confirm('撤回到货后单据回到采购中，可重新提交到货。是否继续？', '撤回到货', { type: 'warning' })
    .then(async () => {
      const res = await arriveCancelPurchaseRequestAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '撤回失败')
      ElMessage.success('已撤回到货')
      loadList()
    }).catch(() => {})
}

const handleArriveReject = (row) => {
  ElMessageBox.confirm('驳回入库后单据回到采购中，采购可重新处理。是否继续？', '驳回入库', { type: 'warning' })
    .then(async () => {
      const res = await arriveRejectPurchaseRequestAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '驳回失败')
      ElMessage.success('已驳回入库')
      loadList()
    }).catch(() => {})
}

const handleReject = (row) => {
  rejectForm.id = row.id
  rejectForm.reason = ''
  rejectVisible.value = true
}

const submitReject = async () => {
  if (!rejectForm.reason) return ElMessage.warning('请填写驳回原因')
  submitting.value = true
  try {
    const res = await rejectPurchaseRequestAPI(rejectForm.id, { reason: rejectForm.reason })
    if (res.code !== 200) throw new Error(res.msg || '驳回失败')
    ElMessage.success('已驳回')
    rejectVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '驳回失败')
  } finally {
    submitting.value = false
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确认撤销该采购申请？', '警告', { type: 'warning' })
    .then(async () => {
      const res = await deletePurchaseRequestAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '撤销失败')
      ElMessage.success('已撤销')
      loadList()
    }).catch(() => {})
}

onMounted(loadList)
</script>

<style scoped>
.purchase-request-view { padding: 12px; }
.search-form { margin-bottom: 8px; }
.detail-line { line-height: 1.6; }
.pager { margin-top: 12px; justify-content: flex-end; }
</style>
