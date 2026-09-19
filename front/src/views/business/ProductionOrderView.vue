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
          <el-tag v-if="scope.row.kitStatus" :type="kitTagType(scope.row.kitStatus)" size="small">{{ scope.row.kitStatusText }}</el-tag>
          <span v-else style="color:#909399">—</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="100" align="center">
        <template #default="scope">
          <el-tag :type="statusTagType(scope.row.status)" size="small">{{ scope.row.statusText }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="createTime" label="下达时间" width="170" />
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="scope">
          <el-button link size="small" type="primary" @click="handleView(scope.row)">查看</el-button>
          <el-button v-if="scope.row.status === 1" link size="small" type="success" @click="handleStart(scope.row)">开工</el-button>
          <el-button
            v-if="(scope.row.status === 1) && (scope.row.kitStatus === 'partial' || scope.row.kitStatus === 'block')"
            link size="small" type="warning" @click="openDraftDialog(scope.row)"
            v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >补料</el-button>
          <!-- D107 两段式：待入库状态下提交入库申请（不加库存），仓储确认后本单才完成；已提交可撤销 -->
          <el-button v-if="scope.row.status === 3 && !scope.row.pendingInboundId" link size="small" type="success" @click="handleReceipt(scope.row)">提交入库申请</el-button>
          <el-button
            v-if="scope.row.status === 3 && scope.row.pendingInboundId" link size="small" type="warning"
            @click="handleCancelReceipt(scope.row)"
          >撤销申请</el-button>
          <el-tooltip content="错单作废留痕，立即生效，不涉及库存变动" placement="top">
            <el-button
              v-if="scope.row.status === 1 || scope.row.status === 2" link size="small" type="warning"
              @click="handleVoid(scope.row)" v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
            >作废</el-button>
          </el-tooltip>
          <el-button
            v-if="[1, 2, 3].includes(scope.row.status)" link size="small" type="danger"
            @click="openTerminate(scope.row)" v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
          >终止</el-button>
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
    <el-dialog :title="'下达生产任务单' + (createResult ? '（已下达，齐套结果如下）' : '')" v-model="createVisible" width="920px">
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
        <!-- D70：选填关联销售单——该成品「正常且待出库」的销售单；关联后入库自动通知建单销售，销售端时间线可见本单进度 -->
        <el-form-item label="关联销售单">
          <el-select v-model="createForm.salesOrderId" clearable filterable placeholder="选填：为哪张销售需求单生产" style="width: 100%" :loading="linkableLoading">
            <el-option
              v-for="opt in linkableSalesOptions" :key="opt.id"
              :label="`${opt.salesNo}（${opt.customerName || '未填客户'} × ${opt.quantity}）`" :value="opt.id"
            />
          </el-select>
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
          title="存在严重缺料或未知物料（新物料），开工将被阻断，请采购补齐后重试。" type="error" :closable="false" style="margin-bottom: 8px"
        />
        <el-alert
          v-else-if="createResult.kitStatus === 'partial'"
          title="存在部分缺料，可开工（有库存的部分将先发料），同时已通知采购补料。" type="warning" :closable="false" style="margin-bottom: 8px"
        />
        <el-alert v-else title="物料齐套，可正常开工。" type="success" :closable="false" style="margin-bottom: 8px" />
        <el-table :data="createResult.kitLines || []" border size="small">
          <el-table-column prop="goodsName" label="物料" min-width="120" />
          <el-table-column label="规格/材质" min-width="110">
            <template #default="s">{{ [s.row.spec, s.row.material].filter(Boolean).join(' / ') || '—' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="100">
            <template #default="s">{{ s.row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column prop="unit" label="单位" width="60" />
          <el-table-column label="用量" width="70">
            <template #default="s">{{ fmtNum(s.row.unitUsage) }}</template>
          </el-table-column>
          <el-table-column label="需用量" width="80">
            <template #default="s">{{ fmtNum(s.row.required) }}</template>
          </el-table-column>
          <el-table-column label="库存" width="70">
            <template #default="s">{{ s.row.goodsId ? s.row.stock : '—' }}</template>
          </el-table-column>
          <el-table-column label="缺口" width="80">
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
        <el-button :icon="Close" @click="closeCreate">关闭</el-button>
        <el-button v-if="!createResult" type="primary" :icon="Check" @click="handleCreate">下达并预警</el-button>
      </template>
    </el-dialog>

    <!-- 查看详情 -->
    <el-dialog title="生产任务单详情" v-model="detailVisible" width="920px" top="6vh">
      <template v-if="detail">
        <el-descriptions :column="2" border>
          <el-descriptions-item label="任务单号">{{ detail.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="成品">{{ detail.goodsName }}</el-descriptions-item>
          <el-descriptions-item label="数量">{{ detail.quantity }} {{ detail.unit }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTagType(detail.status)" size="small">{{ detail.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="齐套状态">
            <el-tag v-if="detail.kitStatus" :type="kitTagType(detail.kitStatus)" size="small">{{ detail.kitStatusText }}</el-tag>
            <span v-else style="color:#909399">—</span>
          </el-descriptions-item>
          <el-descriptions-item label="下达时间">{{ detail.createTime }}</el-descriptions-item>
          <!-- D70：关联销售单（1对1，可空=通用备货） -->
          <el-descriptions-item label="关联销售单">
            <span v-if="detail.salesOrderNo">{{ detail.salesOrderNo }}</span>
            <span v-else style="color:#909399">通用备货（未关联）</span>
          </el-descriptions-item>
          <!-- D71：预计完工——生产手工修正（留痕），销售端时间线优先展示该值 -->
          <el-descriptions-item label="预计完工">
            <span v-if="detail.expectedCompletionTime">{{ fmtTime(detail.expectedCompletionTime) }}</span>
            <span v-else style="color:#909399">未设定（销售端按系统推算展示）</span>
            <el-button
              v-if="[1, 2, 3].includes(detail.status)" link type="primary" size="small" style="margin-left: 8px"
              v-permission="{ roles: ['admin'], deptCodes: ['production'] }"
              @click="openEcDialog"
            >修正</el-button>
          </el-descriptions-item>
        </el-descriptions>

        <!-- D107 两段式：入库申请状态提示 -->
        <el-alert
          v-if="detail.status === 3 && detail.pendingInboundId"
          :title="`已提交入库申请 ${detail.pendingInboundNo}（${detail.pendingInboundOperator || ''} ${fmtTime(detail.pendingInboundTime)}），待仓储管理员确认入库，确认后本单完成。确认前可撤销重新提交。`"
          type="info" :closable="false" style="margin-top: 12px"
        />
        <el-alert
          v-else-if="detail.status === 3 && detail.lastInboundRejectNo"
          :title="`入库申请 ${detail.lastInboundRejectNo} 已被仓储驳回：${detail.lastInboundRejectReason || '未填写原因'}。请核对后重新提交入库申请。`"
          type="warning" :closable="false" style="margin-top: 12px"
        />

        <el-divider content-position="left">生产工序</el-divider>
        <!-- D64：有工序实例 → 状态化 10 道工序（7 道人工打卡 + 首测/成品测由质检推导 + 成品入库由入库推导）；无实例（历史单/已作废）回落静态快照 -->
        <template v-if="detail.stepList && detail.stepList.length">
          <el-table :data="detail.stepList" border size="small">
            <el-table-column label="序号" width="60" align="center">
              <template #default="s">{{ s.row.stepNo }}</template>
            </el-table-column>
            <el-table-column prop="stepName" label="工序" min-width="200" />
            <el-table-column label="状态" width="170" align="center">
              <template #default="s">
                <el-tag :type="s.row.tagType" size="small">{{ s.row.statusText }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="打卡人 / 时间" width="190">
              <template #default="s">
                <template v-if="s.row.operatorName">
                  {{ s.row.operatorName }}
                  <span style="color: #909399">{{ (s.row.operateTime || '').replace('T', ' ').slice(0, 16) }}</span>
                </template>
                <span v-else>—</span>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="130" align="center">
              <template #default="s">
                <el-button
                  v-if="s.row.operable" link type="primary" size="small"
                  v-permission="{ deptCodes: ['production'] }"
                  @click="doStepComplete(s.row)"
                >打卡</el-button>
                <el-button
                  v-if="s.row.revocable" link type="warning" size="small"
                  v-permission="{ deptCodes: ['production'] }"
                  @click="doStepRevoke(s.row)"
                >撤销</el-button>
                <span v-if="!s.row.operable && !s.row.revocable">—</span>
              </template>
            </el-table-column>
          </el-table>
          <div style="color: #909399; font-size: 12px; margin-top: 6px">
            首次测试/成品测在「质检记录」页面录入后自动更新；成品入库在仓储「确认入库」后自动更新；其余 7 道由生产研发部成员打卡，打卡本人或生产管理员可撤销。
          </div>
        </template>
        <ol v-else style="margin: 0; padding-left: 20px">
          <li v-for="(step, i) in (detail.processList || [])" :key="i">{{ step }}</li>
        </ol>

        <template v-if="detail.kitLines && detail.kitLines.length">
          <el-divider content-position="left">齐套明细</el-divider>
          <el-table :data="detail.kitLines" border size="small">
            <el-table-column prop="goodsName" label="物料" min-width="120" />
            <el-table-column label="规格/材质" min-width="110">
              <template #default="s">{{ [s.row.spec, s.row.material].filter(Boolean).join(' / ') || '—' }}</template>
            </el-table-column>
            <el-table-column label="备注" min-width="100">
              <template #default="s">{{ s.row.remark || '—' }}</template>
            </el-table-column>
            <el-table-column label="需用量" width="90">
              <template #default="s">{{ fmtNum(s.row.required) }}</template>
            </el-table-column>
            <el-table-column label="库存" width="80">
              <template #default="s">{{ s.row.goodsId ? s.row.stock : '—' }}</template>
            </el-table-column>
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
                    :label="goodsOptionLabel(opt)" :value="opt.id"
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
                <el-button link size="small" type="danger" @click="removeReturnItem($index)">删除</el-button>
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
    <el-dialog v-model="draftVisible" :title="`补料 - ${draftRow.orderNo || ''}`" width="920px" top="6vh">
      <!-- D87：已有在途补料单时提示并禁提交（其入库后可再补，D86；缺口行仍可查看） -->
      <el-alert
        v-if="draftInFlightNo"
        :title="`已有在途补料单 ${draftInFlightNo}，待其入库后可再次补料`"
        type="warning" :closable="false" style="margin-bottom: 8px"
      />
      <template v-if="boundDraftLines.length">
        <el-divider content-position="left">已有物料缺口（{{ boundDraftLines.length }}）</el-divider>
        <el-table :data="boundDraftLines" border size="small">
          <el-table-column prop="goodsName" label="物料" min-width="130" />
          <el-table-column label="规格/材质" min-width="110">
            <template #default="s">{{ [s.row.spec, s.row.material].filter(Boolean).join(' / ') || '—' }}</template>
          </el-table-column>
          <el-table-column label="备注" min-width="100">
            <template #default="s">{{ s.row.remark || '—' }}</template>
          </el-table-column>
          <el-table-column label="需用量" width="75">
            <template #default="s">{{ fmtNum(s.row.required) }}</template>
          </el-table-column>
          <el-table-column label="库存" width="60">
            <template #default="s">{{ s.row.stock }}</template>
          </el-table-column>
          <el-table-column label="缺口" width="70">
            <template #default="s">{{ fmtNum(s.row.deficit) }}</template>
          </el-table-column>
          <el-table-column label="申请数量" width="115">
            <template #default="s">
              <el-input-number v-model="s.row.applyQty" :min="0" size="small" style="width: 100px" />
            </template>
          </el-table-column>
        </el-table>
      </template>

      <template v-if="unknownDraftLines.length">
        <el-divider content-position="left">
          未知物料（新物料，首次出现需建档）
          <el-tag size="small" type="info" style="margin-left: 8px">{{ unknownDraftLines.length }}</el-tag>
        </el-divider>
        <el-alert
          title="以下物料仓库从未有过：请补全信息，提交后自动建档（挂缺省供应商，进价由采购维护）；也可改绑为已有物料。"
          type="info" :closable="false" style="margin-bottom: 8px"
        />
        <el-table :data="unknownDraftLines" border size="small">
          <el-table-column label="名称" min-width="120">
            <template #default="s">
              <el-input v-if="!s.row.rebindMode" v-model="s.row.newGoodsName" placeholder="物料名称" size="small" />
              <el-select v-else v-model="s.row.goodsId" filterable placeholder="改绑已有物料" size="small" style="width: 100%">
                <el-option v-for="opt in materialOptions" :key="opt.id" :label="goodsOptionLabel(opt)" :value="opt.id" />
              </el-select>
            </template>
          </el-table-column>
          <el-table-column label="规格" min-width="95">
            <template #default="s">
              <el-input v-if="!s.row.rebindMode" v-model="s.row.spec" placeholder="规格" size="small" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="材质" min-width="95">
            <template #default="s">
              <el-input v-if="!s.row.rebindMode" v-model="s.row.material" placeholder="材质" size="small" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="备注" min-width="105">
            <template #default="s">
              <el-input v-if="!s.row.rebindMode" v-model="s.row.remark" placeholder="备注" size="small" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="单位" width="85">
            <template #default="s">
              <el-input v-if="!s.row.rebindMode" v-model="s.row.unit" placeholder="单位" size="small" />
              <span v-else>—</span>
            </template>
          </el-table-column>
          <el-table-column label="缺口" width="65">
            <template #default="s">{{ fmtNum(s.row.deficit) }}</template>
          </el-table-column>
          <el-table-column label="申请数量" width="115">
            <template #default="s">
              <el-input-number v-model="s.row.applyQty" :min="0" size="small" style="width: 100px" />
            </template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template #default="s">
              <el-button v-if="!s.row.rebindMode" link type="primary" size="small" @click="s.row.rebindMode = true">改绑已有</el-button>
              <el-button v-else link type="primary" size="small" @click="switchBackToNew(s.row)">改为建档</el-button>
            </template>
          </el-table-column>
        </el-table>
      </template>
      <template #footer>
        <el-button @click="draftVisible = false">取消</el-button>
        <el-button type="primary" :loading="draftSubmitting" :disabled="!!draftInFlightNo" @click="doCreateDraft">提交补料</el-button>
      </template>
    </el-dialog>

    <!-- D71：修正预计完工（生产管理员；清空=恢复系统推算；操作留痕 @AuditLog） -->
    <el-dialog v-model="ecVisible" title="修正预计完工时间" width="420px">
      <el-date-picker
        v-model="ecTime" type="datetime" value-format="YYYY-MM-DD HH:mm:ss"
        placeholder="选择预计完工时间" style="width: 100%"
      />
      <div style="color:#909399; font-size:12px; margin-top:8px">
        该时间将作为「生产确认」口径展示在销售单履约时间线上；清空则恢复系统推算。
      </div>
      <template #footer>
        <el-button @click="ecVisible = false">取消</el-button>
        <el-button :loading="ecSubmitting" @click="submitEc(null)">清空恢复推算</el-button>
        <el-button type="primary" :loading="ecSubmitting" @click="submitEc(ecTime)">确定</el-button>
      </template>
    </el-dialog>

    <!-- D73：手动终止（销售取消等外部原因）；预填已领未退净额供核对退料，一个事务提交 -->
    <el-dialog v-model="terminateVisible" :title="`终止生产任务单 - ${terminateRow.orderNo || ''}`" width="820px" :close-on-click-modal="false">
      <el-alert
        title="终止为终态操作，不可恢复。已领物料将按下方清单生成退料单，由仓储确认入库后库存加回。"
        type="warning" :closable="false" style="margin-bottom: 12px"
      />
      <el-alert
        v-if="terminateHasOpenReturn"
        :title="`该单已有进行中退料单（${terminateOpenReturnPickNo}），本次终止不再自动生成退料单，请在既有退料单中核对退料覆盖。`"
        type="info" :closable="false" style="margin-bottom: 12px"
      />
      <el-form label-width="90px">
        <el-form-item label="终止原因" required>
          <el-input v-model="terminateReason" type="textarea" :rows="2" placeholder="必填，如：销售交易单 XS… 已取消" />
        </el-form-item>
        <el-form-item v-if="terminateItems.length" label="退料明细">
          <el-table :data="terminateItems" border size="small" style="width: 100%">
            <el-table-column type="index" label="序号" width="55" />
            <el-table-column label="物料" min-width="150">
              <template #default="{ row }">
                {{ row.goodsName }}
                <span v-if="row.spec || row.material" style="color:#909399">（{{ [row.spec, row.material].filter(Boolean).join(' / ') }}）</span>
              </template>
            </el-table-column>
            <el-table-column label="已领未退" width="90" align="center">
              <template #default="{ row }">{{ row.maxQty }}</template>
            </el-table-column>
            <el-table-column label="退料数量" width="140">
              <template #default="{ row }">
                <el-input-number v-model="row.quantity" :min="0" :max="row.maxQty" controls-position="right" style="width: 120px" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="70" align="center">
              <template #default="{ $index }">
                <el-button link size="small" type="danger" @click="terminateItems.splice($index, 1)">删除</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div style="color:#909399; font-size:12px; margin-top:6px">已耗用/损坏的物料请减量或删除该行；退料数量不可超过已领未退。</div>
        </el-form-item>
        <el-form-item v-else label="退料明细">
          <span style="color:#909399">无已领未退物料，终止后不生成退料单</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="terminateVisible = false">取消</el-button>
        <el-button type="danger" :loading="terminateSubmitting" @click="doTerminate">确认终止</el-button>
      </template>
    </el-dialog>

    <!-- D94：作废说明弹窗（生产任务单为直废型，立即生效、原因可选、无库存影响） -->
    <VoidConfirmDialog
      v-model="voidDialogVisible"
      :direct="true"
      :reason-required="false"
      :submitting="voidSubmitting"
      @confirm="submitVoid"
    />
  </el-card>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, Refresh, Plus, Check, Close
} from '@element-plus/icons-vue'
import {
  cancelProductionReceiptAPI,
  completeProductionStepAPI,
  createProductionOrderAPI,
  getLinkableSalesOptionsAPI,
  getProductionOrderDetailAPI,
  getProductionOrderPageAPI,
  receiptProductionOrderAPI,
  revokeProductionStepAPI,
  startProductionOrderAPI,
  updateExpectedCompletionAPI,
  voidProductionOrderAPI
} from '@/api/business'
import VoidConfirmDialog from '@/components/VoidConfirmDialog.vue'
import { getGoodsProductOptionsAPI, getGoodsMaterialOptionsAPI } from '@/api/base'
import { createDraftPurchaseRequestAPI } from '@/api/purchaseRequest'
import { createProductionPickAPI, getProductionPickListAPI, createProductionReturnAPI, getProductionReturnableAPI, terminateProductionOrderAPI } from '@/api/pickList'

const statusOptions = [
  { value: 1, label: '待生产' },
  { value: 2, label: '生产中' },
  { value: 3, label: '待入库' },
  { value: 4, label: '已完成' },
  { value: 5, label: '已作废' },
  { value: 6, label: '已报废' },
  { value: 7, label: '已终止' }
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
const createForm = reactive({ goodsId: null, quantity: 1, remark: '', salesOrderId: null })
// D70：关联销售单下拉（随成品选择联动加载）
const linkableSalesOptions = ref([])
const linkableLoading = ref(false)
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
    const pageData = res.data || {}
    tableData.value = pageData.records || []
    total.value = pageData.total || 0
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    loading.value = false
  }
}

const loadOptions = async () => {
  try {
    const p = await getGoodsProductOptionsAPI({ hasBom: true }) // D67：只列有有效 BOM 的成品
    productOptions.value = (p.data || []).map((it) => ({ goodsId: it.id, goodsName: it.name || it.goodsName, unit: it.unit }))
  } catch {
    // 业务错误已由拦截器统一提示
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
  createForm.salesOrderId = null
  linkableSalesOptions.value = []
  createFormRef.value?.clearValidate()
  createVisible.value = true
}

// D70：选定成品后加载可关联销售单（正常且待出库）；切换成品清空已选关联
watch(() => createForm.goodsId, async (goodsId) => {
  createForm.salesOrderId = null
  linkableSalesOptions.value = []
  if (!goodsId) return
  linkableLoading.value = true
  try {
    const res = await getLinkableSalesOptionsAPI({ goodsId })
    linkableSalesOptions.value = res.data || []
  } catch {
    // 选项加载失败不阻断建单，后端 create 仍会兜底校验
  } finally {
    linkableLoading.value = false
  }
})

const handleCreate = () => {
  createFormRef.value?.validate(async (valid) => {
    if (!valid) return
    try {
      const res = await createProductionOrderAPI({
        goodsId: createForm.goodsId,
        quantity: createForm.quantity,
        remark: createForm.remark || '',
        salesOrderId: createForm.salesOrderId || undefined
      })
      createResult.value = res.data || {}
      ElMessage.success('生产任务单已下达')
    } catch {
      // 业务错误已由拦截器统一提示
    }
  })
}

const closeCreate = () => {
  createVisible.value = false
  if (createResult.value) loadList()
}

const openDetail = async (row) => {
  const res = await getProductionOrderDetailAPI(row.id)
  detail.value = res.data || {}
  // 加载领料单状态（优先取 PICK 类型行，用于"领料已出库"标签展示）
  try {
    const pickRes = await getProductionPickListAPI(row.id)
    if (pickRes.data?.length) {
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
  try {
    await openDetail(row)
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

// D64：工序打卡 / 撤销（生产研发部成员；撤销限打卡本人或生产管理员）
const refreshDetail = async () => {
  const res = await getProductionOrderDetailAPI(detail.value.id)
  detail.value = res.data || {}
  await loadList()
}

const doStepComplete = async (row) => {
  try {
    await ElMessageBox.confirm(`确认工序「${row.stepName}」已完成并打卡？`, '工序打卡', { type: 'warning' })
  } catch {
    return
  }
  try {
    await completeProductionStepAPI(detail.value.id, row.stepNo)
    ElMessage.success(`「${row.stepName}」已打卡`)
    await refreshDetail()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const doStepRevoke = async (row) => {
  try {
    await ElMessageBox.confirm(`确认撤销工序「${row.stepName}」的打卡？撤销后将清除打卡人与打卡时间。`, '撤销打卡', { type: 'warning' })
  } catch {
    return
  }
  try {
    await revokeProductionStepAPI(detail.value.id, row.stepNo)
    ElMessage.success(`「${row.stepName}」打卡已撤销`)
    await refreshDetail()
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

const doApplyPick = async () => {
  try {
    await ElMessageBox.confirm('确认申请领料？将按 BOM 生成该生产任务单的全部领料明细并提交仓储确认出库。', '申请领料', { type: 'warning' })
  } catch {
    return
  }
  pickSubmitting.value = true
  try {
    await createProductionPickAPI(detail.value.id)
    ElMessage.success('领料申请已提交，待仓储确认出库')
    // 刷新领料状态与列表（优先取 PICK 类型行）
    const pickRes = await getProductionPickListAPI(detail.value.id)
    if (pickRes.data?.length) {
      const pickRow = pickRes.data.find((p) => p.pickType === 'PICK') || pickRes.data[0]
      pickListStatus.value = pickRow.status
    }
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
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
    } catch {
      // 业务错误已由拦截器统一提示；加载失败不打开退料弹窗
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
    await createProductionReturnAPI(returnRow.value.id, {
      remark: returnRemark.value || '',
      items
    })
    ElMessage.success('退料已提交，待仓储确认入库')
    returnVisible.value = false
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    returnSubmitting.value = false
  }
}

const handleStart = async (row) => {
  // 前置友好校验：领料单是否已全额出库（失败放行，由后端开工网关兜底返回准确错误）
  try {
    const res = await getProductionPickListAPI(row.id)
    const s = res.data?.[0]?.status
    if (!s) {
      ElMessage.warning('请先申请领料并由仓储确认出库')
      return
    }
    if (s !== 2 && s !== 3) {
      ElMessage.warning('领料单尚未全额出库，请等仓储确认')
      return
    }
  } catch {
    // 网络/业务异常放行（业务错误拦截器已提示），后端开工网关兜底
  }
  ElMessageBox.confirm('确认开工？开工需该生产任务单的领料单已由仓储确认出库。', '开工确认', { type: 'warning' })
  .then(async () => {
    await startProductionOrderAPI(row.id)
    ElMessage.success('已开工')
    await loadList()
  }).catch(() => {}) // 取消或业务错误已统一提示
}

// D107 两段式：提交入库申请（生成待仓储确认记录，不加库存；仓储确认后本单才完成）
const handleReceipt = (row) => {
  ElMessageBox.confirm(`确认为生产任务单「${row.orderNo}」提交入库申请？提交后待仓储管理员确认入库（确认前不加库存），本单暂停留在此。`, '提交入库申请', { type: 'warning' })
    .then(async () => {
      await receiptProductionOrderAPI(row.id)
      ElMessage.success('已提交入库申请，待仓储管理员确认')
      await loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

// D107：撤销入库申请（仓储确认/驳回前可撤，撤仓储待办消息）
const handleCancelReceipt = (row) => {
  ElMessageBox.confirm(`确认撤销任务单「${row.orderNo}」的入库申请（${row.pendingInboundNo}）？撤销后可重新提交。`, '撤销入库申请', { type: 'warning' })
    .then(async () => {
      await cancelProductionReceiptAPI(row.id)
      ElMessage.success('已撤销入库申请')
      await loadList()
    }).catch(() => {}) // 取消或业务错误已统一提示
}

// D94：先弹说明弹窗（立即生效/留痕/无库存影响），确认后再作废
const voidDialogVisible = ref(false)
const voidTarget = ref(null)
const voidSubmitting = ref(false)

const handleVoid = (row) => {
  voidTarget.value = row
  voidDialogVisible.value = true
}

const submitVoid = async (reason) => {
  if (!voidTarget.value) return
  voidSubmitting.value = true
  try {
    await voidProductionOrderAPI(voidTarget.value.id, reason || '')
    ElMessage.success('已作废')
    voidDialogVisible.value = false
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    voidSubmitting.value = false
  }
}

// D73：手动终止（仅生产管理员；预填已领未退净额，一个事务终止+生成退料单）
const terminateVisible = ref(false)
const terminateRow = ref({})
const terminateReason = ref('')
const terminateItems = ref([])
const terminateHasOpenReturn = ref(false)
const terminateOpenReturnPickNo = ref('')
const terminateSubmitting = ref(false)

async function openTerminate(row) {
  terminateRow.value = row
  terminateReason.value = ''
  terminateItems.value = []
  terminateHasOpenReturn.value = false
  terminateOpenReturnPickNo.value = ''
  try {
    const res = await getProductionReturnableAPI(row.id)
    const data = res.data || {}
    terminateItems.value = (data.items || []).map((it) => ({ ...it, maxQty: it.quantity }))
    terminateHasOpenReturn.value = !!data.hasOpenReturn
    terminateOpenReturnPickNo.value = data.openReturnPickNo || ''
  } catch {
    // 业务错误已由拦截器统一提示；加载失败不打开终止弹窗
    return
  }
  terminateVisible.value = true
}

async function doTerminate() {
  if (!terminateReason.value || !terminateReason.value.trim()) {
    ElMessage.warning('请填写终止原因')
    return
  }
  try {
    await ElMessageBox.confirm('终止为终态操作，不可恢复。确认终止该生产任务单？', '终止确认', { type: 'warning' })
  } catch (e) {
    if (e === 'cancel') return
  }
  terminateSubmitting.value = true
  try {
    const items = terminateItems.value
      .filter((i) => i.quantity > 0)
      .map((i) => ({ goodsId: i.goodsId, quantity: i.quantity }))
    await terminateProductionOrderAPI(terminateRow.value.id, {
      reason: terminateReason.value.trim(),
      items
    })
    ElMessage.success(items.length ? '已终止，退料单已提交仓储确认入库' : '已终止')
    terminateVisible.value = false
    await loadList()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    terminateSubmitting.value = false
  }
}

// 补料
const draftVisible = ref(false)
const draftRow = ref({})
const draftLines = ref([])
const draftSubmitting = ref(false)
const materialOptions = ref([])
// D87：在途补料单号（详情 VO 透出；非空时弹窗提示并禁提交）
const draftInFlightNo = ref('')

// D60：按未知物料拆两组——已有物料缺口 / 未知物料（新物料）
const boundDraftLines = computed(() => draftLines.value.filter((l) => l.lineStatus !== 'unknown'))
const unknownDraftLines = computed(() => draftLines.value.filter((l) => l.lineStatus === 'unknown'))

// D60/ADR-0003：物料下拉文案统一「名称(规格/材质)(单位)」，防同名不同规格混选
const goodsOptionLabel = (opt) => {
  const detail = [opt.spec, opt.material].filter(Boolean).join('/')
  return opt.name + (detail ? `（${detail}）` : '') + (opt.unit ? `（${opt.unit}）` : '')
}

async function loadMaterialOptions() {
  if (materialOptions.value.length) return
  try {
    const res = await getGoodsMaterialOptionsAPI()
    materialOptions.value = res.data || []
  } catch {
    // 业务错误已由拦截器统一提示
  }
}

function openDraftDialog(row) {
  draftRow.value = row
  draftVisible.value = true
  draftSubmitting.value = false
  draftLines.value = []
  draftInFlightNo.value = ''
  loadMaterialOptions()
  // 拉详情拿 kitLines，映射成可编辑行；未知物料行预填 BOM 行信息（名称/规格/材质/备注），单位由生产现填
  getProductionOrderDetailAPI(row.id).then((res) => {
    const vo = res.data || {}
    draftInFlightNo.value = vo.inFlightRequestNo || ''
    draftLines.value = (vo.kitLines || [])
      .filter((l) => (l.deficit || 0) > 0)
      .map((l) => ({
        ...l,
        applyQty: Math.ceil(l.deficit),
        rebindMode: false,
        newGoodsName: l.goodsId ? '' : (l.goodsName || ''),
        spec: l.spec || '',
        material: l.material || '',
        remark: l.remark || '',
        unit: ''
      }))
  }).catch(() => {}) // 业务错误已由拦截器统一提示
}

// 改绑回建档：清掉所选物料，回到内联建档表单
function switchBackToNew(row) {
  row.rebindMode = false
  row.goodsId = null
}

function doCreateDraft() {
  const items = []
  for (const l of draftLines.value) {
    if (!l.applyQty || l.applyQty <= 0) continue
    if (l.lineStatus === 'unknown' && !l.goodsId) {
      if (l.rebindMode) {
        if (!l.goodsId) {
          ElMessage.warning('存在改绑行尚未选择物料，请选择或改回建档')
          return
        }
        items.push({ bomDetailId: l.bomDetailId, goodsId: l.goodsId, quantity: l.applyQty })
      } else {
        if (!l.newGoodsName || !l.newGoodsName.trim()) {
          ElMessage.warning('存在未知物料行未填写物料名称，请补全或改绑已有物料')
          return
        }
        items.push({
          bomDetailId: l.bomDetailId,
          quantity: l.applyQty,
          newGoodsName: l.newGoodsName.trim(),
          spec: l.spec || '',
          material: l.material || '',
          remark: l.remark || '',
          unit: l.unit || ''
        })
      }
    } else {
      items.push({ bomDetailId: l.bomDetailId, goodsId: l.goodsId, quantity: l.applyQty })
    }
  }
  if (!items.length) {
    ElMessage.warning('请至少填一条申请数量')
    return
  }
  draftSubmitting.value = true
  createDraftPurchaseRequestAPI({
    productionOrderId: draftRow.value.id,
    details: items,
    remark: ''
  }).then(async () => {
    ElMessage.success('补料已提交，待采购')
    draftVisible.value = false
    await loadList()
  }).catch(() => {
    // 业务错误已由拦截器统一提示
  }).finally(() => {
    draftSubmitting.value = false
  })
}

// D71：修正预计完工（生产管理员，留痕）
const ecVisible = ref(false)
const ecTime = ref(null)
const ecSubmitting = ref(false)

const openEcDialog = () => {
  ecTime.value = detail.value?.expectedCompletionTime
    ? String(detail.value.expectedCompletionTime).replace('T', ' ').slice(0, 19)
    : null
  ecVisible.value = true
}

const submitEc = async (time) => {
  ecSubmitting.value = true
  try {
    await updateExpectedCompletionAPI(detail.value.id, { expectedCompletionTime: time || null })
    ElMessage.success(time ? '预计完工时间已更新' : '已清空，恢复系统推算')
    ecVisible.value = false
    await refreshDetail()
  } catch {
    // 业务错误已由拦截器统一提示
  } finally {
    ecSubmitting.value = false
  }
}

// 标题格式化工具
const fmtNum = (v) => (v == null ? '-' : Number(v).toLocaleString())
const fmtTime = (v) => (v ? String(v).replace('T', ' ').slice(0, 16) : '')
const kitTagType = (k) => (k === 'ok' || k === 'issued' ? 'success' : k === 'partial' ? 'warning' : k === 'block' ? 'danger' : 'info')
const statusTagType = (s) => (s === 1 ? 'info' : s === 2 ? 'warning' : s === 3 ? 'primary' : s === 4 ? 'success' : s === 7 ? 'danger' : 'info')
// D60：lineStatus 四态——unknown（未知物料）用 info 灰，区别于严重缺料的红
const lineTagType = (l) => (l === 'ok' ? 'success' : l === 'partial' ? 'warning' : l === 'unknown' ? 'info' : 'danger')

onMounted(() => {
  loadList()
  loadOptions()
})
</script>

<style scoped>
.deficit-red { color: #f56c6c; font-weight: 600; }
</style>