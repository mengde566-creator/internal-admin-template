import { http, type ApiResponse } from '../../../shared/api/http'

/** 主页内容（草稿/公开共用字段契约） */
export interface HomepageContent {
  siteName: string
  introduction: string
  heroFileId: string
  contactText: string
  colorScheme: 'GRAPHITE' | 'AZURE'
}

/** 公开主页内容 */
export interface HomepagePublic {
  siteName: string
  introduction: string
  heroFileId: string
  contactText: string
  colorScheme: 'GRAPHITE' | 'AZURE'
}

/**
 * 获取当前草稿（尚无草稿时返回 null）。
 */
export function fetchDraftApi() {
  return http.get<ApiResponse<HomepageContent | null>>('/api/site/draft')
}

/**
 * 保存草稿。
 *
 * @param payload 草稿内容
 */
export function saveDraftApi(payload: HomepageContent) {
  return http.put<ApiResponse<null>>('/api/site/draft', payload)
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
