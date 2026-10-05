<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon size="22" color="#fff"><FirstAidKit /></el-icon>
        <span>血透中心管理平台</span>
      </div>
      <el-menu :default-active="$route.path" router background-color="#1f2d3d" text-color="#bfcbd9"
               active-text-color="#409eff" class="menu">
        <el-menu-item v-for="item in menus" :key="item.path" :index="item.path">
          <el-icon><component :is="item.icon" /></el-icon>
          <span>{{ item.title }}</span>
        </el-menu-item>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="crumb">{{ $route.meta.title || '' }}</div>
        <div class="user">
          <el-tag size="small" effect="plain">{{ auth.roleLabel }}</el-tag>
          <el-dropdown @command="onCommand">
            <span class="username">
              {{ auth.user?.name }}<el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout">退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../store/auth'

const auth = useAuthStore()
const router = useRouter()

const ALL_MENUS = [
  { path: '/dashboard', title: '工作台', icon: 'Monitor', roles: null },
  { path: '/schedule', title: '排班看板', icon: 'Calendar', roles: null },
  { path: '/reschedule', title: '故障改排', icon: 'Switch', roles: ['ADMIN', 'NURSE'] },
  { path: '/patients', title: '患者管理', icon: 'User', roles: ['ADMIN', 'NURSE', 'DOCTOR'] },
  { path: '/treatment', title: '透析治疗', icon: 'FirstAidKit', roles: ['ADMIN', 'NURSE', 'DOCTOR'] },
  { path: '/machines', title: '机器与消毒', icon: 'SetUp', roles: ['ADMIN', 'NURSE', 'ENGINEER'] },
  { path: '/consumables', title: '耗材库存', icon: 'Box', roles: ['ADMIN', 'NURSE', 'ENGINEER'] },
  { path: '/exceptions', title: '异常上报', icon: 'WarningFilled', roles: null },
  { path: '/billing', title: '收费核对', icon: 'Money', roles: ['ADMIN', 'FINANCE'] },
  { path: '/stats', title: '统计分析', icon: 'DataAnalysis', roles: ['ADMIN', 'FINANCE'] }
]

const menus = computed(() => ALL_MENUS.filter(m => !m.roles || m.roles.includes(auth.role)))

function onCommand(cmd) {
  if (cmd === 'logout') {
    auth.logout()
    router.push('/login')
  }
}
</script>

<style scoped>
.layout { height: 100vh; }
.aside { background: #1f2d3d; display: flex; flex-direction: column; }
.logo {
  height: 60px; display: flex; align-items: center; justify-content: center;
  gap: 8px; color: #fff; font-size: 16px; font-weight: 600; letter-spacing: 1px;
  border-bottom: 1px solid #2d3a4a;
}
.menu { border-right: none; flex: 1; }
.header {
  background: #fff; border-bottom: 1px solid #e4e7ed;
  display: flex; align-items: center; justify-content: space-between;
}
.crumb { font-size: 16px; font-weight: 600; color: #303133; }
.user { display: flex; align-items: center; gap: 12px; }
.username { display: flex; align-items: center; gap: 4px; cursor: pointer; color: #303133; }
.main { background: #f0f2f5; padding: 16px; overflow-y: auto; }
</style>
