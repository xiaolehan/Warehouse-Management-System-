<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item :label="isProduct ? '成品名称' : '物料名称'">
        <el-input v-model="searchForm.goodsName" :placeholder="isProduct ? '请输入成品名称' : '请输入物料名称'" clearable />
      </el-form-item>
      <template v-if="!isProduct">
        <el-form-item label="产品名称">
          <el-input v-model="searchForm.productName" placeholder="请输入产品名称" clearable />
        </el-form-item>
        <el-form-item label="物料种类">
          <el-input v-model="searchForm.category" placeholder="请输入物料种类" clearable />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="searchForm.supplierId" placeholder="请选择供应商" clearable style="width: 180px;">
            <el-option v-for="sup in suppliers" :key="sup.id" :label="sup.name" :value="sup.id" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <el-button v-if="isWarehouse" type="success" :icon="Plus" @click="handleAdd"
          v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">{{ isProduct ? '新增成品' : '新增物料' }}</el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData" border style="width: 100%" v-loading="loading">
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="goodsName" :label="isProduct ? '成品名称' : '物料名称'" min-width="130" />
      <!-- D65 成品管理页：名称/单位/规格/库存/备注/创建来源；物料页维持原字段 -->
      <el-table-column v-if="isProduct" prop="unit" label="单位" width="90">
        <template #default="scope">{{ scope.row.unit || '—' }}</template>
      </el-table-column>
      <el-table-column prop="spec" label="规格" min-width="100">
        <template #default="scope">{{ scope.row.spec || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="!isProduct" prop="material" label="材质" min-width="100">
        <template #default="scope">{{ scope.row.material || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="!isProduct" prop="productName" label="产品名称" min-width="110" />
      <el-table-column v-if="!isProduct" prop="category" label="物料种类" min-width="90" />
      <el-table-column prop="description" label="备注" min-width="110">
        <template #default="scope">{{ scope.row.description || '—' }}</template>
      </el-table-column>
      <el-table-column v-if="!isProduct" prop="supplierName" label="所属供应商" min-width="140" />
      <!-- 进价：仅供采购/超管可见，仓储隐藏；成品无进价概念 -->
      <el-table-column v-if="showPrice && !isProduct" prop="price" label="进价" width="100">
        <template #default="scope">{{ scope.row.price ?? '-' }}</template>
      </el-table-column>
      <el-table-column prop="stock" label="当前库存" width="100">
        <template #default="scope">
          <!-- D65：成品不参与库存预警，标签恒为中性色 -->
          <el-tag v-if="isProduct" type="info">{{ scope.row.stock }}</el-tag>
          <el-tag v-else :type="scope.row.stock <= (scope.row.warningStock ?? 10) ? 'danger' : 'success'">{{ scope.row.stock }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column v-if="isWarehouse && !isProduct" prop="warningStock" label="预警阈值" width="100" />
      <el-table-column v-if="isProduct" label="创建来源" width="130">
        <template #default="scope">
          <!-- 以 goodsCode 前缀判别（PRD=BOM 建档 / GD=手工建档），不靠备注文案匹配 -->
          <el-tag v-if="(scope.row.goodsCode || '').startsWith('PRD')" type="info">BOM 建档生成</el-tag>
          <el-tag v-else type="success">手工建档</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="180" fixed="right">
        <template #default="scope">
          <el-button link size="small" type="primary" @click="handleView(scope.row)">详情</el-button>
          <el-button v-if="!isProduct && (isWarehouse || isPurchase)" link size="small" type="success"
            @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin', 'employee'], deptCodes: ['warehouse', 'purchase'] }">{{ isPurchase ? '进价编辑' : '编辑' }}</el-button>
          <el-button v-else-if="isWarehouse" link size="small" type="success" @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">编辑</el-button>
          <el-button link size="small" type="danger" @click="handleDelete(scope.row)"
            v-if="isWarehouse"
            v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">删除</el-button>
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

    <el-dialog :title="dialogTitle" v-model="dialogVisible" width="550px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="100px" :disabled="isView">
        <el-form-item :label="isProduct ? '成品名称' : '物料名称'" required>
          <el-input v-model="form.goodsName" :disabled="isView || (!isProduct && isPurchase)"></el-input>
        </el-form-item>
        <template v-if="!isProduct">
          <el-form-item label="产品名称">
            <el-input v-model="form.productName" :disabled="isView || isPurchase"></el-input>
          </el-form-item>
          <el-form-item label="物料种类" required>
            <el-input v-model="form.category" :disabled="isView || isPurchase"></el-input>
          </el-form-item>
          <el-form-item label="供应商" required>
            <el-select v-model="form.supplierId" placeholder="请绑定供应商" style="width: 100%;" :disabled="isView || isPurchase">
              <el-option v-for="sup in suppliers" :key="sup.id" :label="sup.name" :value="sup.id" />
            </el-select>
          </el-form-item>
        </template>
        <el-form-item label="单位">
          <el-input v-model="form.unit" :disabled="isView || (!isProduct && isPurchase)"></el-input>
        </el-form-item>
        <!-- 规格/材质：物料固有属性(ADR-0003)，物料按「名称+规格」唯一，防同名不同规格混选；成品规格选填 -->
        <el-form-item label="规格">
          <el-input v-model="form.spec" :disabled="isView || (!isProduct && isPurchase)"></el-input>
        </el-form-item>
        <el-form-item v-if="!isProduct" label="材质">
          <el-input v-model="form.material" :disabled="isView || isPurchase"></el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="2" :disabled="isView || (!isProduct && isPurchase)"></el-input>
        </el-form-item>
        <!-- 进价(采购管)：仓储新增/编辑一律不显示；采购编辑/双方查看可见可改；成品无进价 -->
        <el-form-item v-if="showPrice && !isWarehouse && !isProduct" label="进价" required>
          <el-input-number v-model="form.purchasePrice" :min="0.01" :precision="2" :step="0.1" style="width: 100%;" />
        </el-form-item>
        <!-- 初始库存(仓储建) -->
        <el-form-item v-if="!isView && isAddMode && isWarehouse" label="初始库存">
          <el-input-number v-model="form.stock" :min="0" />
        </el-form-item>
        <!-- 当前库存：仓储编辑可改；采购编辑/双方查看只读 -->
        <el-form-item v-if="(!isView && !isAddMode && !isPurchase) || isView" label="当前库存">
          <el-input-number v-model="form.stock" :min="0" :disabled="isView || isPurchase" style="width: 100%;" />
        </el-form-item>
        <!-- 预警阈值(仓储管)：仓储编辑/查看可见，采购不显示；成品不参与预警 -->
        <el-form-item v-if="isWarehouse && !isProduct" label="预警阈值">
          <el-input-number v-model="form.warningStock" :min="0" style="width: 100%;" />
        </el-form-item>
      </el-form>
      <template #footer v-if="!isView">
        <el-button :icon="Close" @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :icon="Check" @click="handleSave">确认</el-button>
      </template>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, View, Edit, Delete, Close, Check } from '@element-plus/icons-vue'
