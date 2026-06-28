<template>
  <div class="pick-list-view">
    <el-card shadow="never">
      <!-- 查询区 -->
      <el-form :inline="true" :model="searchForm" class="search-form">
        <el-form-item label="单号">
          <el-input v-model="searchForm.pickNo" placeholder="领料单号" clearable @keyup.enter="handleSearch" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="searchForm.pickType" placeholder="全部" clearable style="width: 120px">
            <el-option label="领料" value="PICK" />
            <el-option label="补料" value="SUPPLY" />
            <el-option label="退料" value="RETURN" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="searchForm.status" placeholder="全部" clearable style="width: 120px">
            <el-option label="待发料" :value="1" />
            <el-option label="已发料" :value="2" />
            <el-option label="已完成" :value="3" />
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
          <el-button type="success" v-permission="{ roles: ['admin'], deptCodes: ['sales', 'warehouse'] }" @click="handleAdd">新增领料</el-button>
        </el-form-item>
      </el-form>

      <!-- 列表 -->
      <el-table v-loading="loading" :data="tableData" border stripe>
        <el-table-column prop="pickNo" label="单号" width="180" />
        <el-table-column label="类型" width="80">
          <template #default="{ row }">{{ row.pickTypeText }}</template>
        </el-table-column>
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
        <el-table-column prop="operatorName" label="发料人" width="100" />
        <el-table-column label="申请时间" width="160">
          <template #default="{ row }">{{ formatTime(row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="280" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="handleView(row)">详情</el-button>
            <el-button link type="primary" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleIssue(row)">发料</el-button>
            <el-button link type="warning" v-if="row.status === 1"
              v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }" @click="handleReject(row)">驳回</el-button>
            <el-button link type="success" v-if="row.status === 2 && isApplicant(row)" @click="handleConfirm(row)">确认收货</el-button>
            <el-button link type="danger" v-if="row.status === 1 && isApplicant(row)" @click="handleDelete(row)">撤销</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination class="pager" background layout="total, sizes, prev, pager, next, jumper"
        :total="total" :current-page="currentPage" :page-size="pageSize"
        :page-sizes="[10, 20, 50]" @size-change="handleSizeChange" @current-change="handleCurrentChange" />
    </el-card>

    <!-- 新增对话框 -->
    <el-dialog v-model="addVisible" title="新增领料单" width="720px" :close-on-click-modal="false">
      <el-form :model="addForm" label-width="90px">
        <el-form-item label="类型" required>
          <el-select v-model="addForm.pickType" placeholder="选择类型" style="width: 200px">
            <el-option label="领料" value="PICK" />
            <el-option label="补料" value="SUPPLY" />
            <el-option label="退料" value="RETURN" />
          </el-select>
        </el-form-item>
        <el-form-item label="关联销售单">
          <el-input v-model="addForm.sourceSalesId" placeholder="可选，销售单ID" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="addForm.remark" placeholder="备注" />
        </el-form-item>
        <el-form-item label="明细" required>
          <el-table :data="addForm.details" border size="small">
            <el-table-column label="序号" width="60" type="index" />
            <el-table-column label="商品" min-width="220">
              <template #default="{ row }">
                <el-select v-model="row.goodsId" placeholder="选择商品" filterable>
                  <el-option v-for="g in goodsOptions" :key="g.id" :label="g.name" :value="g.id" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数量" width="120">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="1" controls-position="right" style="width: 110px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="80">
              <template #default="{ $index }">
                <el-button link type="danger" @click="removeDetail($index)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <el-button class="add-detail-btn" type="primary" link @click="addDetail">+ 添加明细</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="addVisible = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submitAdd">提交</el-button>
      </template>
    </el-dialog>

    <!-- 详情对话框 -->
    <el-dialog v-model="viewVisible" title="领料单详情" width="640px">
      <el-descriptions :column="2" border v-if="viewData">
        <el-descriptions-item label="单号">{{ viewData.pickNo }}</el-descriptions-item>
        <el-descriptions-item label="类型">{{ viewData.pickTypeText }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ viewData.statusText }}</el-descriptions-item>
        <el-descriptions-item label="关联销售单">{{ viewData.sourceSalesId || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请人">{{ viewData.applicantName }}</el-descriptions-item>
        <el-descriptions-item label="发料人">{{ viewData.operatorName || '—' }}</el-descriptions-item>
        <el-descriptions-item label="申请时间">{{ formatTime(viewData.createTime) }}</el-descriptions-item>
        <el-descriptions-item label="发料时间">{{ formatTime(viewData.operationTime) }}</el-descriptions-item>
        <el-descriptions-item label="确认时间">{{ formatTime(viewData.confirmTime) }}</el-descriptions-item>
        <el-descriptions-item label="备注">{{ viewData.remark || '—' }}</el-descriptions-item>
        <el-descriptions-item label="驳回原因" :span="2" v-if="viewData.rejectReason">{{ viewData.rejectReason }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="viewData?.details || []" border size="small" style="margin-top: 12px">
        <el-table-column label="序号" width="60" type="index" />
        <el-table-column prop="goodsName" label="商品" />
        <el-table-column prop="quantity" label="数量" width="100" />
      </el-table>
    </el-dialog>

    <!-- 驳回对话框 -->
    <el-dialog v-model="rejectVisible" title="驳回领料单" width="480px">
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
import { useUserStore } from '@/stores/user'
import {
  getPickListPageAPI, getPickListDetailAPI, createPickListAPI,
  issuePickListAPI, confirmPickListAPI, rejectPickListAPI, deletePickListAPI
} from '@/api/pickList'
import { getGoodsOptionsAPI } from '@/api/business'

const userStore = useUserStore()

const loading = ref(false)
const tableData = ref([])
const total = ref(0)
const currentPage = ref(1)
const pageSize = ref(10)

const searchForm = reactive({ pickNo: '', pickType: '', status: '', goodsName: '', dateRange: [] })

const addVisible = ref(false)
const submitting = ref(false)
const addForm = reactive({ pickType: 'PICK', sourceSalesId: '', remark: '', details: [] })
const goodsOptions = ref([])

const viewVisible = ref(false)
const viewData = ref(null)

const rejectVisible = ref(false)
const rejectForm = reactive({ id: null, reason: '' })

const isApplicant = (row) => row.applicantName && row.applicantName === userStore.realName

const statusTagType = (status) => ({
  1: 'info', 2: 'warning', 3: 'success', 4: 'danger'
}[status] || 'info')

const formatTime = (t) => t ? String(t).replace('T', ' ').slice(0, 19) : '—'

const loadList = async () => {
  loading.value = true
  try {
    const hasDate = Array.isArray(searchForm.dateRange) && searchForm.dateRange.length === 2
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      pickNo: searchForm.pickNo || undefined,
      pickType: searchForm.pickType || undefined,
      status: searchForm.status || undefined,
      goodsName: searchForm.goodsName || undefined,
      startDate: hasDate ? searchForm.dateRange[0] : undefined,
      endDate: hasDate ? searchForm.dateRange[1] : undefined
    }
    const res = await getPickListPageAPI(params)
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
  Object.assign(searchForm, { pickNo: '', pickType: '', status: '', goodsName: '', dateRange: [] })
  currentPage.value = 1; loadList()
}
const handleSizeChange = (v) => { pageSize.value = v; currentPage.value = 1; loadList() }
const handleCurrentChange = (v) => { currentPage.value = v; loadList() }

const loadGoodsOptions = async () => {
  try {
    const res = await getGoodsOptionsAPI()
    if (res.code === 200) goodsOptions.value = res.data || []
  } catch (e) { /* ignore */ }
}

const addDetail = () => { addForm.details.push({ goodsId: null, quantity: 1 }) }
const removeDetail = (idx) => { addForm.details.splice(idx, 1) }

const handleAdd = () => {
  Object.assign(addForm, { pickType: 'PICK', sourceSalesId: '', remark: '', details: [{ goodsId: null, quantity: 1 }] })
  if (!goodsOptions.value.length) loadGoodsOptions()
  addVisible.value = true
}

const submitAdd = async () => {
  if (!addForm.pickType) return ElMessage.warning('请选择类型')
  if (!addForm.details.length) return ElMessage.warning('至少添加一条明细')
  if (addForm.details.some(d => !d.goodsId || !d.quantity)) return ElMessage.warning('请补全明细')
  const payload = {
    pickType: addForm.pickType,
    sourceSalesId: addForm.sourceSalesId ? Number(addForm.sourceSalesId) : undefined,
    remark: addForm.remark || undefined,
    details: addForm.details.map(d => ({ goodsId: d.goodsId, quantity: d.quantity }))
  }
  submitting.value = true
  try {
    const res = await createPickListAPI(payload)
    if (res.code !== 200) throw new Error(res.msg || '创建失败')
    ElMessage.success('领料单已提交，待发料')
    addVisible.value = false
    loadList()
  } catch (e) {
    ElMessage.error(e.message || '创建失败')
  } finally {
    submitting.value = false
  }
}

const handleView = async (row) => {
  try {
    const res = await getPickListDetailAPI(row.id)
    if (res.code !== 200) throw new Error(res.msg || '查询失败')
    viewData.value = res.data
    viewVisible.value = true
  } catch (e) {
    ElMessage.error(e.message || '加载详情失败')
  }
}

const handleIssue = (row) => {
  ElMessageBox.confirm(`确认发料？${row.pickTypeText === '退料' ? '退料将回流入库' : '将扣减库存'}`, '确认', { type: 'warning' })
    .then(async () => {
      const res = await issuePickListAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '发料失败')
      ElMessage.success('发料成功')
      loadList()
    }).catch(() => {})
}

const handleConfirm = (row) => {
  ElMessageBox.confirm('确认已收到物料？', '确认收货', { type: 'warning' })
    .then(async () => {
      const res = await confirmPickListAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '确认失败')
      ElMessage.success('已确认收货')
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
    const res = await rejectPickListAPI(rejectForm.id, { reason: rejectForm.reason })
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
  ElMessageBox.confirm('确认撤销该领料申请？', '警告', { type: 'warning' })
    .then(async () => {
      const res = await deletePickListAPI(row.id)
      if (res.code !== 200) throw new Error(res.msg || '撤销失败')
      ElMessage.success('已撤销')
      loadList()
    }).catch(() => {})
}

onMounted(loadList)
</script>

<style scoped>
.pick-list-view { padding: 12px; }
.search-form { margin-bottom: 8px; }
.detail-line { line-height: 1.6; }
.pager { margin-top: 12px; justify-content: flex-end; }
.add-detail-btn { margin-top: 8px; }
</style>
