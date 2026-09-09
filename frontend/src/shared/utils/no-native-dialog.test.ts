import { describe, expect, it } from 'vitest'

describe('静态代码门禁：禁止使用浏览器原生弹窗', () => {
  it('frontend/src 源码中不得存在 window.alert / window.confirm / window.prompt 或原生 alert/confirm/prompt 调用', () => {
    const sourceFiles = import.meta.glob<string>(
      ['../../**/*.vue', '../../**/*.ts', '!../../**/*.test.ts', '!../../**/*.d.ts'],
      { query: '?raw', import: 'default', eager: true }
    )

    const fileEntries = Object.entries(sourceFiles)
    expect(fileEntries.length).toBeGreaterThan(0)

    const nativeDialogPattern = /\b(?:window\s*\.\s*(?:alert|confirm|prompt)|(?<![\.\w$])(?:alert|confirm|prompt)\s*\()/g

    const violations: { file: string; line: number; match: string }[] = []

    for (const [filePath, content] of fileEntries) {
      const lines = content.split('\n')
      lines.forEach((lineText: string, idx: number) => {
        const trimmed = lineText.trim()
        if (trimmed.startsWith('//') || trimmed.startsWith('*') || trimmed.startsWith('/*')) {
          return
        }
        let match: RegExpExecArray | null
        while ((match = nativeDialogPattern.exec(lineText)) !== null) {
          violations.push({
            file: filePath,
            line: idx + 1,
            match: match[0]
          })
        }
      })
    }

    expect(violations).toEqual([])
  })
})
