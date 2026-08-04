import { defineStore } from 'pinia'
import { http, type ApiResponse } from '../../../shared/api/http'
import type { CurrentUser } from '../api/auth'

/**
 * 会话状态（Pinia 只管理会话/权限等跨页面客户端状态，服务端数据交给 TanStack Query）。
 */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    currentUser: null as CurrentUser | null
  }),
  getters: {
    isLoggedIn: (state) => state.currentUser !== null,
    permissions: (state) => state.currentUser?.permissions ?? []
  },
  actions: {
    /**
     * 判断当前用户是否拥有指定权限编码。
     *
     * @param code 权限编码（后端 PermissionCodes）
     */
    hasPermission(code: string): boolean {
      return this.permissions.includes(code)
    },
    /**
     * 拉取当前登录用户（刷新会话；未登录时抛出异常）。
     */
    async fetchMe(): Promise<void> {
      const { data } = await http.get<ApiResponse<CurrentUser>>('/api/auth/me')
      this.currentUser = data.data
    },
    /**
     * 退出登录并清空本地会话状态。
     */
    async logout(): Promise<void> {
      await http.post<ApiResponse<null>>('/api/auth/logout')
      this.currentUser = null
    }
  }
})
