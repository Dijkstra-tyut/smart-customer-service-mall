import assert from 'node:assert/strict'
import test from 'node:test'
import { createCustomerEventParser, decodeCustomerEvent } from '../src/utils/customer-events.js'

test('SSE parser handles split CRLF frames and only exposes typed events', () => {
  const events = []
  const parser = createCustomerEventParser(event => events.push(event))
  parser.push('data: {"type":"status","content":"查询中"}\r')
  parser.push('\n\r\ndata: {"type":"message","content":"已确认回复"}\n\n')
  parser.push('data: {"internal_secret":"must not display"}\n\ndata: [DONE]')
  parser.finish()
  assert.deepEqual(events, [{ type: 'status', content: '查询中' }, { type: 'message', content: '已确认回复' }, { type: 'done' }])
})

test('unknown or malformed JSON never becomes plain chat text', () => {
  assert.equal(decodeCustomerEvent('data: {"code":500,"message":"error"}'), null)
  assert.equal(decodeCustomerEvent('data: {"type":"unknown","content":"hidden"}'), null)
  assert.equal(decodeCustomerEvent('data: {"unfinished"'), null)
  assert.deepEqual(decodeCustomerEvent('data: 旧版文本'), { type: 'legacy_chunk', content: '旧版文本' })
})
