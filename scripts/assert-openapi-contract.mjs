import { readFileSync } from 'node:fs'
import { deepStrictEqual } from 'node:assert'

const [mode, target] = process.argv.slice(2)

if (mode === '--type-header') {
  const header = readFileSync(target, 'utf8').split('\n').slice(0, 6).join('\n')
  if (!/auto-generated/i.test(header) || !/do not .*direct/i.test(header)) {
    throw new Error('生成的 TypeScript 缺少“机器生成、禁止手改”文件头。')
  }
  process.exit(0)
}

if (!mode || target) {
  throw new Error('用法：node scripts/assert-openapi-contract.mjs <openapi.json>')
}

const specification = JSON.parse(readFileSync(mode, 'utf8'))

function assert(condition, message) {
  if (!condition) {
    throw new Error(message)
  }
}

function dereference(schema) {
  let current = schema
  const visited = new Set()
  while (current?.$ref) {
    assert(current.$ref.startsWith('#/components/schemas/'), `不支持的 schema 引用：${current.$ref}`)
    assert(!visited.has(current.$ref), `发现循环 schema 引用：${current.$ref}`)
    visited.add(current.$ref)
    const name = current.$ref.substring('#/components/schemas/'.length)
    current = specification.components?.schemas?.[name]
    assert(current, `缺少被引用的 schema：${name}`)
  }
  return current
}

function responseSchema(path, method) {
  const response = specification.paths?.[path]?.[method]?.responses?.['200']
  assert(response, `${method.toUpperCase()} ${path} 缺少 200 响应`)
  const schema = response.content?.['application/json']?.schema
  assert(schema, `${method.toUpperCase()} ${path} 缺少 application/json 响应 schema`)
  return dereference(schema)
}

function requestSchema(path, method) {
  const schema = specification.paths?.[path]?.[method]?.requestBody?.content?.['application/json']?.schema
  assert(schema, `${method.toUpperCase()} ${path} 缺少 application/json 请求 schema`)
  return dereference(schema)
}

function property(schema, name, context) {
  const resolved = dereference(schema)
  const value = resolved?.properties?.[name]
  assert(value, `${context} 缺少 ${name} 属性`)
  return dereference(value)
}

function hasNull(schema) {
  const resolved = dereference(schema)
  return resolved.nullable === true
    || (Array.isArray(resolved.type) && resolved.type.includes('null'))
    || resolved.type === 'null'
    || resolved.anyOf?.some((item) => hasNull(item)) === true
    || resolved.oneOf?.some((item) => hasNull(item)) === true
}

function concreteSchema(schema) {
  const resolved = dereference(schema)
  if (Array.isArray(resolved.anyOf)) {
    const concrete = resolved.anyOf.find((item) => {
      const candidate = dereference(item)
      return candidate.type !== 'null'
        && !(Array.isArray(candidate.type) && candidate.type.length === 1 && candidate.type[0] === 'null')
    })
    assert(concrete, '可空 schema 缺少非 null 的业务数据分支。')
    return concreteSchema(concrete)
  }
  return resolved
}

function assertCreateId(path) {
  const response = responseSchema(path, 'post')
  const data = concreteSchema(property(response, 'data', `POST ${path} 响应`))
  const id = property(data, 'id', `POST ${path} data`)
  assert(id.type === 'string', `POST ${path} 的 data.id 必须是 string`)
}

assert(/^3\./.test(specification.openapi), '规范必须是 OpenAPI 3.x。')
assert(typeof specification['x-generated-by'] === 'string'
  && specification['x-generated-by'].includes('springdoc-openapi 3.1.0'),
  '规范缺少 springdoc 运行时生成标记。')

const expectedPaths = [
  '/api/ai/capabilities',
  '/api/ai/conversations',
  '/api/ai/conversations/{conversationId}/messages',
  '/api/ai/conversations/{conversationId}/runs',
  '/api/auth/login',
  '/api/auth/logout',
  '/api/auth/me',
  '/api/auth/change-password',
  '/api/users',
  '/api/users/{id}',
  '/api/departments/tree',
  '/api/departments/options',
  '/api/departments',
  '/api/departments/{id}',
  '/api/departments/{id}/enabled',
  '/api/roles',
  '/api/roles/{id}',
  '/api/roles/permission-options',
  '/api/system/configs',
  '/api/system/configs/{paramKey}',
  '/api/files',
  '/api/files/{fileId}',
  '/api/site/draft',
  '/api/site/publish',
  '/api/site/withdraw',
  '/api/public/site',
  '/api/public/files/{fileId}',
  '/api/warehouse/items',
  '/api/warehouse/items/{id}',
  '/api/warehouse/warehouses',
  '/api/warehouse/warehouses/{id}',
  '/api/warehouse/warehouses/options',
  '/api/warehouse/locations',
  '/api/warehouse/locations/{id}',
  '/api/warehouse/locations/{warehouseId}',
  '/api/warehouse/locations/options',
  '/api/warehouse/stock/items/{itemId}',
  '/api/warehouse/stock',
  '/api/warehouse/stock/locations/{locationId}',
  '/api/warehouse/movements/recent',
  '/api/warehouse/operations',
  '/api/warehouse/operations/{operationId}',
  '/api/warehouse/operations/{operationId}/movements',
  '/api/warehouse/inbound',
  '/api/warehouse/outbound',
  '/api/warehouse/transfer',
  '/api/warehouse/stocktake'
]

