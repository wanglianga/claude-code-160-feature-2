<template>
  <div>
    <!-- 故障申报 + 方案列表 -->
    <el-card>
      <div class="toolbar">
        <span class="title">机位故障改排</span>
        <el-date-picker v-model="filterDate" type="date" value-format="YYYY-MM-DD" clearable
                        placeholder="按故障日期筛选" style="width:160px" @change="loadPlans" />
        <el-button :icon="Refresh" @click="loadPlans">刷新</el-button>
        <div class="spacer" />
        <el-button type="danger" :icon="Warning" @click="openCreate">机位自检失败 / 故障申报</el-button>
      </div>
      <el-table :data="plans" v-loading="plansLoading" border stripe size="small"
                highlight-current-row @current-change="(row) => row && selectPlan(row.plan.id)">
        <el-table-column label="机位" width="80" align="center">
          <template #default="{ row }">
            <el-tag effect="dark" :color="zoneColor(row.plan.machine.zone)" style="border:none">
              {{ row.plan.machine.code }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="故障日期" width="100">
          <template #default="{ row }">{{ row.plan.faultDate }}</template>
        </el-table-column>
        <el-table-column prop="plan.reason" label="故障原因" show-overflow-tooltip />
        <el-table-column label="预计修复" width="140">
          <template #default="{ row }">{{ fmtTime(row.plan.expectedRepairAt) }}</template>
        </el-table-column>
        <el-table-column label="改排进度" width="150" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.decidedCount === row.actionTotal ? 'success' : 'danger'">
              决策 {{ row.decidedCount }}/{{ row.actionTotal }}
            </el-tag>
            <el-tag size="small" style="margin-left:4px"
                    :type="row.notifiedCount === row.actionTotal ? 'success' : 'warning'">
              通知 {{ row.notifiedCount }}/{{ row.actionTotal }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('ReschedulePlanStatus', row.plan.status)">
              {{ label('ReschedulePlanStatus', row.plan.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="申报" width="150">
          <template #default="{ row }">{{ row.plan.createdBy }} {{ fmtTime(row.plan.createdAt).slice(5) }}</template>
        </el-table-column>
        <template #empty><el-empty description="暂无改排方案，点击右上角「故障申报」发起" /></template>
      </el-table>
    </el-card>

    <!-- 方案详情 -->
    <el-card v-if="detail" style="margin-top:16px" v-loading="detailLoading">
      <template #header>
        <div class="head">
          <span>
            方案 #{{ detail.plan.id }}：机位 {{ detail.plan.machine.code }}（{{ label('MachineZone', detail.plan.machine.zone) }}）故障改排
          </span>
          <div>
            <el-tag :type="tagType('ReschedulePlanStatus', detail.plan.status)" style="margin-right:8px">
              {{ label('ReschedulePlanStatus', detail.plan.status) }}
            </el-tag>
            <el-button v-if="detail.plan.status === 'OPEN'" size="small" @click="openRepair">登记预计修复时间</el-button>
            <el-button v-if="detail.plan.status === 'OPEN'" size="small" type="success" @click="closePlan">
              闭环方案
            </el-button>
          </div>
        </div>
      </template>

      <el-descriptions :column="4" border size="small" style="margin-bottom:12px">
        <el-descriptions-item label="故障原因">{{ detail.plan.reason }}</el-descriptions-item>
        <el-descriptions-item label="预计修复时间">{{ fmtTime(detail.plan.expectedRepairAt) }}</el-descriptions-item>
        <el-descriptions-item label="改排进度">
          决策 {{ detail.decidedCount }}/{{ detail.actions.length }}，通知 {{ detail.notifiedCount }}/{{ detail.actions.length }}
        </el-descriptions-item>
        <el-descriptions-item label="闭环信息">
          <span v-if="detail.plan.closedAt">{{ detail.plan.closedBy }} {{ fmtTime(detail.plan.closedAt) }}</span>
          <span v-else>-</span>
        </el-descriptions-item>
      </el-descriptions>

      <el-table :data="detail.actions" border stripe size="small">
        <el-table-column label="患者" width="110">
          <template #default="{ row }">
            <b>{{ row.action.patientName }}</b>
            <div v-if="row.action.patientArrived" class="arrived">已到院</div>
          </template>
        </el-table-column>
        <el-table-column label="感染标记" width="80" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('InfectionMarker', row.action.infectionMarker)">
              {{ label('InfectionMarker', row.action.infectionMarker) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="班次" width="70" align="center">
          <template #default="{ row }">{{ label('Shift', row.chain.session.shift) }}</template>
        </el-table-column>
        <el-table-column label="当前安排" width="150">
          <template #default="{ row }">
            {{ row.chain.machine.code }} {{ fmtTime(row.chain.session.plannedStart).slice(11) }}
            <div class="sub">{{ label('TransportMode', row.chain.notify.transportMode) }}
              {{ row.chain.notify.transportWindow || '' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="改排决策" min-width="200">
          <template #default="{ row }">
            <template v-if="row.action.status === 'DONE'">
              <el-tag size="small" type="success">{{ label('RescheduleActionType', row.action.actionType) }}</el-tag>
              <span class="decision-text">
                <template v-if="row.action.actionType === 'DELAY'">延后 {{ row.action.delayMinutes }} 分钟</template>
                <template v-else-if="row.action.actionType === 'SWAP'">
                  与 {{ row.action.swapPatientName }} 互换（双方确认：{{ row.action.swapConfirmByA }} / {{ row.action.swapConfirmByB }}）
                </template>
                <template v-else>→ {{ row.action.targetMachineCode }}（{{ label('Shift', row.action.targetShift) }}）</template>
              </span>
              <div v-if="row.action.note" class="sub">{{ row.action.note }}</div>
            </template>
            <div v-else-if="detail.plan.status === 'OPEN'" class="decide-btns">
              <el-button size="small" type="warning" @click="openDecide(row, 'DELAY')">延后</el-button>
              <el-button size="small" type="primary" @click="openDecide(row, 'CHANGE_MACHINE')">换机</el-button>
              <el-button v-if="row.chain.session.shift !== 'PM'" size="small" type="primary" plain
                         @click="openDecide(row, 'SHIFT_PM')">改下午班</el-button>
              <el-button size="small" type="danger" plain @click="openSwap(row)">协商调换</el-button>
            </div>
            <el-tag v-else size="small" type="info">未处理</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="串联状态" width="220" align="center">
          <template #default="{ row }">
            <el-tooltip content="患者通知"><span class="chain-icon" :class="row.action.notifyStatus === 'DONE' ? 'ok' : 'bad'">知</span></el-tooltip>
            <el-tooltip content="家属接送"><span class="chain-icon" :class="row.action.familyPickupNote ? 'ok' : 'na'">接</span></el-tooltip>
            <el-tooltip content="机位消毒"><span class="chain-icon" :class="row.chain.disinfectionsToday.length ? 'ok' : 'na'">消</span></el-tooltip>
            <el-tooltip content="耗材领用"><span class="chain-icon" :class="row.chain.consumables.length ? 'ok' : 'na'">耗</span></el-tooltip>
            <el-tooltip content="到院补偿"><span class="chain-icon" :class="row.action.patientArrived ? 'ok' : 'na'">偿</span></el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openChain(row)">串联详情</el-button>
            <el-button v-if="row.action.status === 'DONE' && row.action.notifyStatus === 'PENDING'"
                       size="small" text type="warning" @click="openNotify(row)">登记通知</el-button>
            <el-button v-if="detail.plan.status === 'OPEN'" size="small" text type="danger"
                       @click="openArrival(row)">到院登记</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 故障申报 -->
    <el-dialog v-model="createDialog" title="机位自检失败 / 故障申报" width="480px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="申报后机位将置为故障，并自动筛出当天受影响患者生成改排方案" />
      <el-form label-width="100px">
        <el-form-item label="故障机位" required>
          <el-select v-model="createForm.machineId" style="width:100%">
            <el-option v-for="m in machines" :key="m.id" :value="m.id"
                       :label="`${m.code}（${label('MachineZone', m.zone)}｜${label('MachineStatus', m.status)}）`"
                       :disabled="m.status === 'FAULT' || m.status === 'MAINTENANCE'" />
          </el-select>
        </el-form-item>
        <el-form-item label="故障原因" required>
          <el-input v-model="createForm.reason" type="textarea" :rows="2"
                    placeholder="如：班前自检失败，电导度异常" />
        </el-form-item>
        <el-form-item label="预计修复">
          <el-date-picker v-model="createForm.expectedRepairAt" type="datetime"
                          value-format="YYYY-MM-DDTHH:mm:ss" placeholder="可稍后登记" style="width:100%" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="doCreate">申报并生成改排方案</el-button>
      </template>
    </el-dialog>

    <!-- 决策：延后 / 换机 / 改下午班 -->
    <el-dialog v-model="decideDialog" :title="`改排决策：${decideForm.action?.action.patientName}`" width="560px">
      <el-form label-width="100px">
        <el-form-item label="改排方式">
          <el-radio-group v-model="decideForm.type" @change="decideForm.targetMachineId = null">
            <el-radio-button value="DELAY">延后上机</el-radio-button>
            <el-radio-button value="CHANGE_MACHINE">更换机位</el-radio-button>
            <el-radio-button value="SHIFT_PM" :disabled="decideForm.action?.chain.session.shift === 'PM'">
              改到下午班
            </el-radio-button>
          </el-radio-group>
        </el-form-item>

        <template v-if="decideForm.type === 'DELAY'">
          <el-form-item label="延后时长" required>
            <el-input-number v-model="decideForm.delayMinutes" :min="15" :step="15" :max="480" />
            <span style="margin-left:8px">分钟（留在原机位 {{ detail?.plan.machine.code }}，等修复后上机）</span>
          </el-form-item>
          <el-alert v-if="detail?.plan.expectedRepairAt" type="info" :closable="false"
                    :title="`预计修复时间 ${fmtTime(detail.plan.expectedRepairAt)}，延后后的上机时间不能早于该时间`" />
          <el-alert v-else type="warning" :closable="false" title="尚未登记预计修复时间，建议先登记" />
        </template>

        <template v-else>
          <el-form-item :label="decideForm.type === 'CHANGE_MACHINE' ? '备用机位' : '下午班机位'" required>
            <el-select v-model="decideForm.targetMachineId" style="width:100%"
                       :placeholder="decideForm.type === 'SHIFT_PM' ? '不选则自动分配最早可用机位' : '请选择'">
              <el-option v-for="o in machineOptions" :key="o.machineId" :value="o.machineId"
                         :label="`${o.code}（${label('MachineZone', o.zone)}）预计 ${slotOf(o)} 上机`" />
            </el-select>
          </el-form-item>
          <el-alert type="info" :closable="false"
                    title="换机/改班后：原预领耗材自动归还并按新安排重新预领，机位消毒计划一并更新" />
        </template>

        <el-form-item label="备注" style="margin-top:12px">
          <el-input v-model="decideForm.note" placeholder="如：已征得患者同意" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="decideDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doDecide">执行改排</el-button>
      </template>
    </el-dialog>

    <!-- 协商调换 -->
    <el-dialog v-model="swapDialog" :title="`协商调换：${swapForm.action?.action.patientName}`" width="640px">
      <el-alert type="warning" :closable="false" style="margin-bottom:12px"
                title="调换后对方患者将落到故障机位（须晚于预计修复时间）；双方确认人将留痕，防止口头换班争议" />
      <el-form label-width="110px">
        <el-form-item label="对方患者" required>
          <el-select v-model="swapForm.otherSessionId" style="width:100%" filterable>
            <el-option v-for="c in swapForm.candidates" :key="c.sessionId" :value="c.sessionId"
                       :disabled="!c.zoneOk || c.repairConflict"
                       :label="`${c.patientName}｜${c.machineCode} ${label('Shift', c.shift)} ${fmtTime(c.plannedStart).slice(11)}${c.zoneOk ? '' : '（分区不匹配）'}${c.repairConflict ? '（早于预计修复）' : ''}`" />
          </el-select>
        </el-form-item>
        <el-form-item label="本患者确认人" required>
          <el-input v-model="swapForm.confirmByA" placeholder="如：患者本人 / 家属张女士" />
        </el-form-item>
        <el-form-item label="对方患者确认人" required>
          <el-input v-model="swapForm.confirmByB" placeholder="如：患者本人 / 家属李先生" />
        </el-form-item>
        <el-form-item label="确认备注">
          <el-input v-model="swapForm.confirmNote" type="textarea" :rows="2"
                    placeholder="如：护士站当面确认，双方同意互换" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="swapDialog = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="doSwap">确认调换</el-button>
      </template>
    </el-dialog>

    <!-- 登记通知 -->
    <el-dialog v-model="notifyDialog" :title="`患者通知：${notifyForm.action?.action.patientName}`" width="480px">
      <el-form label-width="100px">
        <el-form-item label="联系电话">
          <span>{{ notifyForm.action?.chain.notify.patientPhone || '-' }}</span>
        </el-form-item>
        <el-form-item label="通知渠道" required>
          <el-radio-group v-model="notifyForm.channel">
            <el-radio-button value="电话">电话</el-radio-button>
            <el-radio-button value="短信">短信</el-radio-button>
            <el-radio-button value="家属转告">家属转告</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="通知内容">
          <el-input v-model="notifyForm.note" type="textarea" :rows="2"
                    placeholder="如：已告知改到下午班 13:30 上机" />
        </el-form-item>
        <el-form-item label="家属接送">
          <el-input v-model="notifyForm.familyPickupNote" placeholder="如：家属 17:40 来院接回" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="notifyDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doNotify">保存</el-button>
      </template>
    </el-dialog>

    <!-- 到院登记（前台） -->
    <el-dialog v-model="arrivalDialog" :title="`到院患者登记：${arrivalForm.action?.action.patientName}`" width="480px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px"
                title="患者已到院等待，前台登记等候补偿与交通调整" />
      <el-form label-width="100px">
        <el-form-item label="等候补偿">
          <el-input v-model="arrivalForm.compensationNote" type="textarea" :rows="2"
                    placeholder="如：早餐券×1 + 交通补贴 20 元" />
        </el-form-item>
        <el-form-item label="交通调整">
          <el-input v-model="arrivalForm.transportAdjustNote" type="textarea" :rows="2"
                    placeholder="如：班车返程改至 13:30 / 家属改 15:00 来接" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="arrivalDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doArrival">保存登记</el-button>
      </template>
    </el-dialog>

    <!-- 登记预计修复时间 -->
    <el-dialog v-model="repairDialog" title="登记预计修复时间" width="420px">
      <el-date-picker v-model="repairTime" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss"
                      style="width:100%" placeholder="选择预计修复时间" />
      <template #footer>
        <el-button @click="repairDialog = false">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="doRepair">保存</el-button>
      </template>
    </el-dialog>

    <!-- 串联详情抽屉 -->
    <el-drawer v-model="chainDrawer" :title="`串联详情：${chainView?.action.patientName}`" size="520px">
      <template v-if="chainView">
        <el-descriptions :column="1" border size="small" title="患者通知 / 家属接送">
          <el-descriptions-item label="通知状态">
            <el-tag size="small" :type="tagType('NotifyStatus', chainView.action.notifyStatus)">
              {{ label('NotifyStatus', chainView.action.notifyStatus) }}
            </el-tag>
            <span v-if="chainView.action.notifiedAt" class="sub" style="margin-left:8px">
              {{ chainView.action.notifyChannel }}｜{{ chainView.action.notifiedBy }}｜{{ fmtTime(chainView.action.notifiedAt) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="通知内容">{{ chainView.action.notifyNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="家属接送">{{ chainView.action.familyPickupNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="接送方式">
            {{ label('TransportMode', chainView.chain.notify.transportMode) }}
            {{ chainView.chain.notify.transportWindow || '' }}
          </el-descriptions-item>
        </el-descriptions>

        <el-descriptions :column="1" border size="small" title="到院补偿 / 交通调整" style="margin-top:16px">
          <el-descriptions-item label="是否到院">
            <el-tag size="small" :type="chainView.action.patientArrived ? 'warning' : 'info'">
              {{ chainView.action.patientArrived ? '已到院' : '未到院' }}
            </el-tag>
            <span v-if="chainView.action.frontDeskAt" class="sub" style="margin-left:8px">
              前台 {{ chainView.action.frontDeskBy }} 登记于 {{ fmtTime(chainView.action.frontDeskAt) }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="等候补偿">{{ chainView.action.compensationNote || '-' }}</el-descriptions-item>
          <el-descriptions-item label="交通调整">{{ chainView.action.transportAdjustNote || '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-descriptions :column="1" border size="small" title="机位消毒" style="margin-top:16px">
          <el-descriptions-item label="当前机位">
            {{ chainView.chain.machine.code }}（{{ label('MachineZone', chainView.chain.machine.zone) }}）
            <el-tag size="small" :type="tagType('MachineStatus', chainView.chain.machine.status)" style="margin-left:6px">
              {{ label('MachineStatus', chainView.chain.machine.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="最近消毒">{{ fmtTime(chainView.chain.machine.lastDisinfectedAt) }}</el-descriptions-item>
          <el-descriptions-item label="当日消毒记录">
            <div v-if="chainView.chain.disinfectionsToday.length">
              <div v-for="r in chainView.chain.disinfectionsToday" :key="r.id">
                {{ fmtTime(r.doneAt).slice(11) }} {{ label('DisinfectionType', r.type) }}
                <el-tag size="small" :type="tagType('DisinfectionResult', r.result)">
                  {{ label('DisinfectionResult', r.result) }}
                </el-tag>
              </div>
            </div>
            <span v-else>当日暂无消毒记录</span>
          </el-descriptions-item>
        </el-descriptions>

        <el-card style="margin-top:16px" shadow="never">
          <template #header><span>耗材领用流水（本治疗单）</span></template>
          <el-table :data="chainView.chain.consumables" size="small" max-height="260">
            <el-table-column prop="consumableName" label="耗材" width="130" show-overflow-tooltip />
            <el-table-column label="类型" width="60">
              <template #default="{ row }">{{ label('TxnType', row.type) }}</template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" width="60" align="center" />
            <el-table-column prop="note" label="备注" show-overflow-tooltip />
          </el-table>
        </el-card>
      </template>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Warning } from '@element-plus/icons-vue'
import dayjs from 'dayjs'
import api from '../api'
import { label, tagType, fmtTime } from '../utils/labels'

const plans = ref([])
const plansLoading = ref(false)
const filterDate = ref(dayjs().format('YYYY-MM-DD'))
const detail = ref(null)
const detailLoading = ref(false)
const machines = ref([])
const submitting = ref(false)

const createDialog = ref(false)
const createForm = reactive({ machineId: null, reason: '', expectedRepairAt: null })

const decideDialog = ref(false)
const decideForm = reactive({ action: null, type: 'DELAY', delayMinutes: 60, targetMachineId: null, note: '' })

const swapDialog = ref(false)
const swapForm = reactive({ action: null, candidates: [], otherSessionId: null, confirmByA: '', confirmByB: '', confirmNote: '' })

const notifyDialog = ref(false)
const notifyForm = reactive({ action: null, channel: '电话', note: '', familyPickupNote: '' })

const arrivalDialog = ref(false)
const arrivalForm = reactive({ action: null, compensationNote: '', transportAdjustNote: '' })

const repairDialog = ref(false)
const repairTime = ref(null)

const chainDrawer = ref(false)
const chainView = ref(null)

const zoneColors = { NORMAL: '#67c23a', HBV: '#e6a23c', HCV: '#b37feb', ISOLATION: '#f56c6c' }
const zoneColor = (z) => zoneColors[z] || '#909399'

const machineOptions = computed(() => {
  const opts = decideForm.action?.backupOptions || []
  return opts
})
const slotOf = (o) => fmtTime(decideForm.type === 'SHIFT_PM' ? o.pmSlot : o.sameShiftSlot).slice(11)

async function loadPlans() {
  plansLoading.value = true
  try {
    plans.value = await api.reschedulePlans(filterDate.value ? { date: filterDate.value } : {})
    if (detail.value) {
      const still = plans.value.find(p => p.plan.id === detail.value.plan.id)
      if (still) await selectPlan(still.plan.id)
    } else if (plans.value.length) {
      await selectPlan(plans.value[0].plan.id)
    }
  } finally {
    plansLoading.value = false
  }
}

async function selectPlan(id) {
  detailLoading.value = true
  try {
    detail.value = await api.rescheduleDetail(id)
  } finally {
    detailLoading.value = false
  }
}

function openCreate() {
  createForm.machineId = null
  createForm.reason = ''
  createForm.expectedRepairAt = null
  createDialog.value = true
}

async function doCreate() {
  if (!createForm.machineId || !createForm.reason.trim()) {
    ElMessage.warning('请选择故障机位并填写故障原因')
    return
  }
  submitting.value = true
  try {
    const d = await api.createReschedulePlan({ ...createForm })
    ElMessage.success(`方案已创建：筛出 ${d.actions.length} 名受影响患者`)
    createDialog.value = false
    await loadPlans()
    detail.value = d
  } finally {
    submitting.value = false
  }
}

function openDecide(row, type) {
  decideForm.action = row
  decideForm.type = type
  decideForm.delayMinutes = 60
  decideForm.targetMachineId = null
  decideForm.note = ''
  decideDialog.value = true
}

async function doDecide() {
  if (decideForm.type === 'CHANGE_MACHINE' && !decideForm.targetMachineId) {
    ElMessage.warning('请选择备用机位')
    return
  }
  submitting.value = true
  try {
    detail.value = await api.decideReschedule(decideForm.action.action.id, {
      actionType: decideForm.type,
      targetMachineId: decideForm.targetMachineId,
      delayMinutes: decideForm.type === 'DELAY' ? decideForm.delayMinutes : null,
      note: decideForm.note
    })
    ElMessage.success('改排已执行，耗材与消毒计划已联动更新')
    decideDialog.value = false
    await loadPlans()
  } finally {
    submitting.value = false
  }
}

async function openSwap(row) {
  swapForm.action = row
  swapForm.otherSessionId = null
  swapForm.confirmByA = ''
  swapForm.confirmByB = ''
  swapForm.confirmNote = ''
  swapForm.candidates = await api.swapCandidates(row.action.id)
  swapDialog.value = true
}

async function doSwap() {
  if (!swapForm.otherSessionId || !swapForm.confirmByA.trim() || !swapForm.confirmByB.trim()) {
    ElMessage.warning('请选择对方患者并填写双方确认人')
    return
  }
  submitting.value = true
  try {
    detail.value = await api.swapReschedule(swapForm.action.action.id, {
      otherSessionId: swapForm.otherSessionId,
      confirmByA: swapForm.confirmByA,
      confirmByB: swapForm.confirmByB,
      confirmNote: swapForm.confirmNote
    })
    ElMessage.success('调换完成，双方确认已留痕')
    swapDialog.value = false
    await loadPlans()
  } finally {
    submitting.value = false
  }
}

function openNotify(row) {
  notifyForm.action = row
  notifyForm.channel = '电话'
  notifyForm.note = ''
  notifyForm.familyPickupNote = ''
  notifyDialog.value = true
}

async function doNotify() {
  submitting.value = true
  try {
    detail.value = await api.notifyReschedule(notifyForm.action.action.id, {
      channel: notifyForm.channel,
      note: notifyForm.note,
      familyPickupNote: notifyForm.familyPickupNote
    })
    ElMessage.success('患者通知已登记')
    notifyDialog.value = false
    await loadPlans()
  } finally {
    submitting.value = false
  }
}

function openArrival(row) {
  arrivalForm.action = row
  arrivalForm.compensationNote = row.action.compensationNote || ''
  arrivalForm.transportAdjustNote = row.action.transportAdjustNote || ''
  arrivalDialog.value = true
}

async function doArrival() {
  submitting.value = true
  try {
    detail.value = await api.arrivalReschedule(arrivalForm.action.action.id, {
      compensationNote: arrivalForm.compensationNote,
      transportAdjustNote: arrivalForm.transportAdjustNote
    })
    ElMessage.success('到院登记已保存')
    arrivalDialog.value = false
    await loadPlans()
  } finally {
    submitting.value = false
  }
}

function openRepair() {
  repairTime.value = detail.value.plan.expectedRepairAt
  repairDialog.value = true
}

async function doRepair() {
  if (!repairTime.value) {
    ElMessage.warning('请选择预计修复时间')
    return
  }
  submitting.value = true
  try {
    detail.value = await api.updateRepairTime(detail.value.plan.id, repairTime.value)
    ElMessage.success('预计修复时间已更新')
    repairDialog.value = false
  } finally {
    submitting.value = false
  }
}

async function closePlan() {
  await ElMessageBox.confirm(
    '关闭前请确认：全部患者已完成改排决策并通知到位。关闭后不可再操作。',
    '闭环改排方案', { type: 'warning', confirmButtonText: '确认闭环', cancelButtonText: '再检查一下' }
  )
  detail.value = await api.closeReschedulePlan(detail.value.plan.id)
  ElMessage.success('方案已闭环')
  await loadPlans()
}

function openChain(row) {
  chainView.value = row
  chainDrawer.value = true
}

onMounted(async () => {
  machines.value = (await api.machines()).filter(m => m.active)
  await loadPlans()
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; }
.title { font-size: 15px; font-weight: 600; }
.spacer { flex: 1; }
.head { display: flex; justify-content: space-between; align-items: center; }
.sub { font-size: 12px; color: #909399; }
.arrived { font-size: 12px; color: #e6a23c; font-weight: 600; }
.decide-btns { display: flex; flex-wrap: wrap; gap: 4px; }
.decision-text { margin-left: 6px; font-size: 13px; }
.chain-icon {
  display: inline-flex; align-items: center; justify-content: center;
  width: 22px; height: 22px; border-radius: 50%; font-size: 12px; color: #fff; margin-right: 4px;
}
.chain-icon.ok { background: #67c23a; }
.chain-icon.bad { background: #f56c6c; }
.chain-icon.na { background: #c0c4cc; }
</style>
