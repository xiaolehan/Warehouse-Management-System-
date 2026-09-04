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
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="scope">
          <el-button link size="small" type="primary" @click="handleView(scope.row)">查看</el-button>
          <el-button
            link size="small" type="warning" @click="handleExport(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >导出</el-button>
          <el-button
            link size="small" type="primary" @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >编辑</el-button>
          <el-button
            link size="small" type="danger" @click="handleDelete(scope.row)"
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
    <el-dialog :title="dialogTitle" v-model="dialogVisible" width="1040px" top="5vh">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px" :disabled="isView">
        <el-form-item label="成品名称" prop="goodsName">
          <el-input v-model="form.goodsName" placeholder="如 PTO153（一个 BOM 即一种成品）" :disabled="isView" style="width: 320px" />
        </el-form-item>
        <el-form-item label="单位" prop="unit">
          <el-input v-model="form.unit" placeholder="如 台（可空）" :disabled="isView" style="width: 200px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.remark" type="textarea" :rows="2" placeholder="BOM 备注" />
        </el-form-item>

        <el-form-item label="BOM明细" class="detail-item">
          <div class="detail-box">
            <el-table :data="form.details" border size="small">
              <el-table-column label="图片" width="150">
                <template #default="scope">
                  <div v-if="!isView" class="img-cell">
                    <el-upload
                      :http-request="uploadImageRequest" :show-file-list="false"
                      accept="image/*" :on-success="(res, uf) => onImageSuccess(scope.row, res, uf)"
                      :on-error="onImageError"
                    >
                      <el-button v-if="!scope.row.image" size="small" type="primary" plain :icon="Picture">上传图</el-button>
                      <template v-else>
                        <el-image :src="imgMap[scope.row.image] || ''" :preview-src-list="[imgMap[scope.row.image]].filter(Boolean)" fit="cover" style="width:46px;height:46px;border-radius:4px;display:inline-block;vertical-align:middle;" />
                      </template>
                    </el-upload>
                    <el-button v-if="scope.row.image" size="small" link type="danger" @click="scope.row.image = null">删</el-button>
                  </div>
                  <el-image
                    v-else-if="scope.row.image" :src="imgMap[scope.row.image] || ''"
                    :preview-src-list="[imgMap[scope.row.image]].filter(Boolean)" fit="cover"
                    style="width:46px;height:46px;border-radius:4px;"
                  />
                </template>
              </el-table-column>
              <el-table-column label="组件/物料名称" min-width="150">
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
              <el-table-column label="单台用量" width="100">
                <template #default="scope">
                  <el-input-number
                    v-model="scope.row.quantity" :min="0" :precision="2" :controls="false"
                    style="width: 100%" placeholder="用量" :disabled="isView"
                  />
                </template>
              </el-table-column>
              <el-table-column label="关联物料" min-width="130">
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
            <p class="tip" v-if="!isView">关联物料可选：未关联的明细行按「缺料待采购」计入齐套，待物料在仓库建档后再回挂。</p>
          </div>
        </el-form-item>
      </el-form>
      <template #footer v-if="!isView">
        <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Check" @click="handleSave">确认</el-button>
      </template>
    </el-dialog>

    <!-- 批量导入（真实 .xlsx） -->
    <el-dialog title="导入 BOM 明细" v-model="importVisible" width="560px">
      <el-form label-width="90px">
        <el-form-item label="成品名称" required>
          <el-input v-model="importForm.goodsName" placeholder="如 PTO153（匹配已有或自动新建成品）" />
        </el-form-item>
        <el-form-item label="BOM编码">
          <el-input v-model="importForm.bomCode" placeholder="如 PTO153-BOM（留空自动生成）" />
        </el-form-item>
        <el-form-item label="文件">
          <el-upload
            ref="importUploadRef" :http-request="importRequest"
            accept=".xlsx,.xls" :show-file-list="true" :limit="1"
            :before-upload="beforeImport" :on-success="onImportSuccess" :on-error="onImportError"
          >
            <el-button :icon="Upload">选择 .xlsx 文件</el-button>
          </el-upload>
          <p class="tip">
            模板列：序号/图片/组件名称/规格/数量/材质/备注。图片请新建后在明细里按行上传（本导入只读文字列）。导入会<strong>整体覆盖</strong>该成品已有明细。
            <el-link type="primary" :icon="Download" @click="downloadTemplate">下载空白模板</el-link>
          </p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :icon="Close" @click="importVisible = false">关闭</el-button>
      </template>
    </el-dialog>

    <!-- 查看 BOM 详情：右抽屉 + 头部描述 + 全字段明细表 -->
    <el-drawer v-model="detailVisible" title="BOM 详情" size="700px">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="BOM 编码">{{ detail.bomCode }}</el-descriptions-item>
          <el-descriptions-item label="成品名称">{{ detail.goodsName }}</el-descriptions-item>
          <el-descriptions-item label="单位">{{ detail.goodsUnit || '-' }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ detail.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="物料条目">
            <el-tag size="small">{{ (detail.details || []).length }} 行</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detail.remark || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-table :data="detail.details || []" border size="small" style="margin-top: 16px">
          <el-table-column type="index" label="序号" width="55" align="center" />
          <el-table-column label="图片" width="90">
            <template #default="scope">
              <el-image
                v-if="scope.row.image" :src="imgMap[scope.row.image] || ''"
                :preview-src-list="[imgMap[scope.row.image]].filter(Boolean)" fit="cover"
                style="width:46px;height:46px;border-radius:4px;"
              />
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column prop="componentName" label="组件/物料名称" min-width="130" />
          <el-table-column prop="spec" label="规格" min-width="80" show-overflow-tooltip />
          <el-table-column prop="material" label="材质" min-width="80" />
          <el-table-column prop="quantity" label="单台用量" width="90" align="center" />
          <el-table-column label="关联物料" min-width="110">
            <template #default="scope">{{ materialName(scope.row.goodsId) }}</template>
          </el-table-column>
          <el-table-column label="参考行" width="70" align="center">
            <template #default="scope">{{ scope.row.isReference === 1 ? '是' : '否' }}</template>
          </el-table-column>
          <el-table-column prop="remark" label="备注" min-width="90" show-overflow-tooltip />
          <el-table-column prop="createTime" label="明细创建时间" width="160" />
        </el-table>
      </template>
    </el-drawer>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Close, Check, Remove, Upload, Download, Picture } from '@element-plus/icons-vue'
