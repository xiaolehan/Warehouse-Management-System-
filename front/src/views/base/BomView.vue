<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item label="BOM编码">
        <el-input v-model="searchForm.bomCode" placeholder="请输入BOM编码" clearable />
      </el-form-item>
      <el-form-item label="成品名称">
        <el-input v-model="searchForm.goodsName" placeholder="请输入成品名称" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <el-button
          type="success" :icon="Plus" @click="handleAdd"
          v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
        >新建 BOM</el-button>
        <el-button
          type="warning" :icon="Upload" @click="openImport"
          v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
        >批量导入</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData" border style="width: 100%" v-loading="loading">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="bomCode" label="BOM编码" min-width="120" />
      <el-table-column prop="goodsName" label="成品名称" min-width="160" />
      <el-table-column prop="goodsUnit" label="单位" width="70" />
      <el-table-column label="物料条目" width="90">
        <template #default="scope">
          <el-tag size="small">{{ (scope.row.details || []).length }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip />
      <el-table-column prop="createTime" label="创建时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="scope">
          <el-button size="small" :icon="View" @click="handleView(scope.row)">查看</el-button>
          <el-button
            size="small" type="primary" :icon="Edit" @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >编辑</el-button>
          <el-button
            size="small" type="danger" :icon="Delete" @click="handleDelete(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >删除</el-button>
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

    <!-- 新建 / 编辑 / 查看 弹窗 -->
    <el-dialog :title="dialogTitle" v-model="dialogVisible" width="920px" top="6vh">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px" :disabled="isView">
        <el-form-item label="BOM编码" prop="bomCode">
          <el-input v-model="form.bomCode" placeholder="如 PTO153" :disabled="!!form.id" />
        </el-form-item>
        <el-form-item label="成品" prop="goodsId">
          <el-select
            v-model="form.goodsId" filterable placeholder="选择成品（type=product）" style="width: 320px"
            :disabled="!!form.id"
          >
            <el-option
              v-for="opt in productOptions" :key="opt.goodsId"
              :label="`${opt.goodsName}（${opt.unit || ''}）`" :value="opt.goodsId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="BOM 备注" />
        </el-form-item>

        <el-form-item label="BOM明细" class="detail-item">
          <div class="detail-box">
            <el-table :data="form.details" border size="small">
              <el-table-column label="组件/物料名称" min-width="160">
                <template #default="scope">
                  <el-input v-model="scope.row.componentName" placeholder="第{{ scope.$index + 1 }}行组件名称" :disabled="isView" />
                </template>
              </el-table-column>
              <el-table-column label="规格" min-width="100">
                <template #default="scope">
                  <el-input v-model="scope.row.spec" placeholder="规格" :disabled="isView" />
                </template>
              </el-table-column>
              <el-table-column label="材质" min-width="90">
                <template #default="scope">
                  <el-input v-model="scope.row.material" placeholder="材质" :disabled="isView" />
                </template>
              </el-table-column>
              <el-table-column label="单台用量" width="110">
                <template #default="scope">
                  <el-input-number
                    v-model="scope.row.quantity" :min="0" :precision="2" :controls="false"
                    style="width: 100px" placeholder="用量" :disabled="isView"
                  />
                </template>
              </el-table-column>
              <el-table-column label="关联物料" min-width="140">
                <template #default="scope">
                  <el-select
                    v-model="scope.row.goodsId" filterable clearable placeholder="可关联(参与齐套)" style="width: 100%"
                    :disabled="isView" @change="(val) => onLinkGoods(scope.row, val)"
                  >
                    <el-option
                      v-for="opt in materialOptions" :key="opt.goodsId"
                      :label="`${opt.goodsName}（${opt.unit || ''}）`" :value="opt.goodsId"
                    />
                  </el-select>
                </template>
              </el-table-column>
              <el-table-column label="参考行" width="70" align="center">
                <template #default="scope">
                  <el-switch v-model="scope.row.isReference" :disabled="isView" />
                </template>
              </el-table-column>
              <el-table-column label="备注" min-width="100">
                <template #default="scope">
                  <el-input v-model="scope.row.remark" placeholder="含外购标记等" :disabled="isView" />
                </template>
              </el-table-column>
              <el-table-column v-if="!isView" label="操作" width="60" align="center">
                <template #default="scope">
                  <el-button type="danger" link :icon="Remove" @click="removeDetail(scope.$index)" />
                </template>
              </el-table-column>
            </el-table>
            <el-button v-if="!isView" type="primary" plain :icon="Plus" style="margin-top: 8px;" @click="addDetail">
              添加明细行
            </el-button>
          </div>
        </el-form-item>
      </el-form>
      <template #footer v-if="!isView">
        <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Check" @click="handleSave">确认</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入弹窗 -->
    <el-dialog title="批量导入 BOM 明细" v-model="importVisible" width="640px">
      <el-form label-width="90px">
        <el-form-item label="BOM编码" required>
          <el-input v-model="importForm.bomCode" placeholder="如 PTO153" />
        </el-form-item>
        <el-form-item label="成品" required>
          <el-select v-model="importForm.goodsId" filterable placeholder="选择成品" style="width: 100%">
            <el-option
              v-for="opt in productOptions" :key="opt.goodsId"
              :label="`${opt.goodsName}（${opt.unit || ''}）`" :value="opt.goodsId"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="明细">
          <el-input
            v-model="importForm.text" type="textarea" :rows="10"
            placeholder="从 Excel 复制后粘贴，每行一个组件。用 Tab 分隔：组件名称	规格	数量	材质	备注（可只填前几列）"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :icon="Close" @click="importVisible = false">取消</el-button>
        <el-button type="primary" :icon="Check" @click="confirmImport">解析并确认</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, View, Edit, Delete, Close, Check, Remove, Upload } from '@element-plus/icons-vue'
