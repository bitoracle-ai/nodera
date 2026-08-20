import { inDependencyOrder, refName, render, toZod } from './generate-zod.mjs'

describe('toZod', () => {
  it('marks a property optional exactly when it is absent from required', () => {
    const schema = {
      type: 'object',
      required: ['status'],
      properties: { status: { type: 'string' }, detail: { type: 'string' } },
    }

    expect(toZod(schema, 'Response')).toBe(
      'z.object({\n  status: z.string(),\n  detail: z.string().optional(),\n})',
    )
  })

  it('turns a string enum into z.enum rather than a bare string', () => {
    expect(toZod({ type: 'string', enum: ['ready', 'not_ready'] }, 'status')).toBe(
      'z.enum(["ready", "not_ready"])',
    )
  })

  it('distinguishes integers from numbers, because the contract does', () => {
    expect(toZod({ type: 'integer' }, 'count')).toBe('z.number().int()')
    expect(toZod({ type: 'number' }, 'ratio')).toBe('z.number()')
  })

  it('handles arrays and booleans', () => {
    expect(toZod({ type: 'array', items: { type: 'boolean' } }, 'flags')).toBe('z.array(z.boolean())')
  })

  it('resolves a local component reference to that schema constant', () => {
    expect(toZod({ $ref: '#/components/schemas/Actor' }, 'owner')).toBe('ActorSchema')
  })

  // The safety claim this generator makes is "an unsupported shape fails loudly rather than
  // becoming z.unknown()". Remove the default branch's throw and this test goes red.
  it('refuses an unsupported schema type, naming the path, instead of emitting z.unknown()', () => {
    expect(() => toZod({ type: 'tuple' }, 'Ticket.weird')).toThrow(/Ticket\.weird/)
    expect(() => toZod({ type: 'tuple' }, 'Ticket.weird')).toThrow(/unsupported schema type/)
  })

  it('refuses a reference to anything but a local component schema', () => {
    expect(() => refName('https://example.invalid/schema.yaml#/Thing')).toThrow(/local component/)
  })
})

describe('inDependencyOrder', () => {
  it('emits a referenced schema before the schema that references it', () => {
    const ordered = inDependencyOrder({
      Ticket: { type: 'object', properties: { assignee: { $ref: '#/components/schemas/Actor' } } },
      Actor: { type: 'object', properties: { id: { type: 'string' } } },
    })

    expect(ordered.map(([name]) => name)).toEqual(['Actor', 'Ticket'])
  })

  it('refuses a cycle rather than emitting code that cannot evaluate', () => {
    expect(() =>
      inDependencyOrder({
        A: { type: 'object', properties: { b: { $ref: '#/components/schemas/B' } } },
        B: { type: 'object', properties: { a: { $ref: '#/components/schemas/A' } } },
      }),
    ).toThrow(/Cyclic/)
  })
})

describe('render', () => {
  it('marks the output as generated so nobody edits it by hand', () => {
    const output = render({ components: { schemas: { Thing: { type: 'object', properties: {} } } } })

    expect(output).toContain('Do not edit by hand')
    expect(output).toContain("import { z } from 'zod'")
    expect(output).toContain('export const ThingSchema =')
  })

  it('produces a valid module for a document with no schemas at all', () => {
    expect(render({})).toContain("import { z } from 'zod'")
  })
})
