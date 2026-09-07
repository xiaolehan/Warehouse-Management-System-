<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item label="任务单号">
        <el-input v-model="searchForm.orderNo" placeholder="请输入任务单号" clearable />
      </el-form-item>
      <el-form-item label="成品名称">
        <el-input v-model="searchForm.goodsName" placeholder="请输入成品名称" clearable />
      </el-form-item>
      <el-form-item label="状态">
        <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 130px">
          <el-option v-for="s in statusOptions" :key="s.value" :label="s.label" :value="s.value" />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <el-button
          type="success" :icon="Plus" @click="handleAdd"
          v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
        >下达生产任务单</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData" border style="width: 100%" v-loading="loading">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="orderNo" label="任务单号" min-width="130" />
      <el-table-column prop="goodsName" label="成品名称" min-width="170" />
      <el-table-column prop="quantity" label="数量" width="80" align="center" />
      <el-table-column prop="unit" label="单位" width="70" align="center" />
      <el-table-column label="齐套状态" width="110" align="center">
        <template #default="scope">
          <el-tag :type="kitTagType(scope.row.kitStatus)" size="small">{{ scope.row.kitStatusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)" size="small">{{ scope.row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="下达时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="scope">
          <el-button size="small" :icon="View" @click="handleView(scope.row)">查看</el-button>
          <el-button v-if="scope.row.status === 1" size="small" type="primary" :icon="VideoPlay" @click="handleStart(scope.row)">开工</el-button>
          <el-button
            v-if="(scope.row.status === 1) && (scope.row.kitStatus === 'partial' || scope.row.kitStatus === 'block')"
            size="small" type="warning" link @click="openDraftDialog(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >补料</el-button>
          <el-button v-if="scope.row.status === 3" size="small" type="success" :icon="CircleCheck" @click="handleReceipt(scope.row)">生产入库</el-button>
          <el-button
            v-if="scope.row.status === 1 || scope.row.status === 2" size="small" type="danger" :icon="CloseBold"
            @click="handleVoid(scope.row)" v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >作废</el-button>
        </template>
      </el-table-column>
    </el-table>

    <div style="margin-top: 20px; display: flex; justify-content: flex-end;">
      <el-pagination
        v-model:current-page="currentPage"
        v-model:page-size="pageSize"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        @size-change="handleSizeChange"
        @current-change="handleCurrentChange"
      />
    </div>

    <!-- 下达生产任务单 -->
    <el-dialog :title="'下达生产任务单' + (createResult ? '（已下达，齐套结果如下）' : '')" v-model="createVisible" width="620px">
      <el-form :model="createForm" :rules="createRules" ref="createFormRef" label-width="90px" :disabled="!!createResult">
        <el-form-item label="成品" prop="goodsId">
          <el-select v-model="createForm.goodsId" filterable placeholder="选择成品（type=product）" style="width: 100%">
            <el-option
              v-for="opt in productOptions" :key="opt.goodsId"
              :label="`${opt.goodsName}（${opt.unit || ''}）`" :value="opt.goodsId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="生产数量" prop="quantity">
          <el-input-number v-model="createForm.quantity" :min="1" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="createForm.remark" type="textarea" :rows="2" placeholder="备注（可选）" />
        </el-form-item>
      </el-form>

      <template v-if="createResult">
        <el-divider content-position="left">
          齐套预警
          <el-tag :type="kitTagType(createResult.kitStatus)" size="small" style="margin-left: 8px">{{ createResult.kitStatusText }}</el-tag>
        </el-divider>
        <el-alert
          v-if="createResult.kitStatus === 'block'"
          title="存在严重缺料，开工将被阻断，请采购补齐后重试。" type="error" :closable="false" style="margin-bottom: 8px"
        />
        <el-alert
          v-else-if="createResult.kitStatus === 'partial'"
          title="存在部分缺料，可开工（有库存的部分将先发料），同时已通知采购补料。" type="warning" :closable="false" style="margin-bottom: 8px"
        />
        <el-alert v-else title="物料齐套，可正常开工。" type="success" :closable="false" style="margin-bottom: 8px" />
        <el-table :data="createResult.kitLines || []" border size="small">
          <el-table-column prop="goodsName" label="物料" min-width="120" />
          <el-table-column prop="unit" label="单位" width="60" />
          <el-table-column label="用量" width="80">
            <template #default="s">{{ fmtNum(s.row.unitUsage) }}</template>
          </el-table-column>
          <el-table-column label="需用量" width="90">
            <template #default="s">{{ fmtNum(s.row.required) }}</template>
          </el-table-column>
          <el-table-column prop="stock" label="库存" width="80" />
          <el-table-column label="缺口" width="90">
            <template #default="s">
              <span :class="s.row.deficit > 0 ? 'deficit-red' : ''">{{ fmtNum(s.row.deficit) }}</span>
            </template>
          </el-table-column>
          <el-table-column label="匹配" width="90" align="center">
            <template #default="s">
              <el-tag :type="lineTagType(s.row.lineStatus)" size="small">{{ s.row.lineStatusText }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template #footer>
        <el-button v-if="!createResult" type="primary" :icon="Check" @click="handleCreate">下达并预警</el-button>
        <el-button :icon="Close" @click="closeCreate">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 查看详情 -->
    <el-dialog title="生产任务单详情" v-model="detailVisible" width="720px" top="6vh">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="成品">{{ detail.goodsName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.quantity }} {{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)" size="small">{{ detail.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="齐套状态">
            <el-tag :type="kitTagType(detail.kitStatus)" size="small">{{ detail.kitStatusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="下达时间">{{ detail.createTime }}</el-descriptions-item>
        </el-descriptions>

        <el-divider content-position="left">装配工序（静态 SOP）</el-divider>
        <ol style="margin: 0; padding-left: 20px">
          <li v-for="(step, i) in (detail.processList || [])" :key="i">{{ step }}</li>
        </ol>

        <template v-if="detail.kitLines && detail.kitLines.length">
          <el-divider content-position="left">齐套明细</el-divider>
          <el-table :data="detail.kitLines" border size="small">
            <el-table-column prop="goodsName" label="物料" min-width="120" />
            <el-table-column label="需用量" width="100">
              <template #default="s">{{ fmtNum(s.row.required) }}</template>
            </el-table-column>
            <el-table-column prop="stock" label="库存" width="80" />
            <el-table-column label="缺口" width="90">
              <template #default="s"><span :class="s.row.deficit > 0 ? 'deficit-red' : ''">{{ fmtNum(s.row.deficit) }}</span></template>
            </el-table-column>
            <el-table-column label="匹配" width="90" align="center">
              <template #default="s"><el-tag :type="lineTagType(s.row.lineStatus)" size="small">{{ s.row.lineStatusText }}</el-tag></template>
            </el-table-column>
          </el-table>
        </template>

        <div style="display:flex; gap:12px; margin:12px 0; flex-wrap: wrap;">
          <el-button
            v-if="detail.status === 1 && !pickListStatus"
            type="primary"
            :loading="pickSubmitting"
            @click="doApplyPick"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >申请领料</el-button>
          <el-tag v-if="pickListStatus != null" :type="pickListTagType" size="medium">领料{{ pickListTagText }}</el-tag>
          <el-button
            v-if="detail.status === 2"
            type="warning"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
            @click="doOpenReturn"
          >生产退料</el-button>
        </div>
      </template>
    </el-dialog>

    <!-- 生产退料 -->
    <el-dialog v-model="returnVisible" title="生产退料" width="720px" :close-on-click-modal="false">
      <el-form label-width="80px">
        <el-form-item label="备注">
          <el-input v-model="returnRemark" type="textarea" :rows="2" placeholder="备注（可选）" />
        </el-form-item>
        <el-form-item label="退料明细" required>
          <el-table :data="returnItems" border size="small" style="width: 100%">
            <el-table-column label="序号" width="60" type="index" />
            <el-table-column label="物料" min-width="240">
              <template #default="{ row }">
                <el-select v-model="row.goodsId" placeholder="选择物料" filterable style="width: 100%">
                  <el-option
                    v-for="opt in returnMaterialOptions" :key="opt.id"
                    :label="`${opt.name}（${opt.unit || ''}）`" :value="opt.id"
                  />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="1" controls-position="right" style="width: 130px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80" align="center">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeReturnItem($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button type="primary" link style="margin-top: 8px" @click="addReturnItem">+ 添加行</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="returnVisible = false">取消</el-button>
        <el-button type="primary" :loading="returnSubmitting" @click="doSubmitReturn">提交退料</el-button>
      </template>
    </el-dialog>

    <!-- 补料 -->
    <el-dialog v-model="draftVisible" :title="`补料 - ${draftRow.orderNo || ''}`" width="760px">
      <el-table :data="draftLines" border>
        <el-table-column prop="goodsName" label="物料" min-width="140">
          <template #default="s">
            <span v-if="s.row.goodsId">{{ s.row.goodsName || '-' }}</span>
            <el-select v-else v-model="s.row.goodsId" filterable placeholder="请选择物料" size="small" style="width: 100%">
              <el-option
                v-for="opt in materialOptions" :key="opt.id"
                :label="`${opt.name}（${opt.unit || ''}）`" :value="opt.id"
              />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="需用量" width="90">
          <template #default="s">{{ fmtNum(s.row.required) }}</template>
        </el-table-column>
        <el-table-column prop="stock" label="库存" width="70" />
        <el-table-column label="缺口" width="70">
          <template #default="s">{{ fmtNum(s.row.deficit) }}</template>
        </el-table-column>
        <el-table-column label="申请数量" width="110">
          <template #default="s">
            <el-input-number v-model="s.row.applyQty" :min="0" size="small" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="draftVisible = false">取消</el-button>
        <el-button type="primary" :loading="draftSubmitting" @click="doCreateDraft">提交补料</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Refresh, Plus, View, VideoPlay, CircleCheck, CloseBold, Check, Close
} from '@element-plus/icons-vue'
import {
  receiptProductionOrderAPI,
  createProductionOrderAPI,
  getProductionOrderDetailAPI,
  getProductionOrderPageAPI,
  startProductionOrderAPI,
  voidProductionOrderAPI
} from '@/api/business'
import { getGoodsProductOptionsAPI, getGoodsMaterialOptionsAPI } from '@/api/base'
import { createDraftPurchaseRequestAPI } from '@/api/purchaseRequest'
import { createProductionPickAPI, getProductionPickListAPI, createProductionReturnAPI } from '@/api/pickList'

const statusOptions = [
  { value: 1, label: '待生产' },
  { value: 2, label: '生产中' },
  { value: 3, label: '待入库' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已作废' },
  { value: 6, label: '已报废' }
]

const searchForm = reactive({ orderNo: '', goodsName: '', status: null })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const productOptions = ref([])
const createVisible = ref(false)
const createFormRef = ref(null)
const createResult = ref(null)
const createForm = reactive({ goodsId: null, quantity: 1, remark: '' })
const createRules = {
  goodsId: [{ required: true, message: '请选择成品', trigger: 'change' }],
  quantity: [{ required: true, message: '请输入生产数量', trigger: 'blur' }]
}

const detailVisible = ref(false)
const detail = ref(null)

const pickListStatus = ref(null)
const pickSubmitting = ref(false)
const pickListTagText = computed(() => {
  const s = pickListStatus.value
  if (s === 1) return '待出库'
  if (s === 2) return '已出库，可开工'
  if (s === 3) return '已完成'
  if (s === 4) return '已驳回'
  return ''
})
const pickListTagType = computed(() => {
  const s = pickListStatus.value
  if (s === 1) return 'warning'
  if (s === 2 || s === 3) return 'success'
  if (s === 4) return 'danger'
  return 'info'
})

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      orderNo: searchForm.orderNo || undefined,
      goodsName: searchForm.goodsName || undefined,
      status: searchForm.status || undefined
    }
    const res = await getProductionOrderPageAPI(params)
    if (res.code !== 200) throw new Error(res.msg || '查询失败')
    const pageData = res.data || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
  } catch (error) {
    ElMessage.error(error.message || '加载生产任务单失败')
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  try {
    const p = await getGoodsProductOptionsAPI()
    productOptions.value = (p.data || []).map((it) => ({ goodsId: it.id, goodsName: it.name || it.goodsName, unit: it.unit }))
  } catch (error) {
    ElMessage.error(error.message || '加载成品选项失败')
  }
}

const handleSearch = () => { currentPage.value = 1; loadList() }
const resetSearch = () => {
  searchForm.orderNo = ''
  searchForm.goodsName = ''
  searchForm.status = null
  currentPage.value = 1
  loadList()
}
const handleSizeChange = (v) => { pageSize.value = v; currentPage.value = 1; loadList() }
const handleCurrentChange = () => loadList()

const handleAdd = () => {
  createResult.value = null
  createForm.goodsId = null
  createForm.quantity = 1
  createForm.remark = ''
  createFormRef.value?.clearValidate()
  createVisible.value = true
}

const handleCreate = () => {
  createFormRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      const res = await createProductionOrderAPI({
        goodsId: createForm.goodsId,
        quantity: createForm.quantity,
        remark: createForm.remark || ''
      })
      if (res.code !== 200) throw new Error(res.msg || '下达失败')
      createResult.value = res.data || {}
      ElMessage.success('生产任务单已下达')
    } catch (error) {
      ElMessage.error(error.message || '下达生产任务单失败')
    }
  })
}

const closeCreate = () => {
  createVisible.value = false
  if (createResult.value) loadList()
}

const openDetail = async (row) => {
  const res = await getProductionOrderDetailAPI(row.id)
  if (res.code !== 200) throw new Error(res.msg || '详情查询失败')
  detail.value = res.data || {}
  // 加载领料单状态（优先取 PICK 类型行，用于"领料已出库"标签展示）
  try {
    const pickRes = await getProductionPickListAPI(row.id)
    if (pickRes.code === 200 && pickRes.data?.length) {
      const pickRow = pickRes.data.find((p) => p.pickType === 'PICK') || pickRes.data[0]
      pickListStatus.value = pickRow.status
    } else {
      pickListStatus.value = null
    }
  } catch {
    pickListStatus.value = null
  }
  detailVisible.value = true
}

const handleView = async (row) => {
  try { await openDetail(row) } catch (error) { ElMessage.error(error.message) }
}

const doApplyPick = async () => {
  try {
    await ElMessageBox.confirm('确认申请领料？将按 BOM 生成该生产任务单的全部领料明细并提交仓储确认出库。', '申请领料', { type: 'warning' })
  } catch {
    return
  }
  pickSubmitting.value = true
  try {
    const res = await createProductionPickAPI(detail.value.id)
    if (res.code !== 200) throw new Error(res.msg || '申请领料失败')
    ElMessage.success('领料申请已提交，待仓储确认出库')
    // 刷新领料状态与列表（优先取 PICK 类型行）
    const pickRes = await getProductionPickListAPI(detail.value.id)
    if (pickRes.code === 200 && pickRes.data?.length) {
      const pickRow = pickRes.data.find((p) => p.pickType === 'PICK') || pickRes.data[0]
      pickListStatus.value = pickRow.status
    }
    loadList()
  } catch (error) {
    ElMessage.error(error.message || '申请领料失败')
  } finally {
    pickSubmitting.value = false
  }
}

// 生产退料
const returnVisible = ref(false)
const returnRow = ref({})
const returnRemark = ref('')
const returnItems = ref([])
const returnMaterialOptions = ref([])
const returnSubmitting = ref(false)

function addReturnItem() {
  returnItems.value.push({ goodsId: null, quantity: 1 })
}
function removeReturnItem(idx) {
  returnItems.value.splice(idx, 1)
}

async function doOpenReturn() {
  if (!returnMaterialOptions.value.length) {
    try {
      const res = await getGoodsMaterialOptionsAPI()
      returnMaterialOptions.value = res.data || []
    } catch (e) {
      ElMessage.error(e?.message || '加载物料选项失败')
      return
    }
  }
  returnRow.value = detail.value || {}
  returnRemark.value = ''
  returnItems.value = [{ goodsId: null, quantity: 1 }]
  returnVisible.value = true
}

async function doSubmitReturn() {
  const items = returnItems.value.filter((i) => i.goodsId && i.quantity > 0)
  if (!items.length) {
    ElMessage.warning('请至少填写一条退料明细')
    return
  }
  const invalid = returnItems.value.find((i) => (i.goodsId && !i.quantity) || (!i.goodsId && i.quantity > 0))
  if (invalid) {
    ElMessage.warning('存在未补全的明细行，请完善物料与数量')
    return
  }
  returnSubmitting.value = true
  try {
    const res = await createProductionReturnAPI(returnRow.value.id, {
      remark: returnRemark.value || '',
      items
    })
    if (res.code !== 200) throw new Error(res.msg || '退料提交失败')
    ElMessage.success('退料已提交，待仓储确认入库')
    returnVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '退料提交失败')
  } finally {
    returnSubmitting.value = false
  }
}