import {
  createBomAPI,
  deleteBomAPI,
  getBomDetailAPI,
  getBomPageAPI,
  getGoodsMaterialOptionsAPI,
  getGoodsProductOptionsAPI,
  updateBomAPI
} from '@/api/base'

const searchForm = reactive({ bomCode: '', goodsName: '' })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const productOptions = ref([])
const materialOptions = ref([])

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isView = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, bomCode: '', goodsId: null, remark: '', details: [] })

const importVisible = ref(false)
const importForm = reactive({ bomCode: '', goodsId: null, text: '' })

const rules = {
  bomCode: [{ required: true, message: '请输入BOM编码', trigger: 'blur' }],
  goodsId: [{ required: true, message: '请选择成品', trigger: 'change' }]
}

const newDetailRow = () => ({
  goodsId: null,
  componentName: '',
  spec: '',
  quantity: null,
  material: '',
  remark: '',
  isReference: false
})

const addDetail = () => {
  form.details.push(newDetailRow())
}

const removeDetail = (index) => {
  if (form.details.length <= 1) return
  form.details.splice(index, 1)
}

// 选择关联物料时自动带出组件名/规格
const onLinkGoods = (row, val) => {
  if (val) {
    const opt = materialOptions.value.find((o) => o.goodsId === val)
    if (opt) {
      row.componentName = row.componentName || opt.goodsName
    }
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      bomCode: searchForm.bomCode || undefined,
      goodsName: searchForm.goodsName || undefined
    }
    const res = await getBomPageAPI(params)
    if (res.code !== 200) {
      throw new Error(res.msg || 'BOM 查询失败')
    }
    const pageData = res.data || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
  } catch (error) {
    ElMessage.error(error.message || '加载 BOM 失败')
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  try {
    const [p, m] = await Promise.all([getGoodsProductOptionsAPI(), getGoodsMaterialOptionsAPI()])
    productOptions.value = (p.data || []).map(normalizeOpt)
    materialOptions.value = (m.data || []).map(normalizeOpt)
  } catch (error) {
    ElMessage.error(error.message || '加载成品/物料选项失败')
  }
}

const normalizeOpt = (item) => ({
  goodsId: item.id,
  goodsName: item.goodsName,
  unit: item.unit
})

const handleSearch = () => {
  currentPage.value = 1
  loadList()
}

const resetSearch = () => {
  searchForm.bomCode = ''
  searchForm.goodsName = ''
  currentPage.value = 1
  loadList()
}

const handleSizeChange = (val) => {
  pageSize.value = val
  currentPage.value = 1
  loadList()
}

const handleCurrentChange = (val) => {
  currentPage.value = val
  loadList()
}

const initForm = () => {
  form.id = null
  form.bomCode = ''
  form.goodsId = null
  form.remark = ''
  form.details = [newDetailRow()]
}

