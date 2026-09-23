<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item :label="isProduct ? '成品名称' : '物料名称'">
        <el-input v-model="searchForm.goodsName" :placeholder="isProduct ? '请输入成品名称' : '请输入物料名称'" clearable />
      </el-form-item>
      <template v-if="!isProduct">
        <el-form-item label="成品名称">
          <el-input v-model="searchForm.productName" placeholder="请输入成品名称" clearable />
        </el-form-item>
        <el-form-item label="物料种类">
          <el-input v-model="searchForm.category" placeholder="请输入物料种类" clearable />
        </el-form-item>
        <el-form-item label="供应商">
          <el-select v-model="searchForm.supplierId" placeholder="请选择供应商" clearable filterable style="width: 180px;">
            <el-option v-for="sup in suppliers" :key="sup.id" :label="sup.name" :value="sup.id" />
          </el-select>
        </el-form-item>
      </template>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <el-button v-if="isWarehouse" type="success" :icon="Plus" @click="handleAdd"
          v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">{{ isProduct ? '新增成品' : '新增物料' }}</el-button>
        <el-button v-if="isWarehouse" type="danger" :icon="Delete" :disabled="selectedRows.length === 0" @click="handleBatchDelete"
          v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">
          批量删除{{ selectedRows.length > 0 ? `（${selectedRows.length}）` : '' }}
        </el-button>
      </el-form-item>
    </el-form>

    <el-table :data="tableData" border style="width: 100%" v-loading="loading" @selection-change="handleSelectionChange">
      <el-table-column type="selection" width="46" />
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
      <el-table-column v-if="!isProduct" prop="productName" label="成品名称" min-width="110" />
      <el-table-column v-if="!isProduct" prop="category" label="物料种类" min-width="90" />
      <el-table-column prop="description" label="备注" min-width="110">
        <template #default="scope">{{ scope.row.description || '—' }}</template>
      </el-table-column>
      <!-- D123：所属供应商列升级为「最新供应商」——最近一张已入库+正常进货单的头级供应商；
           无进货记录回退绑定供应商并标「默认」。D109 待匹配 tag 保留（按绑定关系判断） -->
      <el-table-column v-if="!isProduct" label="最新供应商" min-width="180">
        <template #default="scope">
          <span>{{ scope.row.latestSupplierName || scope.row.supplierName || '—' }}</span>
          <el-tag v-if="scope.row.latestSupplierDefault" size="small" type="info" effect="plain" style="margin-left: 6px">默认</el-tag>
          <el-tag v-if="scope.row.supplierId === DEFAULT_SUPPLIER_ID" size="small" type="warning" effect="plain" style="margin-left: 6px">待匹配</el-tag>
        </template>
      </el-table-column>
      <!-- 进价：仅供采购/超管可见，仓储隐藏；成品无进价概念；「历史」弹窗展示该物料全部有效已入库采购记录（D102） -->
      <el-table-column v-if="showPrice && !isProduct" prop="price" label="进价" width="130">
        <template #default="scope">
          <span>{{ scope.row.price ?? '-' }}</span>
          <el-button link size="small" type="primary" @click="openPriceHistory(scope.row)">历史</el-button>
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="当前库存" width="100">
        <template #default="scope">
          <!-- D65：成品不参与库存预警，标签恒为中性色 -->
          <el-tag v-if="isProduct" type="info">{{ scope.row.stock }}</el-tag>
          <el-tag v-else :type="scope.row.stock <= (scope.row.warningStock ?? 10) ? 'danger' : 'success'">{{ scope.row.stock }}</el-tag>
        </template>
      </el-table-column>
      <!-- D126 售价(销售管)：仅销售部门成员+超管可见（后端同步脱敏）；物料无售价概念 -->
      <el-table-column v-if="isProduct && showSalePrice" prop="salePrice" label="标准售价" width="110">
        <template #default="scope">{{ scope.row.salePrice ?? '—' }}</template>
      </el-table-column>
      <el-table-column v-if="isWarehouse && !isProduct" prop="warningStock" label="预警阈值" width="100" />
      <el-table-column v-if="isProduct" label="创建来源" width="130">
        <template #default="scope">
          <!-- 以 goodsCode 前缀判别（PRD=BOM 建档 / GD=手工建档），不靠备注文案匹配 -->
          <el-tag v-if="(scope.row.goodsCode || '').startsWith('PRD')" type="info">BOM 建档生成</el-tag>
          <el-tag v-else type="success">手工建档</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="240" fixed="right">
        <template #default="scope">
          <el-button link size="small" type="primary" @click="handleView(scope.row)">详情</el-button>
          <el-button v-if="!isProduct && (isWarehouse || isPurchase)" link size="small" type="success"
            @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin', 'employee'], deptCodes: ['warehouse', 'purchase'] }">{{ isPurchase ? '进价编辑' : '编辑' }}</el-button>
          <!-- D68：销售部门成品售价编辑（镜像采购进价编辑范式） -->
          <el-button v-else-if="isProduct && isSales" link size="small" type="success" @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin', 'employee'], deptCodes: ['sales'] }">售价编辑</el-button>
          <el-button v-else-if="isWarehouse" link size="small" type="success" @click="handleEdit(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">编辑</el-button>
          <!-- D109：未知物料一键匹配供应商（仅仍挂系统默认供应商的物料，仅仓储管理员） -->
          <el-button v-if="!isProduct && isWarehouse && scope.row.supplierId === DEFAULT_SUPPLIER_ID" link size="small" type="warning"
            @click="handleMatchSupplier(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['warehouse'] }">匹配供应商</el-button>
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
          <el-input v-model="form.goodsName" :disabled="isView || (!isProduct && isPurchase) || isSalesPriceMode"></el-input>
        </el-form-item>
        <template v-if="!isProduct">
          <el-form-item label="成品名称">
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
          <!-- D123：最新供应商只读展示（详情/编辑态均可见，编辑不影响） -->
          <el-form-item label="最新供应商">
            <el-input :value="form.latestSupplierName || '—'" disabled>
              <template #append v-if="form.latestSupplierDefault">默认</template>
            </el-input>
          </el-form-item>
        </template>
        <el-form-item label="单位">
          <el-input v-model="form.unit" :disabled="isView || (!isProduct && isPurchase) || isSalesPriceMode"></el-input>
        </el-form-item>
        <!-- 规格/材质：物料固有属性(ADR-0003)，物料按「名称+规格」唯一，防同名不同规格混选；成品规格选填 -->
        <el-form-item label="规格">
          <el-input v-model="form.spec" :disabled="isView || (!isProduct && isPurchase) || isSalesPriceMode"></el-input>
        </el-form-item>
        <el-form-item v-if="!isProduct" label="材质">
          <el-input v-model="form.material" :disabled="isView || isPurchase"></el-input>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="form.description" type="textarea" :rows="2" :disabled="isView || (!isProduct && isPurchase) || isSalesPriceMode"></el-input>
        </el-form-item>
        <!-- 进价(采购管)：仓储新增/编辑一律不显示；采购编辑/双方查看可见可改；成品无进价 -->
        <el-form-item v-if="showPrice && !isWarehouse && !isProduct" label="进价" required>
          <el-input-number v-model="form.purchasePrice" :min="0.01" :precision="2" :step="0.1" style="width: 100%;" />
        </el-form-item>
        <!-- D126 售价(销售管)：销售编辑成品可改（D68）；仅销售部门成员+超管可见（查看态同样裁剪） -->
        <el-form-item v-if="isProduct && showSalePrice && ((isSales && !isAddMode) || isView)" label="标准售价" :required="isSales && !isView">
          <el-input-number v-model="form.salePrice" :min="0.01" :precision="2" :step="1" style="width: 100%;" :disabled="isView" />
        </el-form-item>
        <!-- 初始库存(仓储建) -->
        <el-form-item v-if="!isView && isAddMode && isWarehouse" label="初始库存">
          <el-input-number v-model="form.stock" :min="0" />
        </el-form-item>
        <!-- 当前库存：仓储编辑可改；采购编辑/双方查看只读；销售售价编辑态只读 -->
        <el-form-item v-if="(!isView && !isAddMode && !isPurchase) || isView" label="当前库存">
          <el-input-number v-model="form.stock" :min="0" :disabled="isView || isPurchase || isSalesPriceMode" style="width: 100%;" />
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

    <!-- D102：进价历史弹窗——最近在上的有效已入库采购记录（已作废单不展示） -->
    <el-dialog v-model="priceHistoryVisible" :title="`进价历史 - ${priceHistoryGoodsName}`" width="760px">
      <el-table :data="priceHistoryRows" size="small" border max-height="480">
        <el-table-column prop="purchaseNo" label="进货单号" min-width="160" />
        <el-table-column prop="unitPrice" label="进货单价(元)" width="110" />
        <el-table-column prop="quantity" label="数量" width="80" />
        <el-table-column prop="totalPrice" label="总金额(元)" width="110" />
        <el-table-column label="进货时间" min-width="150">
          <template #default="scope">{{ formatDateTime(scope.row.operationTime) }}</template>
        </el-table-column>
        <el-table-column label="入库时间" min-width="150">
          <template #default="scope">{{ formatDateTime(scope.row.confirmTime) }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, View, Edit, Delete, Close, Check } from '@element-plus/icons-vue'
