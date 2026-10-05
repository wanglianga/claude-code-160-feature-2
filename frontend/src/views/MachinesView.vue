<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="13">
        <el-card>
          <template #header>
            <div class="head">
              <span>透析机 / 机位</span>
              <el-button size="small" :icon="Plus" @click="openEdit(null)">新增机位</el-button>
            </div>
          </template>
          <el-table :data="machines" v-loading="loading" border stripe size="small">
            <el-table-column prop="code" label="机位" width="70" />
            <el-table-column prop="model" label="型号" width="140" />
            <el-table-column label="分区" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" effect="dark" :color="zoneColor(row.zone)" style="border:none">
                  {{ label('MachineZone', row.zone) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType('MachineStatus', row.status)">
                  {{ label('MachineStatus', row.status) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="消毒间隔" width="80" align="center">
              <template #default="{ row }">{{ row.disinfectBufferMin }}分</template>
            </el-table-column>
            <el-table-column label="最近消毒" width="150">
              <template #default="{ row }">{{ fmtTime(row.lastDisinfectedAt) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="210" fixed="right">
              <template #default="{ row }">
                <el-button size="small" text type="primary" @click="openRecord(row)">登记记录</el-button>
                <el-button size="small" text type="warning" @click="openEdit(row)">编辑</el-button>
                <el-dropdown style="margin-left:8px" @command="(s) => changeStatus(row, s)">
                  <el-button size="small" text type="danger">状态<el-icon><ArrowDown /></el-icon></el-button>
                  <template #dropdown>
                    <el-dropdown-menu>
                      <el-dropdown-item v-for="o in options('MachineStatus')" :key="o.value" :command="o.value">
                        {{ o.label }}
                      </el-dropdown-item>
                    </el-dropdown-menu>
                  </template>
                </el-dropdown>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="11">
        <el-card>
          <template #header><span>工程记录（消毒 / 滤芯 / 水处理）</span></template>
          <el-table :data="records" size="small" max-height="560">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">{{ label('DisinfectionType', row.type) }}</template>
            </el-table-column>
            <el-table-column prop="machineCode" label="机位" width="70">
              <template #default="{ row }">{{ row.machineCode || '水处理间' }}</template>
            </el-table-column>
            <el-table-column label="结果" width="70">
              <template #default="{ row }">
                <el-tag size="small" :type="tagType('DisinfectionResult', row.result)">
                  {{ label('DisinfectionResult', row.result) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="operatorName" label="操作人" width="80" />
            <el-table-column label="时间" width="140">
              <template #default="{ row }">{{ fmtTime(row.doneAt) }}</template>
            </el-table-column>
            <el-table-column prop="note" label="备注" show-overflow-tooltip />
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <!-- 登记工程记录 -->
    <el-dialog v-model="recordDialog" title="登记工程记录" width="480px">
      <el-form :model="recordForm" label-width="90px">
        <el-form-item label="机位">
          <el-select v-model="recordForm.machineId" clearable placeholder="水处理记录可不选" style="width:100%">
            <el-option v-for="m in machines" :key="m.id" :value="m.id" :label="m.code" />
          </el-select>
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="recordForm.type" style="width:100%">
            <el-option v-for="o in options('DisinfectionType')" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="结果">
          <el-radio-group v-model="recordForm.result">
            <el-radio value="PASS">合格</el-radio>
            <el-radio value="FAIL">不合格</el-radio>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="recordForm.note" type="textarea" :rows="2" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="recordDialog = false">取消</el-button>
        <el-button type="primary" @click="saveRecord">保存</el-button>
      </template>
    </el-dialog>

    <!-- 编辑机位 -->
    <el-dialog v-model="editDialog" :title="editForm.id ? '编辑机位' : '新增机位'" width="440px">
      <el-form :model="editForm" label-width="100px">
        <el-form-item label="机位号"><el-input v-model="editForm.code" :disabled="!!editForm.id" /></el-form-item>
        <el-form-item label="型号"><el-input v-model="editForm.model" /></el-form-item>
        <el-form-item label="分区">
          <el-select v-model="editForm.zone" style="width:100%">
            <el-option v-for="o in options('MachineZone')" :key="o.value" :value="o.value" :label="o.label" />
          </el-select>
        </el-form-item>
        <el-form-item label="消毒间隔(分)">
          <el-input-number v-model="editForm.disinfectBufferMin" :min="10" :max="120" style="width:100%" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="editForm.active" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editDialog = false">取消</el-button>
        <el-button type="primary" @click="saveMachine">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import api from '../api'
import { label, tagType, options, fmtTime } from '../utils/labels'

const router = useRouter()

const machines = ref([])
const records = ref([])
const loading = ref(false)
const recordDialog = ref(false)
const editDialog = ref(false)
const recordForm = reactive({ machineId: null, type: 'DAILY', result: 'PASS', note: '' })
const editForm = reactive({})

const zoneColors = { NORMAL: '#67c23a', HBV: '#e6a23c', HCV: '#b37feb', ISOLATION: '#f56c6c' }
const zoneColor = (z) => zoneColors[z] || '#909399'

async function load() {
  loading.value = true
  try {
    const [ms, rs] = await Promise.all([api.machines(), api.disinfections()])
    machines.value = ms
    records.value = rs
  } finally {
    loading.value = false
  }
}

function openRecord(row) {
  recordForm.machineId = row?.id ?? null
  recordForm.type = 'DAILY'
  recordForm.result = 'PASS'
  recordForm.note = ''
  recordDialog.value = true
}

async function saveRecord() {
  await api.createDisinfection(recordForm)
  ElMessage.success('工程记录已保存')
  recordDialog.value = false
  await load()
}

function openEdit(row) {
  if (row) {
    Object.assign(editForm, JSON.parse(JSON.stringify(row)))
  } else {
    Object.keys(editForm).forEach(k => delete editForm[k])
    Object.assign(editForm, { code: '', model: '', zone: 'NORMAL', disinfectBufferMin: 40, active: true })
  }
  editDialog.value = true
}

async function saveMachine() {
  if (editForm.id) {
    await api.updateMachine(editForm.id, editForm)
  } else {
    await api.createMachine(editForm)
  }
  ElMessage.success('已保存')
  editDialog.value = false
  await load()
}

async function changeStatus(row, status) {
  let note = ''
  if (status === 'FAULT' || status === 'MAINTENANCE') {
    const { value } = await ElMessageBox.prompt('故障/维护说明', `机位 ${row.code} 状态变更`, {
      confirmButtonText: '确定', cancelButtonText: '取消'
    })
    note = value || ''
  }
  const res = await api.changeMachineStatus(row.id, status, note)
  if (res.affectedSessions?.length) {
    ElMessageBox.confirm(
      `今日受影响患者：${res.affectedSessions.map(s => s.patient.name).join('、')}。${res.rescheduleTip || '请到排班看板调班'}`,
      '已上报机器故障异常',
      { type: 'warning', confirmButtonText: '前往故障改排', cancelButtonText: '稍后处理' }
    ).then(() => router.push('/reschedule')).catch(() => {})
  } else {
    ElMessage.success('状态已更新')
  }
  await load()
}

onMounted(load)
</script>

<style scoped>
.head { display: flex; justify-content: space-between; align-items: center; }
</style>
