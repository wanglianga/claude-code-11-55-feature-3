<template>
  <div>
    <div class="page-card">
      <h3 class="page-title">采购凭证数量裁定（三证冲突 · 部分交货 · 单位换算 · 重发版本）</h3>
      <div class="toolbar">
        <el-input v-model="filters.period" placeholder="期间 YYYY-MM" style="width:130px" clearable @change="loadAdj" />
        <el-select v-model="filters.status" placeholder="裁定状态" style="width:150px" clearable @change="loadAdj">
          <el-option v-for="(v, k) in STATUS_MAPS.adjudication" :key="k" :label="v[0]" :value="k" />
        </el-select>
        <el-button @click="loadAdj">查询</el-button>
        <el-button v-if="canEdit" type="primary" @click="openReceive">登记凭证</el-button>
        <el-button v-if="canEdit" @click="openConversions">单位换算规则</el-button>
        <el-button @click="docDrawer.visible = true; loadDocs()">凭证台账</el-button>
      </div>
      <el-alert type="info" :closable="false" show-icon style="margin-bottom:10px"
                title="活动量只能来自守恒约束枚举的可行数量组合；密度/包装规格缺失维持待补；重发文件自动新建版本并使旧裁定失效。" />
      <el-table :data="adjudications" size="small" border v-loading="loading">
        <el-table-column prop="adjNo" label="裁定号" width="150" />
        <el-table-column prop="period" label="期间" width="80" />
        <el-table-column label="工厂" width="90">
          <template #default="{ row }">{{ store.plantName(row.plantId) }}</template>
        </el-table-column>
        <el-table-column prop="voucherNo" label="凭证号" width="100" />
        <el-table-column label="物料" width="100">
          <template #default="{ row }">{{ store.energyLabel(row.energyType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120">
          <template #default="{ row }"><StatusTag kind="adjudication" :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="采用数量" width="120">
          <template #default="{ row }">
            <span v-if="row.adoptedQty != null"><b>{{ fmtNum(row.adoptedQty) }}</b> {{ row.adoptedUnit }}</span>
            <span v-else class="diff-pos">待补</span>
          </template>
        </el-table-column>
        <el-table-column prop="candidateCount" label="可行组合数" width="90" />
        <el-table-column label="剪枝" width="70">
          <template #default="{ row }">
            <el-tag v-if="row.pruned" type="warning" size="small">已剪枝</el-tag><span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="decidedBy" label="裁定人" width="90" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" link type="primary" @click="openAdj(row)">证据链</el-button>
          </template>
        </el-table-column>
      </el-table>
      <el-alert v-if="!canEdit" type="warning" :closable="false" show-icon style="margin-top:10px"
                title="当前角色仅可查看；凭证登记由分析员操作，人工裁定与作废由审核员操作。" />
    </div>

    <!-- 裁定详情抽屉 -->
    <el-drawer v-model="adjDrawer.visible" title="数量裁定详情与证据链" size="72%">
      <template v-if="adjDrawer.data">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="裁定号">{{ adjDrawer.data.adjudication.adjNo }}</el-descriptions-item>
          <el-descriptions-item label="状态">
            <StatusTag kind="adjudication" :value="adjDrawer.data.adjudication.status" />
          </el-descriptions-item>
          <el-descriptions-item label="裁定人">{{ adjDrawer.data.adjudication.decidedBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="采用数量">
            <span v-if="adjDrawer.data.adjudication.adoptedQty != null">
              <b>{{ fmtNum(adjDrawer.data.adjudication.adoptedQty) }}</b> {{ adjDrawer.data.adjudication.adoptedUnit }}
            </span>
            <span v-else class="diff-pos">未确定（待补/待裁定）</span>
          </el-descriptions-item>
          <el-descriptions-item label="可行组合数">{{ adjDrawer.data.adjudication.candidateCount }}</el-descriptions-item>
          <el-descriptions-item label="组合剪枝">
            <el-tag v-if="adjDrawer.data.pruned" type="warning" size="small">超上限已剪枝</el-tag><span v-else>否</span>
          </el-descriptions-item>
          <el-descriptions-item label="裁定规则" :span="3">{{ adjDrawer.data.adjudication.decisionRule }}</el-descriptions-item>
          <el-descriptions-item label="理由" :span="3">{{ adjDrawer.data.adjudication.reason }}</el-descriptions-item>
          <el-descriptions-item v-if="adjDrawer.data.adjudication.invalidatedReason" label="失效原因" :span="3">
            <span class="diff-pos">{{ adjDrawer.data.adjudication.invalidatedReason }}
              （{{ fmtTime(adjDrawer.data.adjudication.invalidatedAt) }}）</span>
          </el-descriptions-item>
        </el-descriptions>

        <h4>证据条目（采用 / 排除 / 支撑，含凭证版本哈希与排除理由）</h4>
        <el-table :data="adjDrawer.data.evidence" size="small" border>
          <el-table-column prop="docNo" label="单据号" width="120" />
          <el-table-column label="类型" width="100">
            <template #default="{ row }">
              <el-tag size="small">{{ STATUS_MAPS.docType[row.docType]?.[0] || row.docType }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="docVersionNo" label="版本" width="60" />
          <el-table-column label="角色" width="100">
            <template #default="{ row }"><StatusTag kind="evidenceRole" :value="row.role" /></template>
          </el-table-column>
          <el-table-column label="原生数量" width="120">
            <template #default="{ row }">
              {{ row.qtyNative != null ? fmtNum(row.qtyNative) + ' ' + row.unitNative : '-' }}
            </template>
          </el-table-column>
          <el-table-column label="换算后(t)" width="100">
            <template #default="{ row }">
              <span v-if="row.conversionStatus === 'OK'">{{ fmtNum(row.qtyConverted) }}</span>
              <StatusTag v-else kind="conversion" value="UNCONVERTIBLE" />
            </template>
          </el-table-column>
          <el-table-column label="签章" width="60">
            <template #default="{ row }">
              <el-tag v-if="row.docSigned" type="success" size="small">已签</el-tag><span v-else>未签</span>
            </template>
          </el-table-column>
          <el-table-column prop="authorityTier" label="权威级" width="70" />
          <el-table-column prop="exclusiveGroup" label="互斥组" width="90">
            <template #default="{ row }">{{ row.exclusiveGroup || '-' }}</template>
          </el-table-column>
          <el-table-column label="文件哈希(SHA-256)" width="150">
            <template #default="{ row }"><el-text class="hash" truncated>{{ row.docHash }}</el-text></template>
          </el-table-column>
          <el-table-column prop="excludeReason" label="理由/排除说明" min-width="220" show-overflow-tooltip />
        </el-table>

        <template v-if="adjDrawer.data.adjudication.status === 'PENDING'">
          <h4>人工裁定（采用量必须属于下列可行组合，不能填金额或平均数）</h4>
          <el-alert v-if="!canAdjudicate" type="info" :closable="false" show-icon
                    title="人工裁定需审核员/管理员角色，当前账号仅可查看候选与理由。" />
          <el-alert v-else-if="!adjDrawer.data.feasibleCandidates.length" type="error" :closable="false" show-icon
                    title="当前无可行组合：存在无法换算的凭证（密度/包装规格缺失），请补录换算规则或重发带规格的凭证后重裁" />
          <template v-else>
            <el-radio-group v-model="decideQty" style="margin-bottom:10px">
              <el-radio v-for="c in adjDrawer.data.feasibleCandidates" :key="String(c.netQty)" :value="Number(c.netQty)"
                        style="display:block;margin-bottom:6px">
                采用净量 <b>{{ fmtNum(c.netQty) }}</b> t
                （凭证 {{ c.docIds.length }} 份：{{ c.docIds.join(', ') }}）
              </el-radio>
            </el-radio-group>
            <el-input v-model="decideReason" type="textarea" rows="2" placeholder="裁定理由（必填）：为什么采用该数量、为什么排除其他候选"
                      style="margin-bottom:10px" />
            <el-button type="primary" :disabled="decideQty == null || !decideReason.trim()"
                       @click="submitDecide">提交人工裁定</el-button>
          </template>
        </template>
      </template>
    </el-drawer>

    <!-- 凭证登记对话框 -->
    <el-dialog v-model="recvDlg.visible" title="接收端凭证登记（文件哈希 + 抽取字段 + 订单/交货/退货关系）"
               width="820px" destroy-on-close>
      <el-form label-width="100px" size="small">
        <el-row :gutter="10">
          <el-col :span="8"><el-form-item label="单据编号"><el-input v-model="recvDlg.docNo" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="单据类型">
              <el-select v-model="recvDlg.docType" style="width:100%">
                <el-option v-for="(v, k) in STATUS_MAPS.docType" :key="k" :label="v[0]" :value="k" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="单据日期"><el-input v-model="recvDlg.docDate" placeholder="YYYY-MM-DD" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="供应商"><el-input v-model="recvDlg.supplier" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="工厂">
              <el-select v-model="recvDlg.plantId" style="width:100%">
                <el-option v-for="p in store.plants" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="期间"><el-input v-model="recvDlg.period" placeholder="YYYY-MM" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="凭证号"><el-input v-model="recvDlg.voucherNo" placeholder="如 V-2001" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="采购订单号"><el-input v-model="recvDlg.orderNo" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="交货批次号"><el-input v-model="recvDlg.deliveryNo" /></el-form-item></el-col>
          <el-col :span="8">
            <el-form-item label="物料">
              <el-select v-model="recvDlg.materialCode" style="width:100%">
                <el-option v-for="t in store.energyTypes.filter(x => x.category === 'PROCUREMENT')"
                           :key="t.code" :label="t.label" :value="t.code" />
              </el-select>
            </el-form-item>
          </el-col>
          <el-col :span="8"><el-form-item label="权威性层级"><el-input-number v-model="recvDlg.authorityTier" :min="1" :max="3" /></el-form-item></el-col>
          <el-col :span="8"><el-form-item label="已签章"><el-switch v-model="recvDlg.signed" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="文件名"><el-input v-model="recvDlg.fileName" /></el-form-item></el-col>
          <el-col :span="12"><el-form-item label="文件哈希"><el-input v-model="recvDlg.fileHash" placeholder="SHA-256（接收端保存）" /></el-form-item></el-col>
        </el-row>
        <el-form-item label="抽取字段">
          <el-table :data="recvDlg.fields" size="small" border style="width:100%">
            <el-table-column label="字段" width="130">
              <template #default="{ row }">
                <el-select v-model="row.name" size="small">
                  <el-option label="数量 QUANTITY" value="QUANTITY" />
                  <el-option label="单位 UNIT" value="UNIT" />
                  <el-option label="密度 DENSITY" value="DENSITY" />
                  <el-option label="包装规格 PACK_SPEC" value="PACK_SPEC" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column label="数值" width="150">
              <template #default="{ row }"><el-input-number v-model="row.numValue" :controls="false" style="width:130px" /></template>
            </el-table-column>
            <el-table-column label="单位" width="110">
              <template #default="{ row }"><el-input v-model="row.unit" size="small" /></template>
            </el-table-column>
            <el-table-column label="抽取状态" width="130">
              <template #default="{ row }">
                <el-select v-model="row.extractStatus" size="small">
                  <el-option label="正常 OK" value="OK" />
                  <el-option label="缺失 MISSING" value="MISSING" />
                  <el-option label="不可读 UNREADABLE" value="UNREADABLE" />
                </el-select>
              </template>
            </el-table-column>
            <el-table-column width="60">
              <template #default="{ $index }">
                <el-button link type="danger" size="small" @click="recvDlg.fields.splice($index, 1)">删</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-form-item>
        <el-button size="small" @click="recvDlg.fields.push({ name: 'QUANTITY', numValue: 1, unit: 't', extractStatus: 'OK' })">+ 字段</el-button>
      </el-form>
      <template #footer>
        <el-button @click="recvDlg.visible = false">取消</el-button>
        <el-button type="primary" @click="submitReceive">登记（同号重发自动新建版本）</el-button>
      </template>
    </el-dialog>

    <!-- 凭证台账抽屉 -->
    <el-drawer v-model="docDrawer.visible" title="凭证台账（版本与文件哈希）" size="70%">
      <el-input v-model="docDrawer.voucherFilter" placeholder="按凭证号过滤" style="width:200px;margin-bottom:10px"
                clearable @change="loadDocs" />
      <el-table :data="docDrawer.list" size="small" border>
        <el-table-column prop="docNo" label="单据号" width="120" />
        <el-table-column prop="versionNo" label="版本" width="60" />
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ STATUS_MAPS.docType[row.docType]?.[0] || row.docType }}</template>
        </el-table-column>
        <el-table-column prop="voucherNo" label="凭证号" width="90" />
        <el-table-column prop="orderNo" label="订单号" width="120" />
        <el-table-column prop="deliveryNo" label="交货号" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }"><StatusTag kind="evidenceDoc" :value="row.status" /></template>
        </el-table-column>
        <el-table-column label="哈希" min-width="160">
          <template #default="{ row }"><el-text class="hash" truncated>{{ row.fileHash }}</el-text></template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button v-if="canAdjudicate" size="small" link type="danger"
                       :disabled="row.status !== 'RECEIVED'" @click="voidDoc(row)">作废</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>
    </el-drawer>

    <!-- 换算规则对话框 -->
    <el-dialog v-model="convDlg.visible" title="单位换算规则（缺密度/包装规格 → 维持待补）" width="760px">
      <el-table :data="convDlg.list" size="small" border>
        <el-table-column prop="energyType" label="物料" width="120" />
        <el-table-column label="换算" width="120">
          <template #default="{ row }">{{ row.fromUnit }} → {{ row.toUnit }}</template>
        </el-table-column>
        <el-table-column label="系数" width="100">
          <template #default="{ row }">{{ row.factor ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="需密度" width="80">
          <template #default="{ row }"><el-tag v-if="row.requiresDensity" size="small" type="warning">是</el-tag><span v-else>否</span></template>
        </el-table-column>
        <el-table-column label="需包装规格" width="100">
          <template #default="{ row }"><el-tag v-if="row.requiresPackSpec" size="small" type="warning">是</el-tag><span v-else>否</span></template>
        </el-table-column>
        <el-table-column label="默认密度" width="100">
          <template #default="{ row }">{{ row.defaultDensity ?? '-' }}</template>
        </el-table-column>
        <el-table-column label="默认包装" width="100">
          <template #default="{ row }">{{ row.defaultPackSpec ?? '-' }}</template>
        </el-table-column>
      </el-table>
      <h4>新增/更新规则</h4>
      <el-row :gutter="8">
        <el-col :span="6"><el-input v-model="convDlg.form.energyType" placeholder="物料 PURCH_STEEL" /></el-col>
        <el-col :span="4"><el-input v-model="convDlg.form.fromUnit" placeholder="原单位" /></el-col>
        <el-col :span="4"><el-input v-model="convDlg.form.toUnit" placeholder="目标单位 t" /></el-col>
        <el-col :span="5"><el-input-number v-model="convDlg.form.factor" :controls="false" placeholder="固定系数" style="width:100%" /></el-col>
        <el-col :span="5">
          <el-select v-model="convDlg.form.requires" placeholder="依赖" style="width:100%">
            <el-option label="固定系数" value="factor" />
            <el-option label="需密度" value="density" />
            <el-option label="需包装规格" value="pack" />
          </el-select>
        </el-col>
      </el-row>
      <el-button type="primary" size="small" style="margin-top:10px" @click="submitRule">保存规则</el-button>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import api from '../api'