import { getDeptCode, getRole, isSuperAdmin } from '@/utils/auth'
import { DEFAULT_SUPPLIER_ID } from '@/utils/constants'
import {
  createGoodsAPI,
  deleteGoodsAPI,
  batchDeleteGoodsAPI,
  getGoodsDetailAPI,
  getGoodsPageAPI,
  getGoodsPurchasePriceHistoryAPI,
  getSupplierOptionsAPI,
  matchGoodsSupplierAPI,
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
// D68：销售部门（admin/员工）可编辑成品售价，镜像采购进价范式
const isSales = userDept === 'sales' && (userRole === 'admin' || userRole === 'employee')
const showPrice = isPurchase || isSuperAdmin(userRole)
// D126：成品售价仅销售部门成员+超管可见（后端 page/options/getById 已同步脱敏，前端只做展示裁剪）
const showSalePrice = isSales || isSuperAdmin(userRole)
// 销售编辑成品=售价编辑态：名称/规格/备注/库存等字段只读，仅售价可改
const isSalesPriceMode = computed(() => isSales && isProduct.value && !isView.value && !isAddMode.value && !!form.id)

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
  salePrice: null,
  unit: '',
  spec: '',
  material: '',
  description: '',
  stock: 0,
  warningStock: 10,
  latestSupplierName: '', // D123：详情展示用（只读）
  latestSupplierDefault: false
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
    suppliers.value = res.data || []
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// D102：进价历史弹窗——从物料行直达，展示该物料全部有效已入库采购记录（单价+时间）
const priceHistoryVisible = ref(false)
const priceHistoryGoodsName = ref('')
const priceHistoryRows = ref([])
const formatDateTime = (val) => (val ? String(val).replace('T', ' ').slice(0, 19) : '—')
const openPriceHistory = async (row) => {
  priceHistoryGoodsName.value = row.goodsName
  priceHistoryVisible.value = true
  try {
    const res = await getGoodsPurchasePriceHistoryAPI(row.id)
    priceHistoryRows.value = res.data || []
  } catch {
    // 业务错误已由拦截器统一提示
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
    const pageData = res.data || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
  } catch {
    // 业务错误已由拦截器统一提示
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
  form.salePrice = null
  form.unit = ''
  form.spec = ''
  form.material = ''
  form.description = ''
  form.stock = 0
  form.warningStock = 10
  form.latestSupplierName = ''
  form.latestSupplierDefault = false
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
    salePrice: detail.salePrice ?? null,
    unit: detail.unit || '',
    spec: detail.spec || '',
    material: detail.material || '',
    description: detail.description || '',
    stock: detail.stock || 0,
    warningStock: detail.warningStock ?? 10,
    latestSupplierName: detail.latestSupplierName || '', // D123：详情展示用（只读）
    latestSupplierDefault: !!detail.latestSupplierDefault
  })
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const handleView = async (row) => {
  try {
    await openByDetail(row, true)
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const handleEdit = async (row) => {
  try {
    await openByDetail(row, false)
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// D109：未知物料匹配供应商——读最新采购申请明细到货备注斜杠前的供应商名；失败文案由后端/拦截器提示，可重试
const handleMatchSupplier = async (row) => {
  try {
    await ElMessageBox.confirm(
      `将读取该物料最新采购申请明细「到货备注」中斜杠前的供应商名字进行匹配，匹配失败不会改变当前绑定。确认匹配？`,
      '匹配供应商', { type: 'info', confirmButtonText: '匹配' }
    )
  } catch {
    return
  }
  let res
  try {
    res = await matchGoodsSupplierAPI(row.id)
  } catch {
    // 业务错误已由拦截器统一提示（未填备注/供应商未建档等），采购补做后重新点击即可
    return
  }
  // 匹配已成功；列表刷新失败不应让操作者误判（loadList 内部已兜底，刷新后按钮自消失）
  ElMessage.success(`已匹配供应商「${res.data.supplierName}」（来源采购申请 ${res.data.sourceRequestNo}）`)
  await loadList()
}

const handleDelete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认删除该${goodsNoun.value}资料?`, '警告', { type: 'warning' })
    await deleteGoodsAPI(row.id)
    ElMessage.success('删除成功')
    await loadList()
  } catch {
    // 用户取消删除或业务错误已由拦截器统一提示
  }
}

// 手测问题 1（2026-09-23）：批量删除——尽力而为，能删的删，失败明细弹出
const selectedRows = ref([])
const handleSelectionChange = (val) => {
  selectedRows.value = val
}
const handleBatchDelete = async () => {
  try {
    await ElMessageBox.confirm(`确认删除选中的 ${selectedRows.value.length} 条${goodsNoun.value}资料吗？有库存/被引用的将跳过并提示。`, '警告', { type: 'warning' })
    const res = await batchDeleteGoodsAPI(selectedRows.value.map((r) => r.id))
    const data = res.data || {}
    if (data.failureCount > 0) {
      const detail = (data.failures || []).map((f) => `${f.name || f.id}：${f.reason}`).join('；')
      ElMessage.warning(`成功 ${data.successCount} 条，失败 ${data.failureCount} 条：${detail}`)
    } else {
      ElMessage.success(`成功删除 ${data.successCount} 条`)
    }
    selectedRows.value = []
    await loadList()
  } catch {
    // 用户取消删除或业务错误已由拦截器统一提示
  }
}

const handleSave = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      let payload
      if (isProduct.value && form.id && isSales) {
        // D68：销售编辑成品=只改售价（其他字段由后端部门分支忽略，防误传）；
        // goodsName 仅为过 DTO @NotBlank 校验，后端售价分支不使用
        payload = { goodsName: form.goodsName, salePrice: form.salePrice }
      } else if (isProduct.value) {
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
        // D35.2 采购只改单价；goodsName 仅为过 DTO @NotBlank 校验，后端进价分支不使用
        payload = { goodsName: form.goodsName, purchasePrice: form.purchasePrice }
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
      if (form.id) {
        await updateGoodsAPI(form.id, payload)
      } else {
        await createGoodsAPI(payload)
      }
      ElMessage.success(form.id ? '修改成功' : '新增成功')
      dialogVisible.value = false
      await loadList()
    } catch {
      // 业务错误已由拦截器统一提示
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