import request from '@/utils/request'
import {
  createBomAPI,
  deleteBomAPI,
  getBomDetailAPI,
  getBomExportAPI,
  getBomPageAPI,
  getBomTemplateAPI,
  getGoodsMaterialOptionsAPI,
  updateBomAPI
} from '@/api/base'

const searchForm = reactive({ bomCode: '', goodsName: '' })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const materialOptions = ref([])

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isView = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, goodsName: '', unit: '', remark: '', details: [] })

const detailVisible = ref(false)
const detail = ref(null)

const importVisible = ref(false)
const importUploadRef = ref(null)
const importForm = reactive({ bomCode: '', goodsName: '' })

// 图片/导入上传均走应用 axios（request.js 自动带 Bearer 头），见 uploadImageRequest / importRequest

const rules = {
  goodsName: [{ required: true, message: '请输入成品名称', trigger: 'blur' }]
}

// 受保护图片：经 axios 拉 blob 建 objectURL 再展示（<img> 直连带不了 Bearer 头，会拿到 401 JSON）
const imgMap = reactive({})
const loadImg = async (path) => {
  if (!path || imgMap[path]) return
  try {
    const blob = await request.get('/base/bom/image', { params: { path }, responseType: 'blob' })
    // 鉴权失败时后端是 HTTP 200 + JSON body，依 content-type 识别
    if (blob && blob.type === 'application/json') {
      imgMap[path] = ''
      return
    }
    imgMap[path] = URL.createObjectURL(blob)
  } catch (e) {
    /* 视图仍为空，不打断流程 */
  }
}
const preloadImgs = (list) => (list || []).forEach((d) => { if (d && d.image) loadImg(d.image) })

const newDetailRow = () => ({
  goodsId: null,
  componentName: '',
  spec: '',
  quantity: null,
  material: '',
  image: null,
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

// 明细行组件图片上传：走应用 axios（自动带 Bearer 头）
// 注意：不要 async（async 必返回 Promise）——el-upload 拿到 Promise 会再自动调一次
// onSuccess/onError（options.then），导致「又提示成功又提示失败」。这里自身只调一次回调。
const uploadImageRequest = (options) => {
  const fd = new FormData()
  fd.append('file', options.file)
  request.post('/base/bom/image', fd)
    .then((body) => options.onSuccess(body))
    .catch((e) => options.onError(e))
}

// 明细行组件图片上传成功
const onImageSuccess = (row, res) => {
  if (res && res.code === 200 && res.data?.path) {
    row.image = res.data.path
    loadImg(res.data.path)
    ElMessage.success('图片上传成功')
  } else {
    ElMessage.error(res?.msg || '图片上传失败')
  }
}
const onImageError = () => ElMessage.error('图片上传失败')

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
    const m = await getGoodsMaterialOptionsAPI()
    materialOptions.value = (m.data || []).map(normalizeOpt)
  } catch (error) {
    ElMessage.error(error.message || '加载物料选项失败')
  }
}

