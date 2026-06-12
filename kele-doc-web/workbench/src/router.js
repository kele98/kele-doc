import { createRouter, createWebHistory } from 'vue-router'
import { pinia, useStore } from './store'

const routes = [
  {
    path: '/',
    redirect: '/workspace'
  },
  {
    name: 'Workspace',
    path: '/workspace',
    component: () => import('@/pages/workspace/Index.vue'),
    children: [
      {
        name: 'List',
        path: '',
        component: () => import('@/pages/workspace/List.vue')
      },
      {
        name: 'Collect',
        path: 'collect',
        component: () => import('@/pages/workspace/Collect.vue')
      },
      {
        name: 'Recycle',
        path: 'recycle',
        component: () => import('@/pages/workspace/Recycle.vue')
      },
      {
        name: 'Homepage',
        path: 'homepage',
        component: () => import('@/pages/workspace/Homepage.vue')
      },
      {
        name: 'Panorama',
        path: 'panorama',
        component: () => import('@/pages/workspace/Panorama.vue')
      },
      {
        name: 'GroupManage',
        path: 'group-manage',
        component: () => import('@/pages/workspace/GroupManage.vue'),
        meta: { requiresAdmin: true }
      },
      {
        name: 'UserManage',
        path: 'user-manage',
        component: () => import('@/pages/workspace/UserManage.vue'),
        meta: { requiresAdmin: true }
      }
    ]
  },
  {
    name: 'Login',
    path: '/login',
    component: () => import('@/pages/login/Index.vue')
  },
  {
    name: 'Error',
    path: '/error',
    component: () => import('@/pages/Error/Index.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// Bug #8: admin 路由前端守卫。
// 后端 LoginContext.isAdmin() 已 enforce（admin API 调用必返 403）；
// 这里只是 UX 层：非 admin 用户直接敲 URL → 重定向到 Error 页，避免看到空表格 / 403 toast。
router.beforeEach(async to => {
  if (to.meta && to.meta.requiresAdmin) {
    const store = useStore(pinia)
    const info = await store.getUserInfo()
    if (!info || info.role !== 'ADMIN') {
      return { name: 'Error' }
    }
  }
})

export default router
