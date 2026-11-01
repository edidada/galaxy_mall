"""Isolated K3s Pod failure exercise. Never scales/deletes a business Deployment."""
import datetime
import json
import subprocess
import sys
import threading
import time
import urllib.request
import uuid

K = ['/usr/local/bin/k3s', 'kubectl']
run_id = uuid.uuid4().hex[:10]
namespace = 'galaxy-mall-e2e-' + run_id
labels = {'app.kubernetes.io/managed-by': 'galaxy-mall-e2e', 'e2e-run': run_id}
out = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'namespace': namespace,
       'scope': 'isolated nginx canary -> existing gateway catalog GET only'}
created = False
stop = threading.Event()
samples = []
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

def cmd(args, data=None):
    p = subprocess.run(K + args, input=data, text=True, capture_output=True, timeout=100)
    if p.returncode:
        raise RuntimeError('kubectl command failed: ' + ' '.join(args[:5]))
    return p.stdout

def obj(args):
    return json.loads(cmd(args + ['-o', 'json']))

def canary(args):
    return ['-n', namespace] + args

def verify_owner():
    ns = obj(['get', 'namespace', namespace])
    if ns['metadata'].get('labels', {}).get('e2e-run') != run_id:
        raise RuntimeError('Refuse mutation: canary ownership mismatch')

def query(url):
    started = time.monotonic()
    try:
        with opener.open(url, timeout=3) as response:
            body = json.load(response)
            return {'ok': response.status == 200 and body.get('success') is True,
                    'elapsedMs': round((time.monotonic() - started) * 1000, 2)}
    except Exception as error:
        return {'ok': False, 'error': type(error).__name__, 'detail': str(error)[:180]}

