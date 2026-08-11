import { beforeEach, describe, expect, it, vi } from 'vitest'

const auth = vi.hoisted(() => ({
  currentUser: null as { mustChangePassword: boolean } | null,
  isLoggedIn: false,
  fetchMe: vi.fn(),
  hasPermission: vi.fn()
}))

vi.mock('../../modules/auth/store/auth', () => ({
  useAuthStore: () => auth
}))

import { router } from './index'

describe('应用路由守卫', () => {
  beforeEach(async () => {
    auth.currentUser = null
    auth.isLoggedIn = false
    auth.fetchMe.mockReset()
    auth.fetchMe.mockRejectedValue(new Error('未登录'))
    auth.hasPermission.mockReset()
    auth.hasPermission.mockReturnValue(false)
    await router.replace('/login')
  })

  it('未登录且会话恢复失败时跳转登录页', async () => {
    await router.push('/site')

    expect(auth.fetchMe).toHaveBeenCalled()
    expect(router.currentRoute.value.name).toBe('login')
  })

  it('首次登录用户访问工作台时强制进入改密页', async () => {
    auth.isLoggedIn = true
    auth.currentUser = { mustChangePassword: true }

    await router.push('/')

    expect(router.currentRoute.value.name).toBe('change-password')
  })

  it('缺少页面权限时回到工作台', async () => {
    auth.isLoggedIn = true
    auth.currentUser = { mustChangePassword: false }

    await router.push('/site')

    expect(auth.hasPermission).toHaveBeenCalledWith('site:homepage:edit')
    expect(router.currentRoute.value.name).toBe('workspace')
  })
})