import { getDeptCode, getRole, isSuperAdmin } from '@/utils/auth'
import {
  createGoodsAPI,
  deleteGoodsAPI,
  getGoodsDetailAPI,
  getGoodsPageAPI,
  getSupplierOptionsAPI,
  updateGoodsAPI
} from '@/api/base'

// D65 主数据分域：同一组件按路由 goodsType 渲染物料/成品两页（/base/goods → material，/base/products → product）
const props = defineProps({
  goodsType: { type: String, default: 'material' }
})
const isProduct = computed(() => props.goodsType === 'product')
const goodsNoun = computed(() => (isProduct.value ? '成品' : '物料'))

// D35 角色职责：仓储 admin=建/删/管库存(无价格)；采购(admin/员工)=看库存/改进价；成品仅仓储可改
const userRole = getRole()
const userDept = getDeptCode()
const isWarehouse = userRole === 'admin' && userDept === 'warehouse'
const isPurchase = userDept === 'purchase' && (userRole === 'admin' || userRole === 'employee')
const showPrice = isPurchase || isSuperAdmin(userRole)

const suppliers = ref([])

const searchForm = reactive({ goodsName: '', productName: '', category: '', supplierId: null })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isView = ref(false)
const isAddMode = ref(false)
const formRef = ref(null)
const form = reactive({
  id: null,
  goodsName: '',
  productName: '',
  category: '',
  supplierId: null,
  purchasePrice: null,
  unit: '',
  spec: '',
  material: '',
  description: '',
  stock: 0,
  warningStock: 10
})

const rules = computed(() => {
  if (isProduct.value) {
    return {
      goodsName: [{ required: true, message: '请输入成品名称', trigger: 'blur' }]
    }
  }
  return {
    goodsName: [{ required: true, message: '请输入物料名称', trigger: 'blur' }],
    category: [{ required: true, message: '请输入物料种类', trigger: 'blur' }],
    supplierId: [{ required: true, message: '请选择供应商', trigger: 'change' }]
  }
})