import { store, fmtNum, fmtTime, STATUS_MAPS } from '../store'
import StatusTag from '../components/StatusTag.vue'

const loading = ref(false)
const canEdit = computed(() => ['ANALYST', 'ADMIN'].includes(store.user?.role))
const canAdjudicate = computed(() => ['AUDITOR', 'ADMIN'].includes(store.user?.role))
const filters = reactive({ period: '', status: '' })
const adjudications = ref([])
const adjDrawer = reactive({ visible: false, data: null })
const decideQty = ref(null)
const decideReason = ref('')
const docDrawer = reactive({ visible: false, list: [], voucherFilter: '' })
const convDlg = reactive({ visible: false, list: [], form: blankRule() })
const recvDlg = reactive({ visible: false, ...blankReceive() })

function blankRule() {
  return { energyType: 'PURCH_STEEL', fromUnit: '', toUnit: 't', factor: null, requires: 'factor' }
}
function blankReceive() {
  return {
    docNo: '', docType: 'INVOICE', docDate: '', supplier: '', plantId: store.plants[0]?.id,
    period: '', voucherNo: '', orderNo: '', deliveryNo: '', materialCode: 'PURCH_STEEL',
    authorityTier: 1, signed: true, fileName: '', fileHash: '',
    fields: [{ name: 'QUANTITY', numValue: 1, unit: 't', extractStatus: 'OK' },
             { name: 'UNIT', numValue: null, unit: 't', extractStatus: 'OK' }]
  }
}

