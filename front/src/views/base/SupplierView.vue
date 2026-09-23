<template>
  <el-card>
    <el-form :inline="true" :model="searchForm">
      <el-form-item label="供应商名称">
        <el-input v-model="searchForm.supplierName" placeholder="请输入供应商名称" clearable />
      </el-form-item>
      <el-form-item label="联系人">
        <el-input v-model="searchForm.contact" placeholder="请输入联系人" clearable />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :icon="Search" @click="handleSearch">查询</el-button>
        <el-button :icon="Refresh" @click="resetSearch">重置</el-button>
        <el-button type="success" :icon="Plus" @click="handleAdd" v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }">新增供应商</el-button>
      </el-form-item>
    </el-form>

    <div style="margin-bottom: 12px;">
      <el-button type="danger" :disabled="batchSelectedRows.length === 0" @click="handleBatchDelete" v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }">
        批量删除{{ batchSelectedRows.length > 0 ? `（${batchSelectedRows.length}）` : '' }}
      </el-button>
    </div>
    <el-table :data="tableData" border style="width: 100%" v-loading="loading" @selection-change="handleBatchSelectionChange">
      <el-table-column type="selection" width="46" />
      <el-table-column type="index" label="序号" width="60" />
      <el-table-column prop="supplierName" label="供应商名称" min-width="160" />
      <el-table-column prop="contact" label="联系人" min-width="130">
        <template #default="scope">
          <span>{{ scope.row.contact }}</span>
          <el-tag v-if="scope.row.contactMatched" type="warning" size="small" style="margin-left: 4px">命中</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="position" label="职务" min-width="110" />
      <el-table-column prop="phone" label="联系电话" min-width="140" />
      <el-table-column prop="address" label="联系地址" min-width="160" />
      <el-table-column label="操作" width="150" fixed="right">
        <template #default="scope">
          <el-button link size="small" type="primary" @click="handleView(scope.row)">查看</el-button>
          <el-button link size="small" type="success" @click="handleEdit(scope.row)" v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }">编辑</el-button>
          <el-button link size="small" type="danger" @click="handleDelete(scope.row)" v-permission="{ roles: ['admin', 'employee'], deptCodes: ['purchase'] }">删除</el-button>
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

    <el-dialog :title="dialogTitle" v-model="dialogVisible" width="600px">
      <el-form :model="form" :rules="rules" ref="formRef" label-width="90px" :disabled="isView">
        <el-form-item label="供应商名称" required>
          <el-input v-model="form.supplierName"></el-input>
        </el-form-item>

        <el-form-item label="联系人" required>
          <div class="contact-rows">
            <div v-for="(item, index) in form.contacts" :key="index" class="contact-row">
              <div class="contact-cell">
                <el-input v-model="item.contactPerson" placeholder="姓名" style="width: 130px" />
              </div>
              <div class="contact-cell">
                <el-input v-model="item.contactPhone" placeholder="联系电话" style="width: 150px" />
              </div>
              <div class="contact-cell">
                <el-input v-model="item.position" placeholder="职务" style="width: 120px" />
              </div>
              <el-button v-if="!isView" type="danger" :icon="Remove" circle size="small"
                :disabled="form.contacts.length <= 1" @click="removeContact(index)"></el-button>
            </div>
            <el-button v-if="!isView" type="primary" plain :icon="Plus" @click="addContact">添加联系人</el-button>
          </div>
        </el-form-item>

        <el-form-item label="详细地址">
          <el-input v-model="form.address" type="textarea" :rows="2"></el-input>
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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, Plus, Close, Check, Remove } from '@element-plus/icons-vue'
import {
  createSupplierAPI,
  deleteSupplierAPI,
  getSupplierDetailAPI,
  getSupplierPageAPI,
  updateSupplierAPI
} from '@/api/base'
import { batchDeleteSuppliersAPI } from '@/api/base.js'

const searchForm = reactive({ supplierName: '', contact: '' })
const tableData = ref([])
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(10)
const total = ref(0)

const dialogVisible = ref(false)
const dialogTitle = ref('')
const isView = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, supplierName: '', address: '', contacts: [] })

const rules = {
  supplierName: [{ required: true, message: '请输入供应商名称', trigger: 'blur' }]
}

// 联系人搜索命中时，列表行改展示命中的联系人（D89）：主联系人命中优先，否则取最早录入（id 最小）的命中者；
// 前端找不到匹配（理论上仅英文名大小写场景）返回 null，回退展示后端填好的主联系人。
const pickMatchedContact = (contacts, keyword) => {
  if (!keyword || !Array.isArray(contacts)) return null
  const matched = contacts
    .filter((c) => c && c.contactPerson && c.contactPerson.includes(keyword))
    .sort((a, b) => (a.id || 0) - (b.id || 0))
  if (!matched.length) return null
  return matched.find((c) => c.isDefault === 1) || matched[0]
}

