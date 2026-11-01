"""Bounded read-only sampling during a soak; no secrets or application log bodies."""
import datetime
import json
import subprocess
import sys
import time

seconds = int(sys.argv[1]) if len(sys.argv) > 1 else 1200
if not 60 <= seconds <= 3600:
    raise RuntimeError('Observation duration must be 60..3600 seconds')
K = ['/usr/local/bin/k3s', 'kubectl']


def run(args):
    p = subprocess.run(K + args, text=True, capture_output=True, timeout=30)
    if p.returncode:
        raise RuntimeError('Read-only observation command failed: ' + ' '.join(args[:4]))
    return p.stdout.strip()


def items(args):
    return json.loads(run(args + ['-o', 'json']))['items']


out = {'startedUtc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'durationSeconds': seconds,
       'intervalSeconds': 60, 'samples': [], 'scope': 'Point samples, not continuous resource/event coverage'}
deadline = time.monotonic() + seconds
baseline = None
try:
    while time.monotonic() < deadline:
        sample = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
        nodes = items(['get', 'nodes'])
        sample['nodes'] = [{'name': n['metadata']['name'], 'ready': any(c['type'] == 'Ready' and c['status'] == 'True'
            for c in n['status']['conditions'])} for n in nodes]
        pods = items(['-n', 'galaxy-mall', 'get', 'pods'])
        sample['pods'] = [{'name': p['metadata']['name'], 'uid': p['metadata']['uid'],
            'ready': any(c['type'] == 'Ready' and c['status'] == 'True' for c in p['status'].get('conditions', [])),
            'restarts': sum(c['restartCount'] for c in p['status'].get('containerStatuses', []))} for p in pods]
        if baseline is None:
            baseline = {p['uid']: p['restarts'] for p in sample['pods']}
        sample['businessPodsUnchanged'] = {p['uid']: p['restarts'] for p in sample['pods']} == baseline
        sample['nodeResources'] = run(['top', 'nodes', '--no-headers'])
        sample['businessResources'] = run(['-n', 'galaxy-mall', 'top', 'pods', '--no-headers'])
        sample['healthy'] = bool(sample['nodes']) and all(n['ready'] for n in sample['nodes']) and \
            len(sample['pods']) >= 7 and all(p['ready'] for p in sample['pods']) and sample['businessPodsUnchanged']
        out['samples'].append(sample)
        time.sleep(min(60, max(0, deadline - time.monotonic())))
    out['passed'] = bool(out['samples']) and all(s['healthy'] for s in out['samples'])
except Exception as error:
    out.update({'passed': False, 'error': str(error)})
out['finishedUtc'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
print(json.dumps(out, ensure_ascii=False, indent=2))
sys.exit(0 if out.get('passed') else 1)