const loadSuppliers = async () => {
  try {
    const res = await getSupplierOptionsAPI()
    if (res.code !== 200) {
      throw new Error(res.msg || '供应商下拉加载失败')
    }
    suppliers.value = res.data || []
  } catch (error) {
    ElMessage.error(error.message || '供应商下拉加载失败')
  }
}

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      // D65：按 goodsType 分域拉取，物料页/成品页各查各的
      type: props.goodsType,
      goodsName: searchForm.goodsName || undefined,
      productName: searchForm.productName || undefined,
      category: searchForm.category || undefined,
      supplierId: searchForm.supplierId || undefined
    }
    const res = await getGoodsPageAPI(params)
    if (res.code !== 200) {
      throw new Error(res.msg || `${goodsNoun.value}查询失败`)
    }
    const pageData = res.data || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
  } catch (error) {
    ElMessage.error(error.message || `加载${goodsNoun.value}失败`)
  } finally {
    loading.value = false
  }
}

const handleSearch = () => {
  currentPage.value = 1
  loadList()
}

const resetSearch = () => {
  searchForm.goodsName = ''
  searchForm.productName = ''
  searchForm.category = ''
  searchForm.supplierId = null
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
  form.productName = ''
  form.category = ''
  form.supplierId = null
  form.purchasePrice = null
  form.unit = ''
  form.spec = ''
  form.material = ''
  form.description = ''
  form.stock = 0
  form.warningStock = 10
}

const handleAdd = () => {
  isView.value = false
  isAddMode.value = true
  dialogTitle.value = isProduct.value ? '新增成品' : '新增物料'
  initForm()
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const openByDetail = async (row, viewMode) => {
  const res = await getGoodsDetailAPI(row.id)
  if (res.code !== 200) {
    throw new Error(res.msg || `${goodsNoun.value}详情查询失败`)
  }
  const detail = res.data || {}
  isView.value = viewMode
  isAddMode.value = false
  dialogTitle.value = viewMode ? `${goodsNoun.value}详情` : `编辑${goodsNoun.value}`
  Object.assign(form, {
    id: detail.id,
    goodsName: detail.goodsName || '',
    productName: detail.productName || '',
    category: detail.category || '',
    supplierId: detail.supplierId || null,
    purchasePrice: detail.purchasePrice ?? null,
    unit: detail.unit || '',
    spec: detail.spec || '',
    material: detail.material || '',
    description: detail.description || '',
    stock: detail.stock || 0,
    warningStock: detail.warningStock ?? 10
  })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    await openByDetail(row, true)
  } catch (error) {
    ElMessage.error(error.message || `加载${goodsNoun.value}详情失败`)
  }
}

const handleEdit = async (row) => {
  try {
    await openByDetail(row, false)
  } catch (error) {
    ElMessage.error(error.message || `加载${goodsNoun.value}详情失败`)
  }
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除该${goodsNoun.value}资料?`, '警告', { type: 'warning' })
    const res = await deleteGoodsAPI(row.id)
    if (res.code !== 200) {
      throw new Error(res.msg || '删除失败')
    }
    ElMessage.success('删除成功')
    await loadList()
  } catch (error) {
    // 用户取消（'cancel'）不提示；后端拒绝（如有库存/被单据引用）须展示其 message
    if (error !== 'cancel' && error?.message) {
      ElMessage.error(error.message)
    }
  }
}

const handleSave = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      let payload
      if (isProduct.value) {
        // D65：成品仅仓储可改，只传名称/单位/规格/备注/库存（供应商/预警/种类由后端定死，防误传）
        payload = {
          goodsName: form.goodsName,
          unit: form.unit,
          spec: form.spec,
          description: form.description,
          stock: form.stock,
          type: 'product'
        }
      } else if (form.id && isPurchase) {
        // D35.2 采购只改单价
        payload = { purchasePrice: form.purchasePrice }
      } else {
        // 仓储新增/编辑：基本字段+初始库存/预警，无价格
        payload = {
          goodsName: form.goodsName,
          productName: form.productName,
          category: form.category,
          supplierId: form.supplierId,
          unit: form.unit,
          spec: form.spec,
          material: form.material,
          description: form.description,
          stock: form.stock,
          warningStock: form.warningStock,
          type: 'material'
        }
      }
      // status 仅新增时置 1；编辑不传——后端 null 保留原值，防编辑误激活已停用条目
      if (!form.id) {
        payload.status = 1
      }
      const res = form.id ? await updateGoodsAPI(form.id, payload) : await createGoodsAPI(payload)
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

// 同组件双路由（/base/goods 物料、/base/products 成品）：路由切换时组件不重挂载，
// 须监听 goodsType 重新拉取，否则残留旧域数据
watch(() => props.goodsType, async () => {
  resetSearch()
  if (!isProduct.value) {
    await loadSuppliers()
  }
})

onMounted(async () => {
  if (!isProduct.value) {
    await loadSuppliers()
  }
  await loadList()
})
</script>
