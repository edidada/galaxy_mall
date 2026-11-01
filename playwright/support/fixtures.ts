import { test as base, expect, type Request } from '@playwright/test';

// Capture only bounded request metadata. Never persist bodies, headers, cookies or queries.
export const test = base.extend<{ networkEvidence: void }>({
  networkEvidence: [async ({ page }, use, info) => {
    const events: { method: string; path: string; status: number; elapsedMs: number }[] = [];
    const errors: string[] = [];
    const starts = new WeakMap<Request, number>();
    const relevant = (url: string) => /^\/(api\/mall|api\/gateway|edge)\//.test(new URL(url).pathname);
    page.on('request', request => starts.set(request, Date.now()));
    page.on('response', response => {
      if (!relevant(response.url())) return;
      if (events.length < 200) events.push({ method: response.request().method(),
        path: new URL(response.url()).pathname, status: response.status(),
        elapsedMs: Date.now() - (starts.get(response.request()) ?? Date.now()) });
      if (response.status() >= 500) errors.push(`HTTP ${response.status()}: ${new URL(response.url()).pathname}`);
    });
    page.on('requestfailed', request => {
      if (relevant(request.url()) && !request.failure()?.errorText.includes('ERR_ABORTED')) {
        errors.push(`Request failed: ${new URL(request.url()).pathname}`);
      }
    });
    page.on('pageerror', error => errors.push(`Uncaught page error: ${error.name}`));
    await use();
    await info.attach('network-metadata', { body: JSON.stringify(events, null, 2), contentType: 'application/json' });
    expect(errors, 'No uncaught browser errors or failed business transports').toEqual([]);
  }, { auto: true }],
});
export { expect };
