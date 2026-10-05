<template>
  <div>
    <el-card>
      <div class="toolbar">
        <el-date-picker v-model="date" type="date" value-format="YYYY-MM-DD" :clearable="false"
                        @change="loadAll" />
        <el-select v-model="machineId" placeholder="选择故障机位" style="width:260px" @change="screen">
          <el-option v-for="m in machines" :key="m.id" :value="m.id"
                     :label="`${m.code}（${label('MachineZone', m.zone)} / ${label('MachineStatus', m.status)}）`">
            <span>{{ m.code }}（{{ label('MachineZone', m.zone) }}）</span>
            <el-tag size="small" :type="tagType('MachineStatus', m.status)" style="float:right">
              {{ label('MachineStatus', m.status) }}
            </el-tag>
          </el-option>
        </el-select>
        <el-button type="primary" :icon="Search" :loading="loading" @click="screen">筛查受影响患者</el-button>
        <div class="spacer" />
        <el-button :icon="Refresh" @click="loadAll">刷新</el-button>
      </div>

      <template v-if="result">
        <el-alert :type="result.affectedSessions.length ? 'error' : 'success'" :closable="false" class="summary">
          <template #title>
            机位 {{ result.machine.code }}（{{ label('MachineZone', result.machine.zone) }}）当前状态
            「{{ label('MachineStatus', result.machine.status) }}」：当天受影响患者
            <b>{{ result.affectedSessions.length }}</b> 人
            <span v-if="result.onDialysisSessions.length" style="color:#e6a23c">
              ；另有 {{ result.onDialysisSessions.length }} 人透析中（{{ result.onDialysisSessions.map(s => s.patientName).join('、') }}），需床旁人工处置
            </span>
            <span v-if="!result.affectedSessions.length">，无需改排</span>
          </template>
        </el-alert>

        <el-row :gutter="16" v-if="result.affectedSessions.length">
          <el-col :span="15">
            <div class="block-title">受影响患者（含传染病分区要求）</div>
            <el-table :data="result.affectedSessions" border stripe size="small">
              <el-table-column label="患者" width="90">
                <template #default="{ row }"><b>{{ row.patientName }}</b></template>
              </el-table-column>
              <el-table-column label="感染标记" width="80" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="tagType('InfectionMarker', row.infectionMarker)">
                    {{ label('InfectionMarker', row.infectionMarker) }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分区要求" min-width="110">
                <template #default="{ row }">
                  <el-tag v-for="z in row.requiredZones" :key="z" size="small" effect="plain"
                          style="margin-right:4px">{{ label('MachineZone', z) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="班次" width="70" align="center">
                <template #default="{ row }">{{ label('Shift', row.shift) }}</template>
              </el-table-column>
              <el-table-column label="计划开始" width="90">
                <template #default="{ row }">{{ fmtTime(row.plannedStart).slice(11) }}</template>
              </el-table-column>
              <el-table-column label="接送" width="100">
                <template #default="{ row }">
                  {{ label('TransportMode', row.transportMode) }}
                  <div v-if="row.transportWindow" class="sub">{{ row.transportWindow }}</div>
                </template>
              </el-table-column>
              <el-table-column label="改排操作" width="240" fixed="right">
                <template #default="{ row }">
                  <el-button size="small" text type="warning" @click="openAction(row, 'DELAY')">延后</el-button>
                  <el-button size="small" text type="primary" @click="openAction(row, 'SWITCH_MACHINE')">换机</el-button>
                  <el-button size="small" text type="success" @click="openAction(row, 'SHIFT_CHANGE')">改班次</el-button>
                  <el-button size="small" text type="danger" @click="openAction(row, 'SWAP')">调换</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-col>
          <el-col :span="9">
            <div class="block-title">可用备用机位（下一可用上机时刻）</div>
            <el-table :data="result.spareMachines" border stripe size="small" max-height="420">
              <el-table-column label="机位" width="70">
                <template #default="{ row }">
                  <el-tag size="small" effect="dark" :color="zoneColor(row.zone)" style="border:none">
                    {{ row.code }}
                  </el-tag>
                </template>
              </el-table-column>
              <el-table-column label="分区" width="70" align="center">
                <template #default="{ row }">{{ label('MachineZone', row.zone) }}</template>
              </el-table-column>
              <el-table-column label="今日已排" width="70" align="center">
                <template #default="{ row }">{{ row.todaySessions }}</template>
              </el-table-column>
              <el-table-column label="上午班" width="80">
                <template #default="{ row }">{{ fmtTime(row.nextSlots.AM).slice(11) }}</template>
              </el-table-column>
              <el-table-column label="下午班" width="80">
                <template #default="{ row }">{{ fmtTime(row.nextSlots.PM).slice(11) }}</template>
              </el-table-column>
              <el-table-column label="晚班" width="80">
                <template #default="{ row }">{{ fmtTime(row.nextSlots.EVE).slice(11) }}</template>
              </el-table-column>
            </el-table>
          </el-col>
        </el-row>
      </template>
      <el-empty v-else description="选择故障机位后点击「筛查受影响患者」" />
    </el-card>

    <el-card style="margin-top:16px">
      <template #header><span>改排台账（{{ date }}）</span></template>
      <el-table :data="records" border stripe size="small">
        <el-table-column label="时间" width="150">
          <template #default="{ row }">{{ fmtTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column prop="patientName" label="患者" width="90" />
        <el-table-column prop="faultMachineCode" label="故障机位" width="80" align="center" />
        <el-table-column label="方式" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagType('RescheduleAction', row.action)">
              {{ label('RescheduleAction', row.action) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="改排结果" min-width="200">
          <template #default="{ row }">
            {{ row.fromMachineCode }} {{ label('Shift', row.fromShift) }} {{ fmtTime(row.fromPlannedStart).slice(11) }}
            → {{ row.toMachineCode }} {{ label('Shift', row.toShift) }} {{ fmtTime(row.toPlannedStart).slice(11) }}
            <div v-if="row.action === 'SWAP'" class="sub">
              与「{{ row.swapPatientName }}」互换；双方确认：{{ row.confirmA }} / {{ row.confirmB }}
            </div>
          </template>
        </el-table-column>
        <el-table-column label="到院补偿 / 交通调整" min-width="180">
          <template #default="{ row }">
            <template v-if="row.arrived">
              <div>{{ row.compensation }}</div>
              <div class="sub">{{ row.transportAdjust }}</div>
            </template>
            <span v-else class="sub">未到院</span>
          </template>
        </el-table-column>
        <el-table-column label="患者通知" width="130">
          <template #default="{ row }">
            {{ row.notifyChannel ? label('NotifyChannel', row.notifyChannel) : '-' }}
            <div v-if="row.familyNotified" class="sub">家属已通知</div>
          </template>
        </el-table-column>
        <el-table-column prop="operator" label="经办" width="110" />
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button size="small" text type="primary" @click="openChain(row)">联动链</el-button>
          </template>
        </el-table-column>
        <template #empty><el-empty description="当天暂无改排记录" /></template>
      </el-table>
    </el-card>

    <!-- 改排执行对话框 -->
    <el-dialog v-model="actionDialog" :title="`故障改排 - ${label('RescheduleAction', form.action)}`" width="620px">
      <el-alert type="info" :closable="false" style="margin-bottom:12px">
        <template #title>
          患者「{{ target?.patientName }}」原安排：机位 {{ result?.machine.code }}
          {{ label('Shift', target?.shift) }} {{ fmtTime(target?.plannedStart).slice(11) }}
          ；分区要求：{{ (target?.requiredZones || []).map(z => label('MachineZone', z)).join(' / ') }}
        </template>
      </el-alert>

      <el-form label-width="110px">
        <!-- 延后 -->
        <template v-if="form.action === 'DELAY'">
          <el-form-item label="延后时长">
            <el-input-number v-model="form.delayMinutes" :min="10" :max="480" :step="10" />
            <span style="margin-left:8px">分钟（等故障机位修复）</span>
          </el-form-item>
          <el-form-item label="新预计开始">
            <b>{{ delayedStart }}</b>
          </el-form-item>
        </template>

        <!-- 换机 -->
        <template v-if="form.action === 'SWITCH_MACHINE'">
          <el-form-item label="备用机位">
            <el-select v-model="form.toMachineId" placeholder="仅列出分区匹配的机位" style="width:100%">
              <el-option v-for="m in compatibleSpares" :key="m.machineId" :value="m.machineId"
                         :label="`${m.code}（${label('MachineZone', m.zone)}，本班下一可用 ${fmtTime(m.nextSlots[target?.shift]).slice(11)}）`" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 改班次 -->
        <template v-if="form.action === 'SHIFT_CHANGE'">
          <el-form-item label="目标班次">
            <el-radio-group v-model="form.toShift">
              <el-radio-button v-for="o in shiftOptions" :key="o.value" :value="o.value"
                               :disabled="o.value === target?.shift">{{ o.label }}</el-radio-button>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="备用机位">
            <el-select v-model="form.toMachineId" clearable placeholder="不选则自动分配最早可用机位" style="width:100%">
              <el-option v-for="m in compatibleSpares" :key="m.machineId" :value="m.machineId"
                         :label="`${m.code}（${label('MachineZone', m.zone)}，该班下一可用 ${fmtTime(m.nextSlots[form.toShift]).slice(11)}）`" />
            </el-select>
          </el-form-item>
        </template>

        <!-- 协商调换 -->
        <template v-if="form.action === 'SWAP'">
          <el-form-item label="调换对象">
            <el-select v-model="form.swapSessionId" style="width:100%"
                       placeholder="选择协商调换的治疗单（仅可互换排位）">
              <el-option v-for="c in swapList" :key="c.sessionId" :value="c.sessionId"
                         :disabled="!c.compatible"
                         :label="`${c.patientName}｜${c.machineCode} ${label('Shift', c.shift)} ${fmtTime(c.plannedStart).slice(11)}${c.compatible ? '' : '（' + c.reason + '）'}`" />
            </el-select>
          </el-form-item>
          <el-form-item label="本方确认人" required>
            <el-input v-model="form.confirmA" placeholder="本患者/家属确认人姓名" />
          </el-form-item>
          <el-form-item label="对方确认人" required>
            <el-input v-model="form.confirmB" placeholder="对方患者/家属确认人姓名" />
          </el-form-item>
          <el-alert type="warning" :closable="false" title="双方确认人将写入台账，防止口头换班争议" />
        </template>

        <el-divider />

        <el-form-item label="患者已到院">
          <el-checkbox v-model="form.arrived">已到院（前台需记录等候补偿与交通调整）</el-checkbox>
        </el-form-item>
        <template v-if="form.arrived">
          <el-form-item label="等候补偿" required>
            <el-input v-model="form.compensation" placeholder="如：等候 40 分钟，补偿交通费 20 元" />
          </el-form-item>
          <el-form-item label="交通调整" required>
            <el-input v-model="form.transportAdjust" placeholder="如：班车改约 15:00 接回" />
          </el-form-item>
        </template>

        <el-form-item label="患者通知">
          <el-select v-model="form.notifyChannel" placeholder="通知方式" style="width:200px">
            <el-option v-for="o in options('NotifyChannel')" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
          <el-checkbox v-model="form.familyNotified" style="margin-left:16px">家属/班车已通知</el-checkbox>
        </el-form-item>
        <el-form-item label="通知备注">
          <el-input v-model="form.notifyNote" placeholder="如：已电话告知改到下午班，患者接受" />
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="actionDialog = false">取消</el-button>
        <el-button type="primary" :loading="applying" @click="submit">确认改排</el-button>
      </template>
    </el-dialog>

    <!-- 联动链 -->
    <el-dialog v-model="chainDialog" title="改排联动链（患者通知 / 家属接送 / 机位消毒 / 耗材领用）" width="640px">
      <el-timeline v-if="chain">
        <el-timeline-item v-for="link in chain.links" :key="link.key"
                          :type="link.done ? 'success' : 'warning'"
                          :icon="link.done ? CircleCheck : Warning">
          <div class="chain-title">
            {{ link.title }}
            <el-tag size="small" :type="link.done ? 'success' : 'warning'">
              {{ link.done ? '已落实' : '待跟进' }}
            </el-tag>
          </div>
          <div class="chain-detail">{{ link.detail }}</div>
          <div v-if="link.items && link.key === 'consumable'" class="chain-items">
            <el-tag v-for="(it, i) in link.items" :key="i" size="small" style="margin:0 6px 4px 0">
              {{ it.consumableName }} ×{{ it.qty }}
            </el-tag>
          </div>
        </el-timeline-item>
      </el-timeline>
      <div v-if="chain" class="chain-foot">
        经办：{{ chain.record.operator }} ｜ {{ fmtTime(chain.record.createdAt) }}
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search, Refresh, CircleCheck, Warning } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, options, fmtTime } from '../utils/labels'
import dayjs from 'dayjs'

const route = useRoute()
const date = ref(dayjs().format('YYYY-MM-DD'))
const machineId = ref(null)
const machines = ref([])
const result = ref(null)
const records = ref([])
const loading = ref(false)

const actionDialog = ref(false)
const target = ref(null)
const applying = ref(false)
const swapList = ref([])
const form = reactive({
  action: 'DELAY',
  delayMinutes: 60,
  toMachineId: null,
  toShift: 'PM',
  swapSessionId: null,
  confirmA: '',
  confirmB: '',
  arrived: false,
  compensation: '',
  transportAdjust: '',
  notifyChannel: 'PHONE',
  notifyNote: '',
  familyNotified: false
})

const chainDialog = ref(false)
const chain = ref(null)

const zoneColors = { NORMAL: '#67c23a', HBV: '#e6a23c', HCV: '#b37feb', ISOLATION: '#f56c6c' }
const zoneColor = (z) => zoneColors[z] || '#909399'
const shiftOptions = options('Shift')

// 与当前患者分区要求匹配的备用机位
const compatibleSpares = computed(() => {
  if (!result.value || !target.value) return []
  return result.value.spareMachines.filter(m => target.value.requiredZones.includes(m.zone))
})

const delayedStart = computed(() => {
  if (!target.value?.plannedStart) return '-'
  return fmtTime(dayjs(target.value.plannedStart).add(form.delayMinutes, 'minute').format('YYYY-MM-DD HH:mm'))
})

async function loadMachines() {
  machines.value = (await api.machines()).filter(m => m.active)
}

async function screen() {
  if (!machineId.value) {
    ElMessage.warning('请先选择故障机位')
    return
  }
  loading.value = true
  try {
    result.value = await api.rescheduleOptions(machineId.value, date.value)
  } finally {
    loading.value = false
  }
}

async function loadRecords() {
  records.value = await api.rescheduleRecords(date.value)
}

async function loadAll() {
  await loadMachines()
  const tasks = [loadRecords()]
  if (machineId.value) tasks.push(screen())
  await Promise.all(tasks)
}

async function openAction(row, action) {
  target.value = row
  Object.assign(form, {
    action,
    delayMinutes: 60,
    toMachineId: null,
    toShift: row.shift === 'AM' ? 'PM' : 'AM',
    swapSessionId: null,
    confirmA: '',
    confirmB: '',
    arrived: false,
    compensation: '',
    transportAdjust: '',
    notifyChannel: 'PHONE',
    notifyNote: '',
    familyNotified: false
  })
  if (action === 'SWAP') {
    swapList.value = await api.swapCandidates(row.sessionId)
    if (!swapList.value.some(c => c.compatible)) {
      ElMessage.warning('当天没有分区匹配、可互换排位的治疗单')
      return
    }
  }
  if (action === 'SWITCH_MACHINE' && !compatibleSpares.value.length) {
    ElMessage.warning('没有分区匹配的可用备用机位，可考虑延后或改班次')
    return
  }
  actionDialog.value = true
}

async function submit() {
  if (form.action === 'SWAP' && (!form.confirmA.trim() || !form.confirmB.trim())) {
    ElMessage.warning('协商调换必须填写双方确认人')
    return
  }
  if (form.arrived && (!form.compensation.trim() || !form.transportAdjust.trim())) {
    ElMessage.warning('患者已到院，请填写等候补偿与交通调整')
    return
  }
  applying.value = true
  try {
    const res = await api.rescheduleApply({
      sessionId: target.value.sessionId,
      action: form.action,
      faultMachineId: result.value.machine.id,
      toMachineId: form.toMachineId,
      toShift: form.action === 'SHIFT_CHANGE' ? form.toShift : null,
      delayMinutes: form.action === 'DELAY' ? form.delayMinutes : null,
      swapSessionId: form.swapSessionId,
      confirmA: form.confirmA,
      confirmB: form.confirmB,
      arrived: form.arrived,
      compensation: form.compensation,
      transportAdjust: form.transportAdjust,
      notifyChannel: form.notifyChannel,
      notifyNote: form.notifyNote,
      familyNotified: form.familyNotified
    })
    actionDialog.value = false
    if (res.warnings?.length) {
      ElMessageBox.alert(res.warnings.join('；'), '改排成功，请注意', { type: 'warning' })
    } else {
      ElMessage.success('改排成功，患者通知 / 家属接送 / 机位消毒 / 耗材领用已串联')
    }
    chain.value = res.chain
    chainDialog.value = true
    await loadAll()
  } finally {
    applying.value = false
  }
}

async function openChain(row) {
  chain.value = await api.rescheduleChain(row.id)
  chainDialog.value = true
}

onMounted(async () => {
  await loadMachines()
  if (route.query.machineId) {
    machineId.value = Number(route.query.machineId)
    await screen()
  } else {
    // 默认选中第一台故障/维护机位
    const faulty = machines.value.find(m => m.status === 'FAULT' || m.status === 'MAINTENANCE')
    if (faulty) {
      machineId.value = faulty.id
      await screen()
    }
  }
  await loadRecords()
})
</script>

<style scoped>
.toolbar { display: flex; align-items: center; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.summary { margin-bottom: 12px; }
.block-title { font-size: 14px; font-weight: 600; color: #303133; margin: 4px 0 8px; }
.sub { font-size: 12px; color: #909399; }
.chain-title { font-weight: 600; display: flex; align-items: center; gap: 8px; }
.chain-detail { color: #606266; font-size: 13px; margin-top: 4px; }
.chain-items { margin-top: 6px; }
.chain-foot { text-align: right; color: #909399; font-size: 12px; }
</style>
