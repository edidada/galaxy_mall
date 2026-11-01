export function targetURL(value = process.env.E2E_BASE_URL): string {
  const url = new URL(value || 'http://192.168.31.77:30080');
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password ||
      url.search || url.hash || url.pathname !== '/') {
    throw new Error('E2E_BASE_URL must be an HTTP(S) origin without credentials, path or query');
  }
  return url.origin;
}

export function required(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing environment variable: ${name}`);
  return value;
}

export function writeEnabled(): boolean {
  if (process.env.E2E_ALLOW_WRITES !== '1') return false;
  if (process.env.E2E_ENV !== 'uat') throw new Error('Transaction tests require E2E_ENV=uat');
  required('E2E_USERNAME');
  required('E2E_PASSWORD');
  required('E2E_PRODUCT_ID');
  return true;
}

export function redact(value: string): string {
  let result = value.replace(/https?:\/\/[^\s"<>]+/g, raw => {
    try {
      const url = new URL(raw);
      return `${url.origin}${url.pathname}${url.search ? '?[redacted]' : ''}`;
    } catch { return '[url]'; }
  });
  for (const secret of [process.env.E2E_PASSWORD, process.env.E2E_USERNAME,
    process.env.E2E_OTHER_PASSWORD, process.env.E2E_OTHER_USERNAME, process.env.CLUSTER_SSH_PASSWORD]) {
    if (secret) result = result.split(secret).join('[redacted]');
  }
  return result;
}
