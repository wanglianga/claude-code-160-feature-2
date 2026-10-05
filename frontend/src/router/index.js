import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layout/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '工作台' } },
      { path: 'schedule', name: 'schedule', component: () => import('../views/ScheduleView.vue'), meta: { title: '排班看板' } },
      { path: 'reschedule', name: 'reschedule', component: () => import('../views/RescheduleView.vue'), meta: { title: '故障改排', roles: ['ADMIN', 'NURSE'] } },
      { path: 'patients', name: 'patients', component: () => import('../views/PatientsView.vue'), meta: { title: '患者管理', roles: ['ADMIN', 'NURSE', 'DOCTOR'] } },
      { path: 'treatment', name: 'treatment', component: () => import('../views/TreatmentView.vue'), meta: { title: '透析治疗', roles: ['ADMIN', 'NURSE', 'DOCTOR'] } },
      { path: 'machines', name: 'machines', component: () => import('../views/MachinesView.vue'), meta: { title: '机器与消毒', roles: ['ADMIN', 'NURSE', 'ENGINEER'] } },
      { path: 'consumables', name: 'consumables', component: () => import('../views/ConsumablesView.vue'), meta: { title: '耗材库存', roles: ['ADMIN', 'NURSE', 'ENGINEER'] } },
      { path: 'exceptions', name: 'exceptions', component: () => import('../views/ExceptionsView.vue'), meta: { title: '异常上报' } },
      { path: 'billing', name: 'billing', component: () => import('../views/BillingView.vue'), meta: { title: '收费核对', roles: ['ADMIN', 'FINANCE'] } },
      { path: 'stats', name: 'stats', component: () => import('../views/StatsView.vue'), meta: { title: '统计分析', roles: ['ADMIN', 'FINANCE'] } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('hd_token')
  if (!to.meta.public && !token) return '/login'
  if (to.path === '/login' && token) return '/dashboard'
  if (to.meta.roles) {
    const user = JSON.parse(localStorage.getItem('hd_user') || 'null')
    if (user && !to.meta.roles.includes(user.role)) return '/dashboard'
  }
  return true
})

export default router