// 明细行关联物料名称（查看抽屉用）
const materialName = (goodsId) => {
  if (goodsId == null || goodsId === '') return '-'
  const opt = materialOptions.value.find((o) => o.goodsId === goodsId)
  return opt ? opt.goodsName : '-'
}

// GoodsOptionVO 的显式名为 "name"（id/name/stock/unit/salePrice/type）
const normalizeOpt = (item) => ({
  goodsId: item.id,
  goodsName: item.name || item.goodsName,
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
  form.goodsName = ''
  form.unit = ''
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

const loadBom = async (row) => {
  const res = await getBomDetailAPI(row.id)
  if (res.code !== 200) {
    throw new Error(res.msg || 'BOM 详情查询失败')
  }
  return res.data || {}
}

// 查看详情：填充到右抽屉
const handleView = async (row) => {
  try {
    const d = await loadBom(row)
    detail.value = d
    detailVisible.value = true
    preloadImgs(d.details)
  } catch (error) {
    ElMessage.error(error.message || '加载 BOM 详情失败')
  }
}

// 编辑：填充新建/编辑弹窗
const handleEdit = async (row) => {
  try {
    const d = await loadBom(row)
    isView.value = false
    dialogTitle.value = '编辑 BOM'
    form.id = d.id
    form.goodsName = d.goodsName || ''
    form.unit = d.goodsUnit || ''
    form.remark = d.remark || ''
    form.details = (d.details && d.details.length)
      ? d.details.map((x) => ({
          goodsId: x.goodsId || null,
          componentName: x.componentName || '',
          spec: x.spec || '',
          quantity: x.quantity == null ? null : Number(x.quantity),
          material: x.material || '',
          image: x.image || null,
          remark: x.remark || '',
          isReference: x.isReference === 1
        }))
      : [newDetailRow()]
    preloadImgs(form.details)
    formRef.value?.clearValidate()
    dialogVisible.value = true
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
    goodsName: form.goodsName,
    unit: form.unit || '',
    remark: form.remark || '',
    details: validDetails.map((x) => ({
      goodsId: x.goodsId || null,
      componentName: x.componentName,
      spec: x.spec || '',
      quantity: x.quantity,
      material: x.material || '',
      image: x.image || null,
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

// ============ 导出 / 模板 / 导入 ============
const downloadBlob = (blob, filename) => {
  const url = window.URL.createObjectURL(new Blob([blob]))
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

const handleExport = async (row) => {
  try {
    const blob = await getBomExportAPI(row.id)
    downloadBlob(blob, `${row.bomCode}.xlsx`)
  } catch (error) {
    ElMessage.error(error.message || '导出失败')
  }
}

const downloadTemplate = async () => {
  try {
    const blob = await getBomTemplateAPI()
    downloadBlob(blob, 'BOM导入模板.xlsx')
  } catch (error) {
    ElMessage.error(error.message || '模板下载失败')
  }
}

const openImport = () => {
  importForm.bomCode = ''
  importForm.goodsName = ''
  importUploadRef.value?.clearFiles()
  importVisible.value = true
}

const beforeImport = () => {
  if (!importForm.goodsName || !String(importForm.goodsName).trim()) {
    ElMessage.warning('请先填写成品名称')
    return false
  }
  return true
}

// xlsx 导入：走应用 axios，自动带表单参数与 Bearer 头（同样不要 async，见上方注释）
const importRequest = (options) => {
  const fd = new FormData()
  fd.append('file', options.file)
  fd.append('goodsName', String(importForm.goodsName).trim())
  if (importForm.bomCode) fd.append('bomCode', String(importForm.bomCode).trim())
  request.post('/base/bom/import', fd)
    .then((body) => options.onSuccess(body))
    .catch((e) => options.onError(e))
}

const onImportSuccess = (res) => {
  if (res && res.code === 200) {
    ElMessage.success(`导入成功，共 ${res.data?.imported || 0} 行明细（已覆盖该成品 BOM）`)
    importVisible.value = false
    importUploadRef.value?.clearFiles()
    loadList()
  } else {
    ElMessage.error(res?.msg || '导入失败')
    importUploadRef.value?.clearFiles()
  }
}

const onImportError = () => {
  ElMessage.error('导入失败，请检查文件格式')
  importUploadRef.value?.clearFiles()
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
.tip {
  font-size: 12px;
  color: #909399;
  margin: 6px 0 0;
  line-height: 1.5;
}
.img-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
</style>