deepStrictEqual(Object.keys(specification.paths ?? {}).sort(), expectedPaths.slice().sort(),
  'OpenAPI 路径集合必须与全部 0.1 Controller 路径完全一致。')

function operation(path, method) {
  const value = specification.paths?.[path]?.[method]
  assert(value, `${method.toUpperCase()} ${path} 缺少 HTTP 方法定义`)
  return value
}

function methods(path) {
  return Object.keys(specification.paths?.[path] ?? {})
    .filter((method) => ['get', 'post', 'put', 'patch', 'delete'].includes(method))
    .sort()
}

operation('/api/ai/conversations', 'post')
const conversationPage = operation('/api/ai/conversations', 'get')
const messagePage = operation('/api/ai/conversations/{conversationId}/messages', 'get')
operation('/api/ai/conversations/{conversationId}/runs', 'post')
deepStrictEqual(methods('/api/ai/conversations'), ['get', 'post'],
  'Conversation 路径必须同时且仅暴露 GET 列表与 POST 创建')
deepStrictEqual(methods('/api/ai/conversations/{conversationId}/messages'), ['get'],
  'History 路径必须仅暴露 GET')
deepStrictEqual(methods('/api/ai/conversations/{conversationId}/runs'), ['post'],
  'Run 路径必须仅暴露 POST')

const runRequest = requestSchema('/api/ai/conversations/{conversationId}/runs', 'post')
deepStrictEqual(Object.keys(runRequest.properties ?? {}).sort(), ['clientRequestId', 'text'],
  'RunRequest 只能包含 clientRequestId 与 text')
deepStrictEqual((runRequest.required ?? []).slice().sort(), ['clientRequestId', 'text'],
  'RunRequest 必须同时要求 clientRequestId 与 text')
assert(!runRequest.properties?.message, 'RunRequest 不得保留 message 兼容字段')

const conversationData = concreteSchema(property(responseSchema('/api/ai/conversations', 'post'), 'data',
  'POST /api/ai/conversations 响应'))
assert(property(conversationData, 'conversationId', 'ConversationDTO').type === 'string',
  'ConversationDTO.conversationId 必须是 string')
assert(!conversationData.properties?.status, 'ConversationDTO 不得伪造不存在的 status 字段')

for (const [path, operationValue, schemaName] of [
  ['/api/ai/conversations', conversationPage, 'ConversationPageDTO'],
  ['/api/ai/conversations/{conversationId}/messages', messagePage, 'MessagePageDTO']
]) {
  const response = concreteSchema(dereference(operationValue.responses?.['200']?.content?.['application/json']?.schema))
  const data = concreteSchema(property(response, 'data', `${schemaName} 响应`))
  for (const field of ['records', 'total', 'page', 'size']) {
    assert(data.properties?.[field], `${schemaName} 缺少 ${field} 分页字段`)
  }
}

const messagePageResponse = responseSchema('/api/ai/conversations/{conversationId}/messages', 'get')
const messagePageData = concreteSchema(property(messagePageResponse, 'data', 'MessagePageDTO 响应'))
const message = dereference(messagePageData.properties?.records?.items)
assert(message, 'MessagePageDTO.records 必须包含 MessageDTO')
for (const idField of ['messageId', 'runId']) {
  assert(property(message, idField, 'MessageDTO').type === 'string', `MessageDTO.${idField} 必须是 string`)
}

assertCreateId('/api/users')
assertCreateId('/api/roles')
assertCreateId('/api/warehouse/items')
assertCreateId('/api/warehouse/warehouses')
assertCreateId('/api/warehouse/locations')

for (const [path, properties] of Object.entries({
  '/api/users': ['username', 'displayName', 'password', 'departmentId', 'roleIds'],
  '/api/roles': ['code', 'name', 'permissionCodes'],
  '/api/site/draft': ['siteName', 'introduction', 'heroFileId', 'contactText', 'colorScheme', 'layoutCode', 'sections']
})) {
  const method = path === '/api/site/draft' ? 'put' : 'post'
  const schema = requestSchema(path, method)
  for (const propertyName of properties) {
    assert(schema.properties?.[propertyName], `${method.toUpperCase()} ${path} 请求缺少 ${propertyName} 字段`)
  }
}

