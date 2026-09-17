export type TemplateParameterValue = string | number | boolean | null

export interface TemplateFormField {
  key: string
  label: string
  type: 'string' | 'number' | 'boolean'
  required: boolean
  defaultValue: TemplateParameterValue
}

const MAX_FIELDS = 30

export function templateFields(schema: unknown): TemplateFormField[] {
  if (!isRecord(schema) || !isRecord(schema.properties)) return []
  const required = new Set(
    Array.isArray(schema.required)
      ? schema.required.filter((value): value is string => typeof value === 'string')
      : [],
  )

  return Object.entries(schema.properties)
    .filter(([key, value]) => isFieldKey(key) && isRecord(value))
    .slice(0, MAX_FIELDS)
    .map(([key, value]) => {
      const type = normalizeType(value.type)
      return {
        key,
        label: typeof value.title === 'string' && value.title.trim() ? value.title.trim() : key,
        type,
        required: required.has(key),
        defaultValue: normalizeValue(value.default, type),
      }
    })
}

export function initializeTemplateParameters(
  fields: TemplateFormField[],
  parameters: Record<string, unknown> | null | undefined,
): Record<string, TemplateParameterValue> {
  const source = isRecord(parameters) ? parameters : {}
  return Object.fromEntries(fields.map((field) => {
    const value = Object.prototype.hasOwnProperty.call(source, field.key) ? source[field.key] : field.defaultValue
    return [field.key, normalizeValue(value, field.type)]
  }))
}

export function mergeTemplateParameters(
  base: Record<string, unknown> | null | undefined,
  fields: TemplateFormField[],
  values: Record<string, unknown> | null | undefined,
): Record<string, TemplateParameterValue> {
  const result: Record<string, TemplateParameterValue> = {}
  if (isRecord(base)) {
    for (const [key, value] of Object.entries(base)) {
      if (isFieldKey(key)) result[key] = normalizeUnknown(value)
    }
  }
  const source = isRecord(values) ? values : {}
  for (const field of fields) {
    if (Object.prototype.hasOwnProperty.call(source, field.key)) {
      result[field.key] = normalizeValue(source[field.key], field.type)
    } else if (!Object.prototype.hasOwnProperty.call(result, field.key)) {
      result[field.key] = field.defaultValue
    }
  }
  return result
}

function isRecord(value: unknown): value is Record<string, any> {
  return Boolean(value) && typeof value === 'object' && !Array.isArray(value)
}

function isFieldKey(value: string): boolean {
  return /^[A-Za-z][A-Za-z0-9_]{0,63}$/.test(value) && value !== '__proto__' && value !== 'constructor'
}

function normalizeType(value: unknown): TemplateFormField['type'] {
  return value === 'number' || value === 'integer' ? 'number' : value === 'boolean' ? 'boolean' : 'string'
}

function normalizeUnknown(value: unknown): TemplateParameterValue {
  if (value === null || typeof value === 'string' || typeof value === 'number' || typeof value === 'boolean') return value
  return value == null ? null : String(value)
}

function normalizeValue(value: unknown, type: TemplateFormField['type']): TemplateParameterValue {
  if (value === null || value === undefined || value === '') return null
  if (type === 'boolean') {
    if (value === true || value === 'true') return true
    if (value === false || value === 'false') return false
    return null
  }
  if (type === 'number') {
    const number = typeof value === 'number' ? value : Number(value)
    return Number.isFinite(number) ? number : null
  }
  return String(value)
}
