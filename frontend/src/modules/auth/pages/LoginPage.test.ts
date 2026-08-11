import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const doubles = vi.hoisted(() => ({
  loginApi: vi.fn(),
  fetchMe: vi.fn(),
  push: vi.fn(),
  warning: vi.fn(),
  error: vi.fn()
}))

vi.mock('../api/auth', () => ({
  loginApi: doubles.loginApi
}))

vi.mock('../store/auth', () => ({
  useAuthStore: () => ({
    currentUser: null,
    fetchMe: doubles.fetchMe
  })
}))

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: doubles.push })
}))

vi.mock('element-plus', async (importOriginal) => {
  const actual = await importOriginal<typeof import('element-plus')>()
  return {
    ...actual,
    ElMessage: {
      warning: doubles.warning,
      error: doubles.error
    }
  }
})

import LoginPage from './LoginPage.vue'

describe('登录页', () => {
  beforeEach(() => {
    doubles.loginApi.mockReset()
    doubles.fetchMe.mockReset()
    doubles.fetchMe.mockRejectedValue(new Error('未登录'))
    doubles.push.mockReset()
    doubles.warning.mockReset()
    doubles.error.mockReset()
  })

  it('账号或密码为空时显示校验提示且不请求登录接口', async () => {
    const wrapper = mount(LoginPage, { global: { plugins: [ElementPlus] } })

    await wrapper.get('button').trigger('click')

    expect(doubles.warning).toHaveBeenCalledWith('请输入账号和密码')
    expect(doubles.loginApi).not.toHaveBeenCalled()
  })

  it('登录接口拒绝时显示后端返回的可见原因', async () => {
    doubles.loginApi.mockRejectedValue({
      isAxiosError: true,
      response: { data: { message: '账号或密码错误' } }
    })
    const wrapper = mount(LoginPage, { global: { plugins: [ElementPlus] } })
    const inputs = wrapper.findAll('input')

    await inputs[0].setValue('editor')
    await inputs[1].setValue('wrong-password')
    await wrapper.get('button').trigger('click')
    await flushPromises()

    expect(doubles.loginApi).toHaveBeenCalledWith('editor', 'wrong-password')
    expect(doubles.error).toHaveBeenCalledWith('账号或密码错误')
    expect(doubles.push).not.toHaveBeenCalled()
  })
})