const handleStart = async (row) => {
  // 前置友好校验：领料单是否已全额出库（失败放行，由后端开工网关兜底返回准确错误）
  try {
    const res = await getProductionPickListAPI(row.id)
    if (res.code === 200) {
      const s = res.data?.[0]?.status
      if (!s) {
        ElMessage.warning('请先申请领料并由仓储确认出库')
        return
      }
      if (s !== 2 && s !== 3) {
        ElMessage.warning('领料单尚未全额出库，请等仓储确认')
        return
      }
    }
    // res.code !== 200 → 放行，交由后端开工网关兜底返回准确错误
  } catch {
    // 网络异常放行，后端兜底
  }
  try {
    ElMessageBox.confirm('确认开工？开工需该生产任务单的领料单已由仓储确认出库。', '开工确认', { type: 'warning' })
    .then(async () => {
      const res = await startProductionOrderAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '开工失败')
      ElMessage.success('已开工')
      await loadList()
    }).catch((e) => { if (e && e.message) ElMessage.error(e.message) })
  } catch (error) {
    ElMessage.error(error.message || '开工失败')
  }
}

const handleReceipt = (row) => {
  ElMessageBox.confirm(`确认生产任务单「${row.orderNo}」生产入库？入库后成品库存将增加 ${row.quantity} ${row.unit}，订单标记已完成。`, '入库确认', { type: 'warning' })
    .then(async () => {
      const res = await receiptProductionOrderAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '入库失败')
      ElMessage.success('已生产入库，成品库存已增加')
      await loadList()
    }).catch((e) => { if (e && e.message) ElMessage.error(e.message) })
}

