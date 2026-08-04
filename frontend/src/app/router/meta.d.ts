declare module 'vue-router' {
  interface RouteMeta {
    /** 页面标题 */
    title?: string
    /** 公开页面（无需登录） */
    public?: boolean
    /** 访问所需权限编码（前端体验层过滤，后端仍是最终判定） */
    permission?: string
  }
}

export {}
