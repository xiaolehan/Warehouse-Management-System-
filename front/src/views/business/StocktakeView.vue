<template>
  <div class="page-container">
    <el-card class="filter-card" shadow="never">
      <div class="filter-bar">
        <el-select v-model="query.status" placeholder="状态" clearable style="width: 130px" @change="loadList">
          <el-option label="盘点中" :value="1" />
          <el-option label="待审核" :value="2" />
          <el-option label="已完成" :value="3" />
          <el-option label="已取消" :value="4" />
        </el-select>
        <el-input v-model="query.stocktakeNo" placeholder="盘点单号" clearable style="width: 200px" @keyup.enter="loadList" />
        <el-button type="primary" :icon="Search" @click="loadList">查询</el-button>
        <el-button :icon="Refresh" @click="handleReset">重置</el-button>
        <div class="filter-bar-right">
          <el-button type="primary" :icon="Plus" v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="openCreate">
            新建盘点单
          </el-button>
        </div>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table :data="tableData" border stripe v-loading="loading" empty-text="暂无盘点单">
        <el-table-column prop="stocktakeNo" label="盘点单号" min-width="180" />
        <el-table-column label="状态" width="90" align="center">
          <template #default="scope">
            <el-tag :type="statusTagType(scope.row.status)">{{ scope.row.statusText }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="盘点进度" width="110" align="center">
          <template #default="scope">
            <span v-if="scope.row.status === 3">
              盈{{ scope.row.overRows }}/亏{{ scope.row.shortRows }}/平{{ scope.row.matchRows }}
            </span>
            <span v-else>{{ scope.row.countedRows }}/{{ scope.row.totalRows }} 行</span>
          </template>
        </el-table-column>
        <el-table-column prop="operatorName" label="建单人" width="110" />
        <el-table-column label="建单时间" width="160">
          <template #default="scope">{{ fmtTime(scope.row.operationTime) }}</template>
        </el-table-column>
        <el-table-column prop="reviewerName" label="审核人" width="110">
          <template #default="scope">{{ scope.row.reviewerName || '—' }}</template>
        </el-table-column>
        <el-table-column label="审核时间" width="160">
          <template #default="scope">{{ scope.row.reviewTime ? fmtTime(scope.row.reviewTime) : '—' }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="scope">
            <el-button link type="primary" @click="openDetail(scope.row)">详情</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-pagination
        class="pagination"
        v-model:current-page="query.pageNum"
        v-model:page-size="query.pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        @size-change="loadList"
        @current-change="loadList"
      />
    </el-card>

    <!-- 新建盘点单：自选商品范围（D81），最久未盘排最前，未完结单商品禁选 -->
    <el-dialog v-model="createVisible" title="新建盘点单" width="900px" top="6vh">
      <div class="create-toolbar">
        <el-radio-group v-model="createType" @change="loadOptions">
          <el-radio-button value="">全部</el-radio-button>
          <el-radio-button value="material">物料</el-radio-button>
          <el-radio-button value="product">成品</el-radio-button>
        </el-radio-group>
        <el-input v-model="optionKeyword" placeholder="按名称/编码过滤" clearable style="width: 220px" />
        <span class="create-tip">按「上次盘点时间」升序（从未盘点的排最前）；灰色行为未完结盘点单中的商品</span>
      </div>
      <el-table
        ref="optionTableRef"
        :data="filteredOptions"
        border
        height="320"
        v-loading="optionsLoading"
        @selection-change="handleSelectionChange"
      >
        <el-table-column type="selection" width="46" :selectable="(row) => !row.inOpenStocktake" />
        <el-table-column prop="goodsCode" label="编码" width="110" />
        <el-table-column prop="goodsName" label="名称" min-width="140" show-overflow-tooltip />
        <el-table-column prop="spec" label="规格" width="100" show-overflow-tooltip />
        <el-table-column prop="material" label="材质" width="100" show-overflow-tooltip />
        <el-table-column label="类型" width="80" align="center">
          <template #default="scope">
            <el-tag :type="scope.row.type === 'product' ? 'success' : 'info'" size="small">
              {{ scope.row.type === 'product' ? '成品' : '物料' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="stock" label="当前库存" width="90" align="right" />
        <el-table-column label="上次盘点" width="150">
          <template #default="scope">
            <span :class="{ 'never-counted': !scope.row.lastStocktakeTime }">
              {{ scope.row.lastStocktakeTime ? fmtTime(scope.row.lastStocktakeTime) : '从未盘点' }}
            </span>
          </template>
        </el-table-column>
      </el-table>
      <!-- D85：逐行指定负责人（每行必选，默认建单人自己；员工限录本人负责行） -->
      <div v-if="selectedGoods.length > 0" class="assign-block">
        <div class="assign-header">
          <span>负责人分配（员工仅可录入本人负责行，管理员可录任意行）</span>
          <el-button link type="primary" @click="assignAllToSelf">全部派给我</el-button>
        </div>
        <el-table :data="selectedGoods" border size="small" max-height="200">
          <el-table-column prop="goodsCode" label="编码" width="110" />
          <el-table-column prop="goodsName" label="名称" min-width="150" show-overflow-tooltip />
          <el-table-column label="负责人" width="200">
            <template #default="scope">
              <el-select v-model="assignMap[scope.row.goodsId]" placeholder="必选" size="small">
                <el-option
                  v-for="o in assigneeOptions"
                  :key="o.userId"
                  :label="o.realName + (o.role === 'admin' ? '（管理员）' : '')"
                  :value="o.userId"
                />
              </el-select>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <el-input v-model="createRemark" placeholder="备注（选填，如：月末 A 区盘点）" style="margin-top: 12px" maxlength="200" />
      <template #footer>
        <span class="dialog-selected">已选 {{ selectedGoods.length }} 项</span>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" :disabled="selectedGoods.length === 0" @click="handleCreate">
          创建（账面快照以建单时点为准）
        </el-button>
      </template>
    </el-dialog>

    <!-- 详情 / 录入 / 审核 -->
    <el-dialog v-model="detailVisible" :title="`盘点单 ${detail?.stocktakeNo || ''}`" width="1000px" top="5vh">
      <template v-if="detail">
        <el-descriptions :column="4" border size="small">
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)">{{ detail.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="建单人">{{ detail.operatorName }}</el-descriptions-item>
          <el-descriptions-item label="建单时间">{{ fmtTime(detail.operationTime) }}</el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交人">{{ detail.submitterName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="提交时间">{{ detail.submitTime ? fmtTime(detail.submitTime) : '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核人">{{ detail.reviewerName || '—' }}</el-descriptions-item>
          <el-descriptions-item label="审核时间">{{ detail.reviewTime ? fmtTime(detail.reviewTime) : '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 4" label="取消人">{{ detail.cancelerName || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.status === 4" label="取消原因">{{ detail.cancelReason || '—' }}</el-descriptions-item>
          <el-descriptions-item v-if="detail.rejectReason" label="最近驳回">{{ detail.rejectReason }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="detail.status === 1 || detail.status === 2"
          class="snapshot-alert"
          type="info"
          :closable="false"
          :title="`账面快照定格于建单时点（${fmtTime(detail.operationTime)}）；盘点期间业务正常出入库，实际差异以审核生效时实时库存为准（ADR-0010）`"
        />

        <div class="summary-bar">
          <template v-if="detail.status === 3">
            <el-tag type="success">盘盈 {{ detail.overRows }} 行</el-tag>
            <el-tag type="danger">盘亏 {{ detail.shortRows }} 行</el-tag>
            <el-tag type="info">账实一致 {{ detail.matchRows }} 行</el-tag>
            <el-tag type="warning">未盘 {{ detail.unscannedRows }} 行</el-tag>
          </template>
          <template v-else>
            <el-tag type="primary">已盘 {{ detail.countedRows }} 行</el-tag>
            <el-tag type="warning">未盘 {{ detail.unscannedRows }} 行</el-tag>
            <span v-if="provisionalSummary.counted > 0" class="provisional-text">
              按快照预估：盈 {{ provisionalSummary.over }} / 亏 {{ provisionalSummary.short }} / 平 {{ provisionalSummary.match }}（以审核时实时库存为准）
            </span>
          </template>
        </div>

        <el-table :data="detail.detailList" border stripe max-height="420" :row-class-name="diffRowClass">
          <el-table-column type="index" width="50" label="#" />
          <el-table-column prop="goodsCode" label="编码" width="100" />
          <el-table-column prop="goodsName" label="物料/成品" min-width="130" show-overflow-tooltip />
          <el-table-column prop="spec" label="规格" width="90" show-overflow-tooltip />
          <el-table-column prop="material" label="材质" width="90" show-overflow-tooltip />
          <el-table-column prop="unit" label="单位" width="70" align="center" />
          <el-table-column prop="bookQty" label="账面快照" width="90" align="right" />
          <!-- D85：负责人——盘点中 admin 可改派（人不在岗时调整），其余只读展示 -->
          <el-table-column label="负责人" width="150" align="center">
            <template #default="scope">
              <el-select
                v-if="detail.status === 1 && isWarehouseAdmin"
                :model-value="scope.row.assigneeId"
                size="small"
                @change="(v) => handleReassign(scope.row, v)"
              >
                <el-option
                  v-for="o in assigneeOptions"
                  :key="o.userId"
                  :label="o.realName"
                  :value="o.userId"
                />
              </el-select>
              <span v-else>{{ scope.row.assigneeName || '未指定' }}</span>
            </template>
          </el-table-column>
          <el-table-column label="实盘数" width="140" align="center">
            <template #default="scope">
              <template v-if="detail.status === 1">
                <el-input-number
                  v-if="canEnterRow(scope.row)"
                  v-model="entryMap[scope.row.id]"
                  :min="0"
                  :controls="false"
                  placeholder="未盘"
                  style="width: 110px"
                />
                <el-tooltip v-else content="仅负责人本人或仓储管理员可录入" placement="top">
                  <span class="entry-locked">{{ scope.row.actualQty ?? '未盘' }}</span>
                </el-tooltip>
              </template>
              <span v-else>{{ scope.row.actualQty ?? '未盘' }}</span>
            </template>
          </el-table-column>
          <!-- D85：实际录入人（可能与负责人不同——如 admin 代录） -->
          <el-table-column label="录入人" width="100" align="center">
            <template #default="scope">
              <el-tooltip v-if="scope.row.counterName" :content="fmtTime(scope.row.countTime)" placement="top">
                <span>{{ scope.row.counterName }}</span>
              </el-tooltip>
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column v-if="detail.status === 3" prop="finalBookQty" label="生效时账面" width="100" align="right">
            <template #default="scope">{{ scope.row.finalBookQty ?? '—' }}</template>
          </el-table-column>
          <el-table-column v-if="detail.status === 3" label="差异" width="90" align="right">
            <template #default="scope">
              <span v-if="scope.row.diffQty == null">—</span>
              <span v-else-if="scope.row.diffQty > 0" class="diff-over">+{{ scope.row.diffQty }}</span>
              <span v-else-if="scope.row.diffQty < 0" class="diff-short">{{ scope.row.diffQty }}</span>
              <span v-else>0</span>
            </template>
          </el-table-column>
        </el-table>

        <div class="detail-actions">
          <template v-if="detail.status === 1">
            <el-checkbox v-model="exportBlind" label="盲盘导出（不含账面数）" />
            <el-button :icon="Download" :loading="exporting" @click="handleExport">导出盘点表</el-button>
            <el-upload
              :http-request="importRequest"
              :show-file-list="false"
              accept=".xlsx"
              style="display: inline-block; margin: 0 8px"
            >
              <el-button :icon="Upload" :loading="importing" v-permission="{ deptCodes: ['warehouse'] }">
                导入回填
              </el-button>
            </el-upload>
            <el-button type="primary" :loading="saving" v-permission="{ deptCodes: ['warehouse'] }" @click="handleSaveEntry">
              保存录入
            </el-button>
            <!-- D84 修订：提交 = 仓储成员级（员工实盘完直接送审），审核生效/驳回/取消仍收口 admin -->
            <el-button type="success" v-permission="{ deptCodes: ['warehouse'] }" @click="handleSubmit">
              提交审核
            </el-button>
            <el-button type="danger" plain v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleCancel">
              取消盘点单
            </el-button>
          </template>
          <template v-else-if="detail.status === 2">
            <el-checkbox v-model="exportBlind" label="盲盘导出（不含账面数）" />
            <el-button :icon="Download" :loading="exporting" @click="handleExport">导出盘点表</el-button>
            <el-button type="success" v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleReview">
              审核生效
            </el-button>
            <el-button type="warning" v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleReject">
              驳回重录
            </el-button>
            <el-button type="danger" plain v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleCancel">
              取消盘点单
            </el-button>
          </template>
          <template v-else>
            <el-checkbox v-model="exportBlind" label="盲盘导出（不含账面数）" />
            <el-button :icon="Download" :loading="exporting" @click="handleExport">导出盘点表</el-button>
          </template>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Plus, Refresh, Search, Upload } from '@element-plus/icons-vue'
import {
  assignStocktakeAPI,
  cancelStocktakeAPI,
  createStocktakeAPI,
  entryStocktakeAPI,
  exportStocktakeAPI,
  getStocktakeAssigneeOptionsAPI,
  getStocktakeDetailAPI,
  getStocktakeGoodsOptionsAPI,
  getStocktakePageAPI,
  importStocktakeAPI,
  rejectStocktakeAPI,
  reviewStocktakeAPI,
  submitStocktakeAPI
} from '@/api/stocktake'
import { localDateString, saveBlobAs } from '@/utils/download'
import { getDeptCode, getRole, getUserId } from '@/utils/auth'

// D85：当前用户角色/id——员工限录本人负责行（与后端硬校验一致，前端只做交互兜底）
const currentUserId = getUserId()
const isWarehouseAdmin = getRole() === 'admin' && getDeptCode() === 'warehouse'

const query = reactive({ pageNum: 1, pageSize: 10, status: null, stocktakeNo: '' })
const tableData = ref([])
const total = ref(0)
const loading = ref(false)

const loadList = async () => {
  loading.value = true
  try {
    const res = await getStocktakePageAPI(query)
    tableData.value = res.data?.records || []
    total.value = res.data?.total || 0
  } finally {
    loading.value = false
  }
}

const handleReset = () => {
  query.pageNum = 1
  query.status = null
  query.stocktakeNo = ''
  loadList()
}

// ---------- 新建 ----------

const createVisible = ref(false)
const createType = ref('')
const optionKeyword = ref('')
const options = ref([])
const optionsLoading = ref(false)
const selectedGoods = ref([])
const createRemark = ref('')
const creating = ref(false)
// D85：负责人候选 + 逐行分配（goodsId -> assigneeId）
const assigneeOptions = ref([])
const assignMap = reactive({})

const openCreate = async () => {
  createVisible.value = true
  createRemark.value = ''
  selectedGoods.value = []
  Object.keys(assignMap).forEach((k) => delete assignMap[k])
  loadOptions()
  if (assigneeOptions.value.length === 0) {
    const res = await getStocktakeAssigneeOptionsAPI()
    assigneeOptions.value = res.data || []
  }
}

// 勾选变化时给新行默认负责人（默认建单人自己），取消勾选清理
const handleSelectionChange = (rows) => {
  selectedGoods.value = rows
  const ids = new Set(rows.map((g) => g.goodsId))
  Object.keys(assignMap).forEach((k) => {
    if (!ids.has(Number(k))) delete assignMap[k]
  })
  rows.forEach((g) => {
    if (assignMap[g.goodsId] === undefined) {
      const inOptions = assigneeOptions.value.some((o) => o.userId === currentUserId)
      assignMap[g.goodsId] = inOptions ? currentUserId : null
    }
  })
}

// 一键把已选行全派给当前用户（建单人）
const assignAllToSelf = () => {
  const inOptions = assigneeOptions.value.some((o) => o.userId === currentUserId)
  if (!inOptions) {
    ElMessage.warning('当前用户不在负责人候选中')
    return
  }
  selectedGoods.value.forEach((g) => {
    assignMap[g.goodsId] = currentUserId
  })
}

const loadOptions = async () => {
  optionsLoading.value = true
  try {
    const res = await getStocktakeGoodsOptionsAPI(createType.value ? { type: createType.value } : {})
    // 最久未盘排最前（从未盘点优先），其次按名称
    options.value = (res.data || []).slice().sort((a, b) => {
      if (!a.lastStocktakeTime && !b.lastStocktakeTime) return a.goodsName.localeCompare(b.goodsName)
      if (!a.lastStocktakeTime) return -1
      if (!b.lastStocktakeTime) return 1
      return new Date(a.lastStocktakeTime) - new Date(b.lastStocktakeTime)
    })
  } finally {
    optionsLoading.value = false
  }
}

const filteredOptions = computed(() => {
  const kw = optionKeyword.value.trim()
  if (!kw) return options.value
  return options.value.filter((o) => o.goodsName?.includes(kw) || o.goodsCode?.includes(kw))
})

const handleCreate = async () => {
  const unassigned = selectedGoods.value.filter((g) => !assignMap[g.goodsId])
  if (unassigned.length > 0) {
    ElMessage.warning(`请为每行指定负责人（${unassigned.length} 行未指定）`)
    return
  }
  creating.value = true
  try {
    await createStocktakeAPI({
      items: selectedGoods.value.map((g) => ({ goodsId: g.goodsId, assigneeId: assignMap[g.goodsId] })),
      remark: createRemark.value || null
    })
    ElMessage.success('盘点单已创建，账面快照已定格')
    createVisible.value = false
    loadList()
  } finally {
    creating.value = false
  }
}

// ---------- 详情 / 录入 / 审核 ----------

const detailVisible = ref(false)
const detail = ref(null)
const entryMap = reactive({})
const exportBlind = ref(true)
const exporting = ref(false)
const importing = ref(false)
const saving = ref(false)

const openDetail = async (row) => {
  const res = await getStocktakeDetailAPI(row.id)
  detail.value = res.data
  Object.keys(entryMap).forEach((k) => delete entryMap[k])
  ;(res.data?.detailList || []).forEach((d) => {
    entryMap[d.id] = d.actualQty ?? null
  })
  // admin 改派下拉需要负责人候选
  if (isWarehouseAdmin && assigneeOptions.value.length === 0) {
    const optRes = await getStocktakeAssigneeOptionsAPI()
    assigneeOptions.value = optRes.data || []
  }
  detailVisible.value = true
}

const reloadDetail = async () => {
  if (!detail.value) return
  const res = await getStocktakeDetailAPI(detail.value.id)
  detail.value = res.data
  Object.keys(entryMap).forEach((k) => delete entryMap[k])
  ;(res.data?.detailList || []).forEach((d) => {
    entryMap[d.id] = d.actualQty ?? null
  })
  loadList()
}

const provisionalSummary = computed(() => {
  const list = detail.value?.detailList || []
  let over = 0
  let short = 0
  let match = 0
  let counted = 0
  list.forEach((d) => {
    if (d.actualQty == null) return
    counted++
    const diff = d.actualQty - (d.bookQty ?? 0)
    if (diff > 0) over++
    else if (diff < 0) short++
    else match++
  })
  return { over, short, match, counted }
})

const diffRowClass = ({ row }) => {
  if (detail.value?.status === 3 && row.diffQty != null && row.diffQty !== 0) return 'diff-row'
  if (detail.value?.status !== 3 && row.actualQty != null && row.actualQty !== row.bookQty) return 'diff-row'
  return ''
}

// D85：行录入权限（后端硬校验为准，前端仅交互兜底）——admin 任意行，员工限本人负责行
const canEnterRow = (row) => isWarehouseAdmin || row.assigneeId === currentUserId

// D85：admin 盘点中改派负责人
const handleReassign = async (row, newAssigneeId) => {
  const target = assigneeOptions.value.find((o) => o.userId === newAssigneeId)
  try {
    await assignStocktakeAPI(detail.value.id, { detailId: row.id, assigneeId: newAssigneeId })
    row.assigneeId = newAssigneeId
    row.assigneeName = target?.realName || row.assigneeName
    ElMessage.success(`「${row.goodsName}」负责人已改派为 ${row.assigneeName}`)
  } catch (e) {
    row.assigneeId = row.assigneeId // select 已是本地值，失败时刷新回正
    reloadDetail()
  }
}

const handleSaveEntry = async () => {
  const items = (detail.value?.detailList || [])
    .filter((d) => canEnterRow(d))
    .filter((d) => entryMap[d.id] !== null && entryMap[d.id] !== undefined && entryMap[d.id] !== d.actualQty)
    .map((d) => ({ detailId: d.id, actualQty: entryMap[d.id] }))
  if (items.length === 0) {
    ElMessage.warning('没有需要保存的变更')
    return
  }
  saving.value = true
  try {
    await entryStocktakeAPI(detail.value.id, { items })
    ElMessage.success(`已保存 ${items.length} 行实盘数`)
    reloadDetail()
  } finally {
    saving.value = false
  }
}

const handleSubmit = async () => {
  await ElMessageBox.confirm(
    `提交后进入待审核，不可再修改实盘数。当前已盘 ${detail.value.countedRows} 行 / 未盘 ${detail.value.unscannedRows} 行（未盘行不调整库存）。确认提交？`,
    '提交审核',
    { type: 'warning' }
  )
  await submitStocktakeAPI(detail.value.id)
  ElMessage.success('已提交，待仓储管理员审核')
  reloadDetail()
}

const handleReview = async () => {
  const p = provisionalSummary.value
  await ElMessageBox.confirm(
    `按账面快照预估：盘盈 ${p.over} 行 / 盘亏 ${p.short} 行 / 一致 ${p.match} 行 / 未盘 ${detail.value.unscannedRows} 行。` +
      '审核生效将按「生效时点实时库存」计算差异并调整库存，未盘行不动。确认审核生效？',
    '审核生效',
    { type: 'warning', confirmButtonText: '确认生效' }
  )
  await reviewStocktakeAPI(detail.value.id)
  ElMessage.success('盘点已生效，库存已调整')
  reloadDetail()
}

const handleReject = async () => {
  const { value } = await ElMessageBox.prompt('请输入驳回原因（退回后可在盘点中状态重新录入）', '驳回盘点单', {
    inputValidator: (v) => (v && v.trim() ? true : '驳回原因不能为空')
  })
  await rejectStocktakeAPI(detail.value.id, { reason: value.trim() })
  ElMessage.success('已驳回，退回盘点中')
  reloadDetail()
}

const handleCancel = async () => {
  const { value } = await ElMessageBox.prompt(
    '取消后单据进入「已取消」终态，不产生任何库存变动。取消原因（选填）：',
    '取消盘点单',
    { confirmButtonText: '确认取消', cancelButtonText: '返回', inputPlaceholder: '如：建错范围' }
  )
  await cancelStocktakeAPI(detail.value.id, { reason: value?.trim() || null })
  ElMessage.success('盘点单已取消')
  reloadDetail()
}

// ---------- 导出 / 导入 ----------

const handleExport = async () => {
  exporting.value = true
  try {
    const blob = await exportStocktakeAPI(detail.value.id, exportBlind.value)
    await saveBlobAs(blob, `盘点表-${detail.value.stocktakeNo}-${localDateString()}.xlsx`)
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

const importRequest = async ({ file }) => {
  importing.value = true
  try {
    const res = await importStocktakeAPI(detail.value.id, file)
    ElMessage.success(`回填完成：${res.data} 行实盘数已写入`)
    reloadDetail()
  } finally {
    importing.value = false
  }
}

// ---------- 工具 ----------

const statusTagType = (status) => {
  switch (status) {
    case 1: return 'warning'
    case 2: return 'primary'
    case 3: return 'success'
    case 4: return 'info'
    default: return 'info'
  }
}

const fmtTime = (t) => {
  if (!t) return '—'
  const d = new Date(t)
  const p = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`
}

loadList()
</script>

<style scoped>
.filter-bar {
  display: flex;
  gap: 10px;
  align-items: center;
}

.filter-bar-right {
  margin-left: auto;
}

.pagination {
  margin-top: 16px;
  justify-content: flex-end;
}

.create-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 12px;
}

.create-tip {
  color: #909399;
  font-size: 12px;
}

.never-counted {
  color: #e6a23c;
  font-weight: 600;
}

.assign-block {
  margin-top: 12px;
}

.assign-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 6px;
  color: #606266;
  font-size: 13px;
}

.entry-locked {
  color: #c0c4cc;
  cursor: not-allowed;
}

.dialog-selected {
  float: left;
  color: #606266;
}

.snapshot-alert {
  margin: 12px 0;
}

.summary-bar {
  display: flex;
  gap: 10px;
  align-items: center;
  margin: 12px 0;
}

.provisional-text {
  color: #909399;
  font-size: 13px;
}

.detail-actions {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.diff-over {
  color: #67c23a;
  font-weight: 600;
}

.diff-short {
  color: #f56c6c;
  font-weight: 600;
}

:deep(.diff-row) {
  background: #fdf6ec;
}
</style>