async function loadAdj() {
  loading.value = true
  try {
    const params = {}
    if (filters.period) params.period = filters.period
    if (filters.status) params.status = filters.status
    adjudications.value = (await api.get('/evidence/adjudications', { params })).data
  } finally { loading.value = false }
}

async function openAdj(row) {
  adjDrawer.data = (await api.get(`/evidence/adjudications/${row.id}`)).data
  decideQty.value = null
  decideReason.value = ''
  adjDrawer.visible = true
}

async function submitDecide() {
  const id = adjDrawer.data.adjudication.id
  const resp = await api.post(`/evidence/adjudications/${id}/decide`, {
    adoptedQty: decideQty.value, reason: decideReason.value
  })
  ElMessage.success('人工裁定已生效，证据版本已冻结')
  adjDrawer.visible = false
  loadAdj()
  return resp
}

function openReceive() {
  Object.assign(recvDlg, blankReceive(), { visible: true })
}

async function submitReceive() {
  const body = {
    docNo: recvDlg.docNo, docType: recvDlg.docType, supplier: recvDlg.supplier,
    plantId: recvDlg.plantId, period: recvDlg.period, voucherNo: recvDlg.voucherNo || null,
    orderNo: recvDlg.orderNo || null, deliveryNo: recvDlg.deliveryNo || null,
    materialCode: recvDlg.materialCode, signed: recvDlg.signed,
    authorityTier: recvDlg.authorityTier, docDate: recvDlg.docDate,
    fileName: recvDlg.fileName || (recvDlg.docNo + '.pdf'), fileHash: recvDlg.fileHash,
    fields: recvDlg.fields.map(f => ({
      name: f.name, numValue: f.numValue, unit: f.unit, extractStatus: f.extractStatus
    }))
  }
  await api.post('/evidence/documents', body)
  ElMessage.success('凭证已登记；同号重发已自动新建版本并触发重裁')
  recvDlg.visible = false
  loadAdj()
}

