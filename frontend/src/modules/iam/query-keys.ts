import type { QueryKey } from '@tanstack/vue-query'

/**
 * iam 模块 Query Key 约定（AGENTS：Query Key 按业务模块统一定义，禁止页面随意拼接）。
 */
export const iamQueryKeys = {
  users: (page: number, size: number, keyword?: string): QueryKey =>
    ['iam', 'users', { page, size, keyword }],
  roles: (): QueryKey => ['iam', 'roles'],
  permissionOptions: (): QueryKey => ['iam', 'roles', 'permission-options'],
  systemConfigs: (): QueryKey => ['iam', 'system-configs']
}
