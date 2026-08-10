import { readFileSync, writeFileSync } from 'node:fs'

const [inputPath, outputPath] = process.argv.slice(2)

if (!inputPath || !outputPath) {
  throw new Error('用法：node scripts/normalize-openapi-json.mjs <input.json> <output.json>')
}

function normalize(value) {
  if (Array.isArray(value)) {
    return value.map(normalize)
  }
  if (value !== null && typeof value === 'object') {
    return Object.fromEntries(Object.keys(value).sort().map((key) => [key, normalize(value[key])]))
  }
  return value
}

const specification = JSON.parse(readFileSync(inputPath, 'utf8'))
writeFileSync(outputPath, `${JSON.stringify(normalize(specification), null, 2)}\n`, 'utf8')
