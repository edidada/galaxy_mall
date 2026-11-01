import { test, expect } from '@playwright/test';
import { targetURL, redact, writeEnabled } from '../support/environment.js';
import { business } from '../support/api.js';

test('target must be an origin without secrets or query parameters', () => {
  expect(targetURL('http://192.168.31.77:30080/')).toBe('http://192.168.31.77:30080');
  for (const url of ['file:///tmp/test', 'https://user:pw@example.com', 'https://example.com/?token=x']) {
    expect(() => targetURL(url)).toThrow();
  }
});

test('report metadata drops query secrets', () => {
  expect(redact('GET https://example.com/api/register?password=secret')).not.toContain('secret');
});

test('HTTP 200 with business failure must fail the test', async () => {
  const response = { ok: () => true, headers: () => ({ 'content-type': 'application/json' }),
    json: async () => ({ success: false, message: 'downstream-unavailable:product', data: null }) };
  await expect(business(response)).rejects.toThrow();
});

test('writes cannot be enabled for production', () => {
  const before = { allow: process.env.E2E_ALLOW_WRITES, env: process.env.E2E_ENV };
  try {
    process.env.E2E_ALLOW_WRITES = '1'; process.env.E2E_ENV = 'production';
    expect(() => writeEnabled()).toThrow('E2E_ENV=uat');
  } finally {
    for (const [key, value] of [['E2E_ALLOW_WRITES', before.allow], ['E2E_ENV', before.env]]) {
      if (value === undefined) delete process.env[key!]; else process.env[key!] = value;
    }
  }
});
