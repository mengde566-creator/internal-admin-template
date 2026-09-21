import { describe, expect, it } from 'vitest'
import fixture from '../contracts/failed-clarification.json'
import { normaliseClarificationTask } from './agentApi'

describe('澄清合同', () => {
  it('消费后端 FAILED_RETRYABLE 样本并保留业务归属与空候选', () => {
    expect(normaliseClarificationTask(fixture)).toEqual(fixture)
    expect(normaliseClarificationTask(fixture)?.options).toEqual([])
  })
})