const createUser = requestSchema('/api/users', 'post')
assert(dereference(createUser.properties.roleIds)?.items?.type === 'string',
  'POST /api/users 的 roleIds 元素必须是 string。')

for (const path of ['/api/warehouse/inbound', '/api/warehouse/outbound', '/api/warehouse/transfer', '/api/warehouse/stocktake']) {
  const schema = requestSchema(path, 'post')
  assert(schema.required?.includes('requestId') && schema.required?.includes('lines'), `${path} 必须要求 requestId 与 lines。`)
  const line = dereference(dereference(schema.properties?.lines)?.items)
  assert(line && dereference(line.properties?.quantity)?.type === 'string', `${path} 数量必须按字符串传输。`)
}

for (const [path, method] of [
  ['/api/departments', 'post'],
  ['/api/departments/{id}', 'put'],
  ['/api/departments/{id}/enabled', 'put']
]) {
  const schema = requestSchema(path, method)
  assert(schema.required?.includes('version'), `${method.toUpperCase()} ${path} 的 version 必须是必填字段。`)
}
const deleteDepartment = specification.paths?.['/api/departments/{id}']?.delete
const deleteVersion = deleteDepartment?.parameters?.find((parameter) => parameter.name === 'version')
assert(deleteVersion?.required === true, 'DELETE /api/departments/{id} 的 version 必须是必填参数。')

const userPage = concreteSchema(property(responseSchema('/api/users', 'get'), 'data', 'GET /api/users 响应'))
const records = property(userPage, 'records', '用户分页 data')
assert(records.type === 'array' && records.items, '用户分页 records 必须是数组。')
assert(property(records.items, 'id', '用户分页 records 元素').type === 'string',
  '用户列表 ID 必须是 string。')

const stockPage = concreteSchema(property(responseSchema('/api/warehouse/stock', 'get'), 'data', 'GET /api/warehouse/stock 响应'))
const stockRecords = property(stockPage, 'records', '库存分页 data')
assert(stockRecords.type === 'array' && stockRecords.items, '库存分页 records 必须是数组。')
for (const propertyName of ['itemId', 'warehouseId', 'locationId']) {
  assert(property(stockRecords.items, propertyName, '库存分页 records 元素').type === 'string',
    `库存分页 ${propertyName} 必须是 string。`)
}
for (const propertyName of ['itemName', 'baseUnit', 'warehouseName', 'locationName', 'quantity']) {
  assert(property(stockRecords.items, propertyName, '库存分页 records 元素').type === 'string',
    `库存分页 ${propertyName} 必须是 string。`)
}
for (const propertyName of ['total', 'current', 'size']) {
  assert(property(stockPage, propertyName, '库存分页 data').type === 'integer',
    `库存分页 ${propertyName} 必须是 integer。`)
}

const emptyData = property(responseSchema('/api/users', 'put'), 'data', 'PUT /api/users 响应')
assert(hasNull(emptyData), '空成功响应的 data 必须允许 null。')

for (const [path, method] of [
  ['/api/auth/logout', 'post'],
  ['/api/auth/change-password', 'post'],
  ['/api/users', 'put'],
  ['/api/users/{id}', 'delete'],
  ['/api/roles', 'put'],
  ['/api/roles/{id}', 'delete'],
  ['/api/system/configs/{paramKey}', 'put'],
  ['/api/site/publish', 'post'],
  ['/api/site/withdraw', 'post']
]) {
  assert(hasNull(property(responseSchema(path, method), 'data', `${method.toUpperCase()} ${path} 响应`)),
    `${method.toUpperCase()} ${path} 的空成功响应 data 必须允许 null。`)
}

for (const [propertyName, values] of Object.entries({
  colorScheme: ['GRAPHITE', 'AZURE'],
  layoutCode: ['GRID_SPLIT', 'BANNER_SPLIT'],
  sectionType: ['ABOUT', 'SERVICE', 'NEWS', 'CONTACT']
})) {
  const schemas = Object.values(specification.components?.schemas ?? {})
  const matching = schemas.some((schema) => {
    const value = dereference(schema)?.properties?.[propertyName]
    return value && JSON.stringify(value.enum) === JSON.stringify(values)
  })
  assert(matching, `${propertyName} 必须声明为受限枚举。`)
}

for (const [path, pathItem] of Object.entries(specification.paths ?? {})) {
  const operations = Object.values(pathItem ?? {})
  if (Array.isArray(pathItem?.parameters)) {
    operations.push({ parameters: pathItem.parameters })
  }
  for (const operation of operations) {
    if (!operation || typeof operation !== 'object' || !Array.isArray(operation.parameters)) {
      continue
    }
    for (const parameter of operation.parameters) {
      if (parameter.name?.toLowerCase().endsWith('id')) {
        assert(dereference(parameter.schema)?.type === 'string',
          `${path} 的路径参数 ${parameter.name} 必须是 string。`)
      }
    }
  }
}
