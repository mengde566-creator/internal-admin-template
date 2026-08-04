import type { QueryKey } from '@tanstack/vue-query'

/**
 * site 模块 Query Key 约定。
 */
export const siteQueryKeys = {
  draft: (): QueryKey => ['site', 'draft'],
  publicSite: (): QueryKey => ['site', 'public']
}