const handleVoid = (row) => {
  ElMessageBox.prompt(`确认作废生产任务单「${row.orderNo}」？请填写作废原因`, '作废确认', {
    type: 'warning',
    inputPlaceholder: '作废原因（可选）',
    inputValidator: (v) => (v === '' ? false : true),
    inputErrorMessage: '作废原因不能为空'
  }).then(async ({ value }) => {
    const res = await voidProductionOrderAPI(row.id, value)
    if (res.code !== 200) throw new Error(res.msg || '作废失败')
    ElMessage.success('已作废')
    await loadList()
  }).catch((e) => { if (e === 'cancel') return; if (e && e.message) ElMessage.error(e.message) })
}

// 补料
const draftVisible = ref(false)
const draftRow = ref({})
const draftLines = ref([])
const draftSubmitting = ref(false)
const materialOptions = ref([])

async function loadMaterialOptions() {
  if (materialOptions.value.length) return
  try {
    const res = await getGoodsMaterialOptionsAPI()
    materialOptions.value = res.data || []
  } catch (error) {
    ElMessage.error(error.message || '加载物料选项失败')
  }
}

function openDraftDialog(row) {
  draftRow.value = row
  draftVisible.value = true
  draftSubmitting.value = false
  draftLines.value = []
  loadMaterialOptions()
  // 拉详情拿 kitLines，映射成可编辑行
  getProductionOrderDetailAPI(row.id).then((res) => {
    if (res.code !== 200) return
    const vo = res.data || {}
    draftLines.value = (vo.kitLines || [])
      .filter((l) => (l.deficit || 0) > 0)
      .map((l) => ({ ...l, applyQty: Math.ceil(l.deficit) }))
  }).catch(() => {})
}

