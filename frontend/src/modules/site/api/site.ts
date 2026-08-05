import { http, type ApiResponse } from '../../../shared/api/http'

/** 布局代码（代码定义，前端只展示，权威在后端白名单） */
export type LayoutCode = 'GRID_SPLIT' | 'BANNER_SPLIT'

/** 区块类型代码（代码定义，第一批 4 种） */
export type SectionType = 'ABOUT' | 'SERVICE' | 'NEWS' | 'CONTACT'

/** 主页区块（草稿与公开共用传输结构；id 由后端生成，新增时为空） */
export interface HomepageSection {
  id?: string
  sectionType: SectionType
  title: string
  content: string
  heroFileId?: string
  sortOrder?: number
}

/** 主页展示内容（草稿与公开页共用字段契约，含布局与区块） */
export interface HomepageView {
  siteName: string
  introduction: string
  heroFileId: string
  contactText: string
  colorScheme: 'GRAPHITE' | 'AZURE'
  layoutCode: LayoutCode
  sections: HomepageSection[]
}

/** 主页草稿内容（获取与保存共用） */
export type HomepageContent = HomepageView

/** 公开主页内容 */
export type HomepagePublic = HomepageView

/**
 * 获取当前草稿（尚无草稿时返回 null）。
 */
export function fetchDraftApi() {
  return http.get<ApiResponse<HomepageContent | null>>('/api/site/draft')
}

/**
 * 保存草稿（含布局与区块），返回保存后的草稿（含后端生成的区块 ID）。
 *
 * @param payload 草稿内容
 */
export function saveDraftApi(payload: HomepageContent) {
  return http.put<ApiResponse<HomepageContent>>('/api/site/draft', payload)
}

/**
 * 发布草稿为公开快照。
 */
export function publishApi() {
  return http.post<ApiResponse<null>>('/api/site/publish')
}

/**
 * 撤回公开主页。
 */
export function withdrawApi() {
  return http.post<ApiResponse<null>>('/api/site/withdraw')
}

/**
 * 匿名读取公开主页（未发布或已撤回时 404）。
 */
export function fetchPublicSiteApi() {
  return http.get<ApiResponse<HomepagePublic>>('/api/public/site')
}

/**
 * 公开图片访问地址（仅已发布快照引用的图片可读）。
 *
 * @param fileId 文件 ID
 */
export function publicFileUrl(fileId: string): string {
  return `/api/public/files/${fileId}`
}

/**
 * 管理端图片访问地址（草稿预览用，需要内容编辑权限）。
 *
 * @param fileId 文件 ID
 */
export function manageFileUrl(fileId: string): string {
  return `/api/files/${fileId}`
}

/**
 * 上传图片，返回文件 ID。
 *
 * @param file 图片文件
 */
export async function uploadImageApi(file: File): Promise<string> {
  const form = new FormData()
  form.append('file', file)
  const { data } = await http.post<ApiResponse<{ fileId: string }>>('/api/files', form)
  return data.data.fileId
}
