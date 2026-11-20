<template>
  <el-drawer :model-value="visible" title="血缘下钻：排放结果 ← 来源数据 + 因子版本"
             size="620px" @update:model-value="v => $emit('update:visible', v)">
    <div v-if="data" class="lineage">
      <el-descriptions :column="2" border size="small" title="排放结果">
        <el-descriptions-item label="工厂">{{ store.plantName(data.result.plantId) }}</el-descriptions-item>
        <el-descriptions-item label="产品线">{{ store.lineName(data.result.productLineId) }}</el-descriptions-item>
        <el-descriptions-item label="范围">{{ data.result.scope }}</el-descriptions-item>
        <el-descriptions-item label="能源类型">{{ store.energyLabel(data.result.energyType) }}</el-descriptions-item>
        <el-descriptions-item label="活动量">{{ fmtNum(data.result.activityQty) }} {{ data.result.unit }}</el-descriptions-item>
        <el-descriptions-item label="排放量">
          <b>{{ fmtNum(data.result.emissionTco2e) }}</b> tCO2e
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <StatusTag kind="result" :value="data.result.status" />
        </el-descriptions-item>
      </el-descriptions>

      <template v-if="data.factorVersion">
        <h4>排放因子版本</h4>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="因子">{{ data.factorVersion.factorCode }} {{ data.factorVersion.factorName }}</el-descriptions-item>
          <el-descriptions-item label="版本">v{{ data.factorVersion.versionNo }}</el-descriptions-item>
          <el-descriptions-item label="因子值">{{ data.factorVersion.value }} kgCO2e/{{ data.result.unit }}</el-descriptions-item>
          <el-descriptions-item label="地域">{{ data.factorVersion.region }}</el-descriptions-item>
          <el-descriptions-item label="适用期">{{ data.factorVersion.validFrom }} ~ {{ data.factorVersion.validTo || '长期' }}</el-descriptions-item>
          <el-descriptions-item label="状态"><StatusTag kind="factor" :value="data.factorVersion.status" /></el-descriptions-item>
          <el-descriptions-item label="批准人">{{ data.factorVersion.approvedBy || '-' }}</el-descriptions-item>
          <el-descriptions-item label="批准时间">{{ fmtTime(data.factorVersion.approvedAt) }}</el-descriptions-item>
        </el-descriptions>
      </template>
      <el-alert v-else type="warning" :closable="false" style="margin-top:12px"
                title="缺少适用的批准因子：该指标列为待核，未使用集团平均因子兜底" />

      <h4>来源数据（{{ data.sources.length }} 行）</h4>
      <el-table :data="data.sources" size="small" border>
        <el-table-column prop="batchNo" label="来源批次" width="180" />
        <el-table-column prop="rowNo" label="行号" width="60" />
        <el-table-column label="数量" width="110">
          <template #default="{ row }">{{ row.qtyUsed != null ? fmtNum(row.qtyUsed) + ' ' + row.unit : '待证（未填）' }}</template>
        </el-table-column>
        <el-table-column prop="voucherNo" label="供应商凭证" width="110">
          <template #default="{ row }">{{ row.voucherNo || '-' }}</template>
        </el-table-column>
        <el-table-column label="批次状态" width="90">
          <template #default="{ row }"><StatusTag kind="batch" :value="row.batchStatus" /></template>
        </el-table-column>
        <el-table-column label="核验人" width="80">
          <template #default="{ row }">{{ row.verifiedBy || '-' }}</template>
        </el-table-column>
        <el-table-column label="数量裁定" width="90">
          <template #default="{ row }">
            <el-button v-if="row.adjudicationId" link type="primary" size="small"
                       @click="openAdj(row)">证据链</el-button>
            <span v-else>-</span>
          </template>
        </el-table-column>
      </el-table>

      <!-- 数量裁定证据链下钻 -->
      <el-dialog v-model="adjDlg.visible" title="数量裁定证据链（运行冻结快照口径）" width="80%" append-to-body>
        <template v-if="adjDlg.data">
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="裁定号">{{ adjDlg.data.adjudication.adjNo }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <StatusTag kind="adjudication" :value="adjDlg.data.adjudication.status" />
            </el-descriptions-item>
            <el-descriptions-item label="采用数量">
              {{ adjDlg.data.adjudication.adoptedQty != null
                ? fmtNum(adjDlg.data.adjudication.adoptedQty) + ' ' + adjDlg.data.adjudication.adoptedUnit : '待补' }}
            </el-descriptions-item>
            <el-descriptions-item label="裁定理由" :span="3">{{ adjDlg.data.adjudication.reason }}</el-descriptions-item>
          </el-descriptions>
          <el-table :data="adjDlg.data.evidence" size="small" border style="margin-top:10px">
            <el-table-column prop="docNo" label="单据号" width="110" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ STATUS_MAPS.docType[row.docType]?.[0] || row.docType }}</template>
            </el-table-column>
            <el-table-column prop="docVersionNo" label="版本" width="55" />
            <el-table-column label="角色" width="90">
              <template #default="{ row }"><StatusTag kind="evidenceRole" :value="row.role" /></template>
            </el-table-column>
            <el-table-column label="换算后(t)" width="90">
              <template #default="{ row }">
                <span v-if="row.conversionStatus === 'OK'">{{ fmtNum(row.qtyConverted) }}</span>
                <StatusTag v-else kind="conversion" value="UNCONVERTIBLE" />
              </template>
            </el-table-column>
            <el-table-column prop="excludeReason" label="理由/排除说明" min-width="200" show-overflow-tooltip />
            <el-table-column label="哈希" width="140">
              <template #default="{ row }"><el-text class="hash" truncated>{{ row.docHash }}</el-text></template>
            </el-table-column>
          </el-table>
        </template>
      </el-dialog>
    </div>
    <el-skeleton v-else :rows="8" animated />
  </el-drawer>
</template>

<script setup>
import { reactive, ref, watch } from 'vue'
import api from '../api'
import { store, fmtNum, fmtTime, STATUS_MAPS } from '../store'
import StatusTag from './StatusTag.vue'

const props = defineProps({
  visible: Boolean,
  resultId: Number
})
defineEmits(['update:visible'])

const data = ref(null)
const adjDlg = reactive({ visible: false, data: null })

async function openAdj(row) {
  adjDlg.data = (await api.get(`/evidence/adjudications/${row.adjudicationId}`)).data
  adjDlg.visible = true
}

watch(() => [props.visible, props.resultId], async ([v, id]) => {
  if (v && id) {
    data.value = null
    const resp = await api.get(`/results/${id}/lineage`)
    data.value = resp.data
  }
}, { immediate: true })
</script>

<style scoped>
.lineage h4 { margin: 16px 0 8px; color: #303133; }
.hash { font-family: monospace; font-size: 12px; }
</style>