const loadList = async () => {
  loading.value = true
  try {
    const params = {
      pageNum: currentPage.value,
      pageSize: pageSize.value,
      supplierName: searchForm.supplierName || undefined,
      contact: searchForm.contact || undefined
    }
    const res = await getSupplierPageAPI(params)
    const pageData = res.data || {}
    const contactKeyword = searchForm.contact.trim()
    tableData.value = (pageData.records || []).map((item) => {
      const hit = contactKeyword ? pickMatchedContact(item.contacts, contactKeyword) : null
      return {
        ...item,
        contact: hit ? (hit.contactPerson || '') : (item.contact || ''),
        position: hit ? (hit.position || '') : (item.position || ''),
        phone: hit ? (hit.contactPhone || '') : (item.phone || ''),
        contactMatched: !!hit
      }
    })
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
  searchForm.supplierName = ''
  searchForm.contact = ''
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

const addContact = () => {
  form.contacts.push({ contactPerson: '', contactPhone: '', position: '' })
}

const removeContact = (index) => {
  if (form.contacts.length <= 1) return
  form.contacts.splice(index, 1)
}

const initForm = () => {
  form.id = null
  form.supplierName = ''
  form.address = ''
  form.contacts = [{ contactPerson: '', contactPhone: '', position: '' }]
}

const handleAdd = () => {
  isView.value = false
  dialogTitle.value = '新增供应商'
  initForm()
  formRef.value?.clearValidate()
  dialogVisible.value = true
}

const openByDetail = async (row, viewMode) => {
  const res = await getSupplierDetailAPI(row.id)
  const detail = res.data || {}
  isView.value = viewMode
  dialogTitle.value = viewMode ? '供应商详情 (仅查看)' : '编辑供应商'
  const contacts = (detail.contacts && detail.contacts.length) ? detail.contacts : []
  form.id = detail.id
  form.supplierName = detail.supplierName || ''
  form.address = detail.address || ''
  form.contacts = (contacts.length ? contacts : [{ contactPerson: '', contactPhone: '', position: '' }]).map((c) => ({
    contactPerson: c.contactPerson || '',
    contactPhone: c.contactPhone || '',
    position: c.position || ''
  }))
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

// 手测问题 1（2026-09-23）：批量删除——尽力而为，能删的删，失败明细弹出
const batchSelectedRows = ref([])
const handleBatchSelectionChange = (val) => {
  batchSelectedRows.value = val
}
const handleBatchDelete = async () => {
  try {
    await ElMessageBox.confirm(`确认批量删除选中的 ${batchSelectedRows.value.length} 个供应商吗？不满足条件的将跳过并提示。`, '警告', { type: 'warning' })
    const res = await batchDeleteSuppliersAPI(batchSelectedRows.value.map((r) => r.id))
    const data = res.data || {}
    if (data.failureCount > 0) {
      const detail = (data.failures || []).map((f) => `${f.name || f.id}：${f.reason}`).join('；')
      ElMessage.warning(`成功 ${data.successCount} 条，失败 ${data.failureCount} 条：${detail}`)
    } else {
      ElMessage.success(`成功删除 ${data.successCount} 条`)
    }
    batchSelectedRows.value = []
    await loadList()
  } catch {
    // 用户取消或业务错误已由拦截器统一提示
  }
}

const handleDelete = (row) => {
  ElMessageBox.confirm('确认删除该供应商信息?', '警告', { type: 'warning' })
    .then(async () => {
      await deleteSupplierAPI(row.id)
      ElMessage.success('删除成功')
      await loadList()
    })
    .catch(() => {}) // 取消或业务错误已统一提示
}

const handleSave = () => {
  formRef.value?.validate(async (valid) => {
    if (!valid) return
    const validContacts = form.contacts.filter((c) => c.contactPerson && c.contactPerson.trim())
    if (!validContacts.length) {
      ElMessage.warning('请至少填写一位联系人姓名')
      return
    }
    try {
      const payload = {
        supplierName: form.supplierName,
        address: form.address,
        status: 1,
        contacts: validContacts.map((c) => ({
          contactPerson: c.contactPerson,
          contactPhone: c.contactPhone,
          position: c.position
        }))
      }
      form.id ? await updateSupplierAPI(form.id, payload) : await createSupplierAPI(payload)
      ElMessage.success(form.id ? '修改成功' : '新增成功')
      dialogVisible.value = false
      await loadList()
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })
}

onMounted(() => {
  loadList()
})
</script>

<style scoped>
.contact-rows {
  display: flex;
  flex-direction: column;
  gap: 8px;
  width: 100%;
}
.contact-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.contact-cell {
  flex-shrink: 0;
}
</style>