<template>
  <div v-loading="loading">
    <el-alert v-if="data.openReschedulePlans > 0" type="error" :closable="false" style="margin-bottom:16px">
      <template #title>
        有 {{ data.openReschedulePlans }} 个机位故障改排方案进行中，受影响患者待处置
        <el-button size="small" type="danger" plain style="margin-left:12px"
                   @click="$router.push('/reschedule')">前往故障改排</el-button>
      </template>
    </el-alert>
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-num">{{ data.todaySessionTotal || 0 }}</div>
          <div class="stat-label">今日治疗单</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-num" style="color:#409eff">{{ data.todayByStatus?.ON_DIALYSIS || 0 }}</div>
          <div class="stat-label">透析中</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-num" style="color:#f56c6c">{{ data.openExceptionCount || 0 }}</div>
          <div class="stat-label">待处理异常</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover" class="stat-card">
          <div class="stat-num" style="color:#e6a23c">{{ data.lowStock?.length || 0 }}</div>
          <div class="stat-label">耗材预警</div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>机位状态</span>
              <el-button text type="primary" @click="$router.push('/schedule')">进入排班看板</el-button>
            </div>
          </template>
          <div class="machine-grid">
            <div v-for="m in machines" :key="m.id" class="machine-cell" :class="'ms-' + m.status">
              <div class="mc-code">{{ m.code }}</div>
              <div class="mc-zone">{{ label('MachineZone', m.zone) }}</div>
              <div class="mc-status">{{ label('MachineStatus', m.status) }}</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>最新异常</span>
              <el-button text type="primary" @click="$router.push('/exceptions')">全部异常</el-button>
            </div>
          </template>
          <el-table :data="data.latestExceptions || []" size="small" max-height="330">
            <el-table-column label="类型" width="110">
              <template #default="{ row }">
                <el-tag size="small" :type="row.status === 'OPEN' ? 'danger' : 'warning'">
                  {{ label('ExceptionType', row.type) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="description" label="描述" show-overflow-tooltip />
            <el-table-column label="时间" width="90">
              <template #default="{ row }">{{ fmtTime(row.createdAt).slice(5) }}</template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="16" style="margin-top:16px">
      <el-col :span="14">
        <el-card>
          <template #header><span>今日治疗单状态分布</span></template>
          <div class="status-chips">
            <el-tag v-for="(count, status) in data.todayByStatus" :key="status"
                    :type="tagType('SessionStatus', status)" size="large" class="chip">
              {{ label('SessionStatus', status) }} × {{ count }}
            </el-tag>
            <span v-if="!data.todaySessionTotal" class="empty">今日暂无治疗单，请到排班看板执行自动排班</span>
          </div>
        </el-card>
      </el-col>
      <el-col :span="10">
        <el-card>
          <template #header><span>耗材库存预警</span></template>
          <el-table :data="data.lowStock || []" size="small" max-height="180">
            <el-table-column prop="name" label="耗材" />
            <el-table-column label="库存" width="120">
              <template #default="{ row }">
                <span style="color:#f56c6c;font-weight:600">{{ row.stock }}</span>
                / {{ row.safeStock }} {{ row.unit }}
              </template>
            </el-table-column>
            <el-table-column label="状态" width="90">
              <template #default><el-tag type="danger" size="small">需补货</el-tag></template>
            </el-table-column>
          </el-table>
          <div v-if="!(data.lowStock || []).length" class="empty">库存充足</div>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import api from '../api'
import { label, tagType, fmtTime } from '../utils/labels'

const loading = ref(false)
const data = ref({})
const machines = ref([])

onMounted(async () => {
  loading.value = true
  try {
    const [dash, ms] = await Promise.all([api.dashboard(), api.machines()])
    data.value = dash
    machines.value = ms.filter(m => m.active)
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
.stat-card { text-align: center; }
.stat-num { font-size: 32px; font-weight: 700; color: #303133; }
.stat-label { color: #909399; margin-top: 4px; }
.card-header { display: flex; justify-content: space-between; align-items: center; }
.machine-grid { display: grid; grid-template-columns: repeat(6, 1fr); gap: 10px; }
.machine-cell {
  border-radius: 6px; padding: 10px 6px; text-align: center; color: #fff; font-size: 12px;
}
.mc-code { font-size: 16px; font-weight: 700; }
.mc-zone { opacity: .85; margin: 2px 0; }
.ms-IDLE { background: #67c23a; }
.ms-IN_USE { background: #409eff; }
.ms-DISINFECTING { background: #e6a23c; }
.ms-FAULT { background: #f56c6c; }
.ms-MAINTENANCE { background: #909399; }
.status-chips { display: flex; flex-wrap: wrap; gap: 10px; }
.chip { font-size: 14px; }
.empty { color: #909399; font-size: 13px; }
</style>