async function loadDocs() {
  const params = docDrawer.voucherFilter ? { voucherNo: docDrawer.voucherFilter } : {}
  docDrawer.list = (await api.get('/evidence/documents', { params })).data
}

async function voidDoc(row) {
  const { value } = await ElMessageBox.prompt('作废原因', `作废凭证 ${row.docNo}`, { inputValue: '' })
  await api.post(`/evidence/documents/${row.id}/void`, { reason: value || '审计作废' })
  ElMessage.success('凭证已作废，引用它的裁定已失效并重裁')
  loadDocs(); loadAdj()
}

async function openConversions() {
  convDlg.list = (await api.get('/evidence/conversions')).data
  convDlg.visible = true
}

async function submitRule() {
  const f = convDlg.form
  const body = {
    energyType: f.energyType, fromUnit: f.fromUnit, toUnit: f.toUnit,
    factor: f.requires === 'factor' ? f.factor : null,
    requiresDensity: f.requires === 'density',
    requiresPackSpec: f.requires === 'pack'
  }
  await api.post('/evidence/conversions', body)
  ElMessage.success('换算规则已保存')
  convDlg.list = (await api.get('/evidence/conversions')).data
}

onMounted(loadAdj)
</script>

<style scoped>
h4 { margin: 16px 0 8px; }
.hash { font-family: monospace; font-size: 12px; }
</style>
