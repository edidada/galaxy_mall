import { spawnSync } from 'node:child_process';
import { mkdirSync, writeFileSync } from 'node:fs';

// Use the operator's current kubeconfig. Do not read Secrets or change cluster resources.
function get(args) {
  const result = spawnSync('kubectl', [...args, '-o', 'json'], { encoding: 'utf8', timeout: 20_000 });
  if (result.error || result.status !== 0) throw new Error(`kubectl ${args.join(' ')} failed; check context and RBAC`);
  return JSON.parse(result.stdout).items;
}
const nodes = get(['get', 'nodes']);
const apps = get(['-n', 'galaxy-mall', 'get', 'deployments']);
const pods = get(['-n', 'galaxy-mall', 'get', 'pods']);
const middleware = get(['-n', 'middleware', 'get', 'endpointslices']);
const failures = [];
if (!nodes.length) failures.push('No nodes found');
for (const node of nodes) {
  if (!node.status.conditions.some(c => c.type === 'Ready' && c.status === 'True')) failures.push(`Node not Ready: ${node.metadata.name}`);
}
for (const name of ['front', ...['gateway', 'user', 'product', 'stock', 'order', 'payment'].map(s => `galaxy-mall-${s}`)]) {
  const app = apps.find(d => d.metadata.name === name);
  if (!app || !app.spec.replicas || app.status.availableReplicas !== app.spec.replicas ||
      app.status.updatedReplicas !== app.spec.replicas || (app.status.observedGeneration ?? 0) < app.metadata.generation) {
    failures.push(`Deployment not converged: ${name}`);
  }
}
for (const name of ['mysql', 'redis', 'rocketmq-namesrv', 'rocketmq-broker']) {
  const ready = middleware.filter(s => s.metadata.labels['kubernetes.io/service-name'] === name)
    .some(s => s.endpoints.some(e => e.conditions.ready === true && e.addresses.length));
  if (!ready) failures.push(`No Ready endpoint: middleware/${name}`);
}
mkdirSync('artifacts', { recursive: true });
const snapshot = { utc: new Date().toISOString(), failures,
  nodes: nodes.map(n => ({ name: n.metadata.name, ready: n.status.conditions.find(c => c.type === 'Ready')?.status })),
  deployments: apps.map(d => ({ name: d.metadata.name, desired: d.spec.replicas,
    ready: d.status.readyReplicas || 0, images: d.spec.template.spec.containers.map(c => c.image) })),
  pods: pods.map(p => ({ name: p.metadata.name, node: p.spec.nodeName,
    containers: (p.status.containerStatuses || []).map(c => ({ name: c.name, ready: c.ready, restarts: c.restartCount })) })) };
writeFileSync('artifacts/cluster-snapshot.json', JSON.stringify(snapshot, null, 2));
console.log(`Cluster checks: ${failures.length ? 'FAILED' : 'PASSED'}`);
for (const failure of failures) console.error(failure);
process.exitCode = failures.length ? 1 : 0;
