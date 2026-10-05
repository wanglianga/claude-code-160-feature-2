import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use(config => {
  const token = localStorage.getItem('hd_token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  res => res.data,
  err => {
    const status = err.response?.status
    const message = err.response?.data?.message || err.message || '请求失败'
    if (status === 401 || status === 403) {
      localStorage.removeItem('hd_token')
      localStorage.removeItem('hd_user')
      if (router.currentRoute.value.path !== '/login') {
        ElMessage.error('登录已失效，请重新登录')
        router.push('/login')
      }
    } else {
      ElMessage.error(message)
    }
    return Promise.reject(err)
  }
)

export default {
  // 认证
  login: (username, password) => http.post('/auth/login', { username, password }),
  me: () => http.get('/auth/me'),
  // 患者
  patients: (keyword) => http.get('/patients', { params: { keyword } }),
  patientDetail: (id) => http.get(`/patients/${id}`),
  createPatient: (data) => http.post('/patients', data),
  updatePatient: (id, data) => http.put(`/patients/${id}`, data),
  // 机位
  machines: () => http.get('/machines'),
  createMachine: (data) => http.post('/machines', data),
  updateMachine: (id, data) => http.put(`/machines/${id}`, data),
  changeMachineStatus: (id, status, note) => http.post(`/machines/${id}/status`, { status, note }),
  // 排班/治疗单
  board: (date, shift) => http.get('/sessions/board', { params: { date, shift } }),
  generate: (date, shift, ignorePattern) => http.post('/sessions/generate', { date, shift, ignorePattern }),
  createSession: (data) => http.post('/sessions', data),
  impact: (sessionId, machineId, date, shift) => http.get('/sessions/impact', { params: { sessionId, machineId, date, shift } }),
  moveSession: (id, machineId, date, shift) => http.post(`/sessions/${id}/move`, { machineId, date, shift }),
  late: (id, note) => http.post(`/sessions/${id}/late`, { note }),
  leave: (id, reason) => http.post(`/sessions/${id}/leave`, { reason }),
  // 机位故障改排
  rescheduleOptions: (machineId, date) => http.get('/reschedule/options', { params: { machineId, date } }),
  rescheduleApply: (data) => http.post('/reschedule/apply', data),
  rescheduleRecords: (date) => http.get('/reschedule/records', { params: { date } }),
  rescheduleChain: (id) => http.get(`/reschedule/${id}/chain`),
  swapCandidates: (sessionId) => http.get('/reschedule/swap-candidates', { params: { sessionId } }),
  startSession: (id) => http.post(`/sessions/${id}/start`),
  sessionDetail: (id) => http.get(`/sessions/${id}/detail`),
  // 治疗过程
  savePre: (id, data) => http.post(`/sessions/${id}/pre`, data),
  addIntra: (id, data) => http.post(`/sessions/${id}/intra`, data),
  savePost: (id, data) => http.post(`/sessions/${id}/post`, data),
  // 工程记录
  disinfections: (params) => http.get('/disinfections', { params }),
  createDisinfection: (data) => http.post('/disinfections', data),
  // 耗材
  consumables: () => http.get('/consumables'),
  createConsumable: (data) => http.post('/consumables', data),
  updateConsumable: (id, data) => http.put(`/consumables/${id}`, data),
  consumableTxn: (id, data) => http.post(`/consumables/${id}/txn`, data),
  consumableTxns: (params) => http.get('/consumables/txns', { params }),
  consumableWarnings: () => http.get('/consumables/warnings'),
  // 异常
  exceptions: (status) => http.get('/exceptions', { params: { status } }),
  createException: (data) => http.post('/exceptions', data),
  handleException: (id, status, handleNote) => http.put(`/exceptions/${id}/handle`, { status, handleNote }),
  // 收费
  billings: (status) => http.get('/billing', { params: { status } }),
  updateBilling: (id, data) => http.put(`/billing/${id}`, data),
  confirmBilling: (id) => http.post(`/billing/${id}/confirm`),
  // 统计
  dashboard: () => http.get('/stats/dashboard'),
  overview: (from, to) => http.get('/stats/overview', { params: { from, to } })
}