function doCreateDraft() {
  const items = draftLines.value
    .filter((l) => l.applyQty > 0)
    .map((l) => ({ bomDetailId: l.bomDetailId, goodsId: l.goodsId, quantity: l.applyQty }))
  if (!items.length) {
    ElMessage.warning('请至少填一条申请数量')
    return
  }
  const missing = items.find((i) => !i.goodsId)
  if (missing) {
    ElMessage.warning('存在未关联物料的缺口行，请先选择物料')
    return
  }
  draftSubmitting.value = true
  createDraftPurchaseRequestAPI({
    productionOrderId: draftRow.value.id,
    details: items,
    remark: ''
  }).then((res) => {
    if (res.code !== 200) throw new Error(res.msg || '补料失败')
    ElMessage.success('补料已提交，待采购')
    draftVisible.value = false
    loadList()
  }).catch((error) => {
    ElMessage.error(error.message || '补料失败')
  }).finally(() => {
    draftSubmitting.value = false
  })
}

// 标题格式化工具
const fmtNum = (v) => (v == null ? '-' : Number(v).toLocaleString())
const kitTagType = (k) => (k === 'ok' ? 'success' : k === 'partial' ? 'warning' : k === 'block' ? 'danger' : 'info')
const statusTagType = (s) => (s === 1 ? 'info' : s === 2 ? 'warning' : s === 3 ? 'primary' : s === 4 ? 'success' : 'danger')
const lineTagType = (l) => (l === 'ok' ? 'success' : l === 'partial' ? 'warning' : 'danger')

onMounted(() => {
  loadList()
  loadOptions()
})
</script>

<style scoped>
.deficit-red { color: #f56c6c; font-weight: 600; }
</style>