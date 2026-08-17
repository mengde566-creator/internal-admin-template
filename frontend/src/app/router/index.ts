import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../../modules/auth/store/auth'
import SystemLayout from '../../layouts/SystemLayout.vue'

/**
 * 应用路由。
 *
 * <p>登录页与改密页使用独立布局；登录后区域由 {@link SystemLayout}（侧边栏+顶栏）包裹。
 * 未登录跳转登录页；首次登录必须改密时强制跳转改密页；
 * 带 permission meta 的页面按当前用户权限过滤（前端体验层，后端仍是最终判定）。
 * 页面组件使用动态导入（路由级拆包）。</p>
 */
export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../../modules/auth/pages/LoginPage.vue'),
      meta: { title: '登录', public: true }
    },
    {
      path: '/change-password',
      name: 'change-password',
      component: () => import('../../modules/auth/pages/ChangePasswordPage.vue'),
      meta: { title: '修改密码' }
    },
    {
      path: '/',
      component: SystemLayout,
      children: [
        {
          path: '',
          name: 'workspace',
          component: () => import('../../modules/workspace/pages/WorkspaceHome.vue'),
          meta: { title: '工作台' }
        },
        {
          path: 'site',
          name: 'site-manage',
          component: () => import('../../modules/site/pages/SiteManagePage.vue'),
          meta: { title: '主页内容', permission: 'site:homepage:edit' }
        },
        {
          path: 'users',
          name: 'users',
          component: () => import('../../modules/iam/pages/UserManagePage.vue'),
          meta: { title: '用户管理', permission: 'iam:user:manage' }
        },
        {
          path: 'departments',
          name: 'departments',
          component: () => import('../../modules/iam/pages/DepartmentManagePage.vue'),
          meta: { title: '部门管理', permission: 'iam:department:manage' }
        },
        {
          path: 'warehouse',
          name: 'warehouse',
          component: () => import('../../modules/warehouse/pages/WarehouseManagePage.vue'),
          meta: { title: '仓储', permission: 'warehouse:read' },
          children: [
            { path: '', name: 'warehouse-default', redirect: { name: 'warehouse-stock' } },
            { path: 'stock', name: 'warehouse-stock', component: () => import('../../modules/warehouse/pages/WarehouseStockPage.vue'), meta: { title: '库存查询', permission: 'warehouse:read' } },
            { path: 'operations', name: 'warehouse-operations', component: () => import('../../modules/warehouse/pages/WarehouseOperationsPage.vue'), meta: { title: '库存操作', permission: 'warehouse:inventory:operate' } },
            { path: 'items', name: 'warehouse-items', component: () => import('../../modules/warehouse/pages/WarehouseItemsPage.vue'), meta: { title: '物品', permission: 'warehouse:master:manage' } },
            { path: 'locations', name: 'warehouse-locations', component: () => import('../../modules/warehouse/pages/WarehouseLocationsPage.vue'), meta: { title: '仓库与库位', permission: 'warehouse:master:manage' } },
            { path: 'records', name: 'warehouse-records', component: () => import('../../modules/warehouse/pages/WarehouseRecordsPage.vue'), meta: { title: '库存记录', permission: 'warehouse:read' } },
          ]
        },
        {
          path: 'roles',
          name: 'roles',
          component: () => import('../../modules/iam/pages/RoleManagePage.vue'),
          meta: { title: '角色管理', permission: 'iam:role:manage' }
        },
        {
          path: 'system-config',
          name: 'system-config',
          component: () => import('../../modules/iam/pages/SystemConfigPage.vue'),
          meta: { title: '登录安全', permission: 'system:config:manage' }
        }
      ]
    },
    {
      path: '/public',
      name: 'public-site',
      component: () => import('../../modules/site/pages/PublicSitePage.vue'),
      meta: { title: '公开主页', public: true }
    }
  ]
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()

  if (to.meta.public) {
    // 已登录用户访问登录页时直接回工作台
    if (to.name === 'login' && !auth.isLoggedIn) {
      try {
        await auth.fetchMe()
      } catch {
        return true
      }
      if (auth.isLoggedIn) {
        return { name: 'workspace' }
      }
    }
    return true
  }

  if (!auth.isLoggedIn) {
    try {
      await auth.fetchMe()
    } catch {
      return { name: 'login' }
    }
  }

  if (auth.currentUser?.mustChangePassword && to.name !== 'change-password') {
    return { name: 'change-password' }
  }

  if (to.meta.permission && !auth.hasPermission(to.meta.permission)) {
    return { name: 'workspace' }
  }

  return true
})