try:
    source = obj(['-n', 'galaxy-mall', 'get', 'pods', '-l', 'app=front'])['items'][0]
    expected_image_id = source['status']['containerStatuses'][0]['imageID']
    image = source['spec']['containers'][0]['image']
    if not image.startswith('registry.galaxy.local:30500/galaxy-mall/front:'):
        raise RuntimeError('Unexpected front image; inspect before canary deployment')
    out['image'] = image
    out['expectedImageID'] = expected_image_id
    cmd(['create', '-f', '-'], json.dumps({'apiVersion': 'v1', 'kind': 'Namespace',
        'metadata': {'name': namespace, 'labels': labels}}))
    created = True
    pull_secrets = source['spec'].get('imagePullSecrets', [])
    if not pull_secrets:
        account = obj(['-n', 'galaxy-mall', 'get', 'serviceaccount', source['spec'].get('serviceAccountName', 'default')])
        pull_secrets = account.get('imagePullSecrets', [])
    for ref in pull_secrets:
        secret = obj(['-n', 'galaxy-mall', 'get', 'secret', ref['name']])
        if secret.get('type') != 'kubernetes.io/dockerconfigjson':
            raise RuntimeError('Only copy the source registry pull credential')
        # Never emit credential bytes. The copy is removed with the owned test namespace.
        cmd(['create', '-f', '-'], json.dumps({'apiVersion': 'v1', 'kind': 'Secret',
            'metadata': {'name': ref['name'], 'namespace': namespace},
            'type': secret['type'], 'data': secret['data']}))
    nginx = '''server {
      listen 80;
      location = /ready { access_log off; return 200 "canary-ready"; }
      location /api/ {
        proxy_connect_timeout 2s; proxy_read_timeout 5s;
        proxy_pass http://galaxy-mall-gateway.galaxy-mall.svc.cluster.local:8080;
      }
    }'''
    resources = [
      {'apiVersion': 'v1', 'kind': 'ConfigMap', 'metadata': {'name': 'nginx', 'namespace': namespace},
       'data': {'default.conf': nginx}},
      {'apiVersion': 'apps/v1', 'kind': 'Deployment', 'metadata': {'name': 'canary', 'namespace': namespace},
       'spec': {'replicas': 2, 'selector': {'matchLabels': {'app': 'canary', 'e2e-run': run_id}},
        'template': {'metadata': {'labels': {'app': 'canary', 'e2e-run': run_id}}, 'spec': {
          'automountServiceAccountToken': False,
          'imagePullSecrets': pull_secrets,
          'nodeSelector': {'kubernetes.io/hostname': source['spec']['nodeName']},
          'terminationGracePeriodSeconds': 10,
          'containers': [{'name': 'nginx', 'image': image, 'imagePullPolicy': 'IfNotPresent',
            'resources': {'requests': {'cpu': '25m', 'memory': '32Mi'}, 'limits': {'cpu': '200m', 'memory': '96Mi'}},
            'readinessProbe': {'httpGet': {'path': '/ready', 'port': 80}, 'periodSeconds': 1},
            'lifecycle': {'preStop': {'exec': {'command': ['/bin/sh', '-c', 'sleep 3']}}},
            'volumeMounts': [{'name': 'config', 'mountPath': '/etc/nginx/conf.d', 'readOnly': True}]}],
          'volumes': [{'name': 'config', 'configMap': {'name': 'nginx'}}]}}}},
      {'apiVersion': 'v1', 'kind': 'Service', 'metadata': {'name': 'canary', 'namespace': namespace},
       'spec': {'selector': {'app': 'canary', 'e2e-run': run_id}, 'ports': [{'port': 80, 'targetPort': 80}]}}
    ]
    for resource in resources: cmd(['create', '-f', '-'], json.dumps(resource))
    cmd(canary(['rollout', 'status', 'deploy/canary', '--timeout=90s']))
    out['canaryImageIDs'] = [p['status']['containerStatuses'][0]['imageID']
        for p in obj(canary(['get', 'pods', '-l', 'e2e-run=' + run_id]))['items']]
    out['sourceDigestMatchesCanary'] = all(image_id == expected_image_id for image_id in out['canaryImageIDs'])
    if not out['sourceDigestMatchesCanary']:
        raise RuntimeError('Canary digest differs from source; refusing a misleading fault exercise')
    service_ip = obj(canary(['get', 'svc', 'canary']))['spec']['clusterIP']
    url = 'http://' + service_ip + '/api/mall/products'
    baseline = []
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        baseline.append(query(url))
        if len(baseline) >= 5 and all(s['ok'] for s in baseline[-5:]): break
        time.sleep(.25)
    out['baseline'] = baseline
    if len(baseline) < 5 or not all(s['ok'] for s in baseline[-5:]):
        raise RuntimeError('Canary baseline failed')

    def load():
        while not stop.is_set():
            samples.append(query(url))
            stop.wait(.2)

    worker = threading.Thread(target=load, daemon=True)
    worker.start()
    time.sleep(2)
    verify_owner()
    pods = obj(canary(['get', 'pods', '-l', 'e2e-run=' + run_id]))['items']
    victim = pods[0]['metadata']['name']
    cmd(canary(['delete', 'pod', victim, '--wait=false']))
    out['injectedPodDeletion'] = victim
    # Check that the controller creates a different Ready Pod, not just the remaining replica.
    deadline = time.monotonic() + 90
    recovered = False
    while time.monotonic() < deadline:
        alive = obj(canary(['get', 'pods', '-l', 'e2e-run=' + run_id]))['items']
        ready = [p for p in alive if p['metadata']['name'] != victim and not p['metadata'].get('deletionTimestamp')
                 and any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status'].get('conditions', []))]
        if len(ready) == 2:
            recovered = True
            break
        time.sleep(1)
    time.sleep(5)
    stop.set()
    worker.join(timeout=5)
    out['duringDeletion'] = {'requests': len(samples), 'failures': sum(not s['ok'] for s in samples),
                            'replacementReady': recovered}

    # Full outage is limited to the two canary replicas in this newly owned namespace.
    verify_owner()
    cmd(canary(['scale', 'deploy/canary', '--replicas=0']))
    deadline = time.monotonic() + 30
    observed_outage = False
    while time.monotonic() < deadline:
        if not query(url)['ok']:
            observed_outage = True
            break
        time.sleep(.5)
    cmd(canary(['scale', 'deploy/canary', '--replicas=2']))
    recovery_started = time.monotonic()
    cmd(canary(['rollout', 'status', 'deploy/canary', '--timeout=90s']))
    recovery_samples = []
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        recovery_samples.append(query(url))
        if len(recovery_samples) >= 5 and all(s['ok'] for s in recovery_samples[-5:]): break
        time.sleep(.25)
    restored = len(recovery_samples) >= 5 and all(s['ok'] for s in recovery_samples[-5:])
    out['fullOutage'] = {'observed': observed_outage, 'recovered': restored,
        'recoveryMs': round((time.monotonic() - recovery_started) * 1000, 2),
        'recoverySamples': recovery_samples}
    out['passed'] = recovered and not out['duringDeletion']['failures'] and observed_outage and restored and out['sourceDigestMatchesCanary']
except Exception as error:
    out['passed'] = False
    out['error'] = str(error)
finally:
    stop.set()
    if created:
        try:
            verify_owner()
            cmd(['delete', 'namespace', namespace, '--wait=true', '--timeout=90s'])
            out['cleanup'] = 'owned test namespace deleted'
        except Exception as error:
            out['cleanup'] = 'FAILED: ' + str(error)
            out['passed'] = False
print(json.dumps(out, ensure_ascii=False, indent=2))
sys.exit(0 if out.get('passed') else 1)
