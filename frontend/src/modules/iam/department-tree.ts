import type { DepartmentNode } from './api/department'

export function flatten(nodes: DepartmentNode[], depth = 0): Array<{ id: string; label: string; node: DepartmentNode }> {
  return nodes.flatMap((node) => [
    { id: node.id, label: `${'　'.repeat(depth)}${node.name}（${node.code}）`, node },
    ...flatten(node.children, depth + 1)
  ])
}

function collectDescendantIds(node: DepartmentNode, blocked: Set<string>) {
  blocked.add(node.id)
  node.children.forEach((child) => collectDescendantIds(child, blocked))
}

export function filterParentOptions(nodes: DepartmentNode[], editingId?: string): Array<{ id: string; label: string }> {
  const blocked = new Set<string>()
  if (editingId) {
    const visit = (items: DepartmentNode[]): void => {
      const current = items.find((item) => item.id === editingId)
      if (current) {
        collectDescendantIds(current, blocked)
        return
      }
      items.forEach((item) => visit(item.children))
    }
    visit(nodes)
  }
  return flatten(nodes)
    .filter((option) => option.node.enabled && !blocked.has(option.id))
    .map(({ id, label }) => ({ id, label }))
}
