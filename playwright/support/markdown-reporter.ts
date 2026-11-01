import fs from 'node:fs';
import path from 'node:path';
import { execFileSync } from 'node:child_process';
import type { Reporter, TestCase, TestResult, FullResult } from '@playwright/test/reporter';
import { redact, targetURL } from './environment.js';

export default class MarkdownReporter implements Reporter {
  private rows: string[] = [];
  onTestEnd(test: TestCase, result: TestResult): void {
    const clean = (value: string) => redact(value).replace(/\|/g, '\\|').replace(/[\r\n]+/g, ' ');
    const failure = result.errors.map(e => e.message?.replace(/\u001b\[[0-9;]*m/g, '') || '').join('; ');
    this.rows.push(`| ${clean(test.titlePath().filter(Boolean).join(' / '))} | ${result.status} | ${result.duration} | ${clean(failure).slice(0, 1600)} |`);
  }
  onEnd(result: FullResult): void {
    // Test discovery (--list) must not replace a real run with an empty green report.
    if (!this.rows.length) return;
    let commit = process.env.GITHUB_SHA || process.env.E2E_COMMIT;
    if (!commit) {
      try { commit = execFileSync('git', ['rev-parse', 'HEAD'], { encoding: 'utf8' }).trim(); }
      catch { commit = 'unknown'; }
    }
    const directory = process.env.E2E_REPORT_DIR || 'artifacts';
    fs.mkdirSync(directory, { recursive: true });
    fs.writeFileSync(path.join(directory, 'report.md'), [
      '# K3s Galaxy Mall Test Report', '',
      `- UTC: ${new Date().toISOString()}`,
      `- Target: ${targetURL()}`,
      `- Result: ${result.status}`,
      `- Source commit (not deployed digest): ${commit}`,
      `- Auth: ${process.env.E2E_AUTH === '1'}; transactions: ${process.env.E2E_ALLOW_WRITES === '1'}`,
      '', '| Test | Status | Duration (ms) | Failure |', '| --- | --- | ---: | --- |',
      ...this.rows, '',
      'Only executed cases are covered. Disabled projects are not verified.',
      'Browser timing is a diagnostic sample, not cluster load capacity.', '',
    ].join('\n'));
  }
}