const handleAdd = () => {
  isView.value = false
  dialogTitle.value = '新建 BOM'
  initForm()
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const openByDetail = async (row, viewMode) => {
  const res = await getBomDetailAPI(row.id)
  if (res.code !== 200) {
    throw new Error(res.msg || 'BOM 详情查询失败')
  }
  const d = res.data || {}
  isView.value = viewMode
  dialogTitle.value = viewMode ? 'BOM 详情（仅查看）' : '编辑 BOM'
  form.id = d.id
  form.bomCode = d.bomCode || ''
  form.goodsId = d.goodsId || null
  form.remark = d.remark || ''
  form.details = (d.details && d.details.length)
    ? d.details.map((x) => ({
        goodsId: x.goodsId || null,
        componentName: x.componentName || '',
        spec: x.spec || '',
        quantity: x.quantity == null ? null : Number(x.quantity),
        material: x.material || '',
        remark: x.remark || '',
        isReference: x.isReference === 1
      }))
    : [newDetailRow()]
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    await openByDetail(row, true)
  } catch (error) {
    ElMessage.error(error.message || '加载 BOM 详情失败')
  }
}

const handleEdit = async (row) => {
  try {
    await openByDetail(row, false)
  } catch (error) {
    ElMessage.error(error.message || '加载 BOM 详情失败')
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确认删除 BOM「${row.bomCode}」？`, '警告', { type: 'warning' })
    .then(async () => {
      const res = await deleteBomAPI(row.id)
      if (res.code !== 200) {
        throw new Error(res.msg || '删除失败')
      }
      ElMessage.success('删除成功')
      await loadList()
    })
    .catch(() => {})
}

const buildPayload = () => {
  const validDetails = form.details.filter((x) => x.componentName && String(x.componentName).trim())
  return {
    bomCode: form.bomCode,
    goodsId: form.goodsId,
    remark: form.remark || '',
    details: validDetails.map((x) => ({
      goodsId: x.goodsId || null,
      componentName: x.componentName,
      spec: x.spec || '',
      quantity: x.quantity,
      material: x.material || '',
      remark: x.remark || '',
      isReference: !!x.isReference
    }))
  }
}

const handleSave = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    const validCount = form.details.filter((x) => x.componentName && String(x.componentName).trim()).length
    if (!validCount) {
      ElMessage.warning('请至少填写一行组件/物料明细')
      return
    }
    for (let i = 0; i < form.details.length; i++) {
      const row = form.details[i]
      if (row.componentName && String(row.componentName).trim() && (row.quantity == null)) {
        ElMessage.warning(`第 ${i + 1} 行「${row.componentName}」缺少单台用量`)
        return
      }
    }
    try {
      const payload = buildPayload()
      const res = form.id ? await updateBomAPI(form.id, payload) : await createBomAPI(payload)
      if (res.code !== 200) {
        throw new Error(res.msg || '保存失败')
      }
      ElMessage.success(form.id ? '修改成功' : '新增成功')
      dialogVisible.value = false
      await loadList()
    } catch (error) {
      ElMessage.error(error.message || '保存失败')
    }
  })
}

// ============ 批量导入 ============
const openImport = () => {
  importForm.bomCode = ''
  importForm.goodsId = null
  importForm.text = ''
  importVisible.value = true
}

const confirmImport = () => {
  if (!importForm.bomCode || !String(importForm.bomCode).trim()) {
    ElMessage.warning('请输入 BOM 编码')
    return
  }
  if (!importForm.goodsId) {
    ElMessage.warning('请选择成品')
    return
  }
  const lines = String(importForm.text || '').split(/\r?\n/).map((l) => l.trim()).filter(Boolean)
  if (!lines.length) {
    ElMessage.warning('请粘贴明细内容')
    return
  }
  const details = []
  for (const line of lines) {
    const parts = line.split(/\t|,|、/).map((p) => (p || '').trim())
    if (!parts[0]) continue
    details.push({
      goodsId: null,
      componentName: parts[0],
      spec: parts[1] || '',
      quantity: parts[2] == null || parts[2] === '' ? null : Number(parts[2]),
      material: parts[3] || '',
      remark: parts[4] || '',
      isReference: false
    })
  }
  if (!details.length) {
    ElMessage.warning('未解析到有效明细行')
    return
  }
  // 回填到新建弹窗再让用户确认
  isView.value = false
  dialogTitle.value = '新建 BOM（导入待确认）'
  initForm()
  form.bomCode = importForm.bomCode
  form.goodsId = importForm.goodsId
  form.details = details
  formRef.value?.clearValidate()
  importVisible.value = false
  dialogVisible.value = true
}

onMounted(() => {
  loadList()
  loadOptions()
})
</script>

<style scoped>
.detail-item {
  width: 100%;
}
.detail-item :deep(.el-form-item__content) {
  width: 100%;
}
.detail-box {
  width: 100%;
}
</style>