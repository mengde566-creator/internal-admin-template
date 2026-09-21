import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import AgentMarkdownContent from './AgentMarkdownContent.vue'

describe('助手安全 Markdown 渲染', () => {
  it('只渲染受控的粗体、列表和表格，不执行 HTML', () => {
    const wrapper = mount(AgentMarkdownContent, { props: { content: '**规则**\n- 先盘点\n\n| 项目 | 值 |\n| --- | --- |\n| A100 | 2 |\n<script>alert(1)</script>' } })
    expect(wrapper.find('strong').text()).toBe('规则')
    expect(wrapper.findAll('li')).toHaveLength(1)
    expect(wrapper.find('table').exists()).toBe(true)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('v-html')
  })
})
