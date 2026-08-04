import type { Component } from 'vue'

/** 布局导航项（与已批准 admin-ui-kit 契约一致） */
export interface NavigationItem {
  key: string
  label: string
  icon?: Component
  badge?: string | number
  disabled?: boolean
}
