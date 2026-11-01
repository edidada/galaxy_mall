"""Run on the K3s master. Reads only; never dumps Secrets or full ConfigMaps."""
import datetime
import json
import re
import subprocess
import sys

K = ['/usr/local/bin/k3s', 'kubectl']

def run(args, data=None):
    p = subprocess.run(args, input=data, text=True, capture_output=True, timeout=45)
    if p.returncode:
        # CLI errors can contain env values; keep failure evidence bounded.
        return {'error': 'command failed', 'exit': p.returncode, 'command': args[:6]}
    return p.stdout.strip()

def get(ns, kind):
    args = K + (['-n', ns] if ns else []) + ['get', kind, '-o', 'json']
    data = run(args)
    if isinstance(data, dict):
        raise RuntimeError('Kubernetes read failed')
    return json.loads(data)['items']

out = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
apps = get('galaxy-mall', 'deployments')
out['deployments'] = [{'name': d['metadata']['name'], 'replicas': d['spec']['replicas'],
    'ready': d['status'].get('readyReplicas', 0),
    'images': [c['image'] for c in d['spec']['template']['spec']['containers']]} for d in apps]
out['pods'] = [{'name': p['metadata']['name'], 'uid': p['metadata']['uid'], 'node': p['spec'].get('nodeName'),
    'containers': [{'name': c['name'], 'ready': c['ready'], 'restarts': c['restartCount'],
      'imageID': c.get('imageID')} for c in p['status'].get('containerStatuses', [])]}
    for p in get('galaxy-mall', 'pods')]
out['nodes'] = [{'name': n['metadata']['name'], 'ready': any(c['type'] == 'Ready' and c['status'] == 'True'
    for c in n['status']['conditions'])} for n in get('', 'nodes')]
out['isolatedTestNamespaces'] = [n['metadata']['name'] for n in get('', 'namespaces')
    if n['metadata'].get('labels', {}).get('app.kubernetes.io/managed-by') == 'galaxy-mall-e2e']
allowed = ['SPRING_PROFILES_ACTIVE', 'GALAXY_STOCK_BACKEND', 'GALAXY_ORDER_MESSAGING_BACKEND',
    'GALAXY_ORDER_STORE_BACKEND', 'GALAXY_ORDER_ROCKETMQ_TOPIC', 'GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP',
    'REDIS_HOST', 'ROCKETMQ_NAME_SERVER']
out['config'] = {c['metadata']['name']: {k: v for k, v in c.get('data', {}).items() if k in allowed}
    for c in get('galaxy-mall', 'configmaps')}
for dep in apps:
    out.setdefault('explicitEnv', {})[dep['metadata']['name']] = {
        e['name']: e['value'] for c in dep['spec']['template']['spec']['containers']
        for e in c.get('env', []) if e['name'] in allowed and 'value' in e}
mysql = K + ['-n', 'middleware', 'exec', '-i', 'deploy/mysql', '--', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B']
order_id = sys.argv[1] if len(sys.argv) > 1 else ''
if order_id and not re.fullmatch(r'ORD-[A-Za-z0-9_-]+', order_id):
    raise RuntimeError('Invalid order ID')
sql = "SELECT table_schema,table_name FROM information_schema.tables WHERE table_schema IN ('galaxy_order_0','galaxy_order_1') AND table_name LIKE 't_order_%';"
out['dbTables'] = run(mysql, sql)
if isinstance(out['dbTables'], str):
    rows = []
    for line in out['dbTables'].splitlines():
        schema, table = line.split('\t')
        if not re.fullmatch(r'galaxy_order_[01]', schema) or not re.fullmatch(r't_order_[0-9]+', table):
            continue
        predicate = "order_id='%s'" % order_id if order_id else "product_id='sku-coffee' AND user_id='u100' AND created_at > NOW() - INTERVAL 20 MINUTE"
        query = "SELECT order_id,user_id,product_id,quantity,amount_cent,status,async_attempts FROM %s.%s WHERE %s ORDER BY created_at DESC LIMIT 5;" % (schema, table, predicate)
        result = run(mysql, query)
        if result: rows.append({'table': schema + '.' + table, 'rows': result})
    out['orders'] = rows
topic = next((cm.get('GALAXY_ORDER_ROCKETMQ_TOPIC') for cm in out['config'].values()
    if cm.get('GALAXY_ORDER_ROCKETMQ_TOPIC')), 'galaxy-order-submitted')
group = next((cm.get('GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP') for cm in out['config'].values()
    if cm.get('GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP')), 'galaxy-order-consumer')
for value in [topic, group]:
    if not re.fullmatch(r'[A-Za-z0-9_-]+', value): raise RuntimeError('Unexpected MQ name')
mq = K + ['-n', 'middleware', 'exec', 'rocketmq-broker-0', '--', 'sh', 'mqadmin']
out['mqTopic'] = run(mq + ['topicStatus', '-n', 'rocketmq-namesrv:9876', '-t', topic])
out['mqConsumers'] = run(mq + ['consumerProgress', '-n', 'rocketmq-namesrv:9876', '-g', group])
if order_id:
    # Correlate by message BODY: this producer does not set RocketMQ message keys.
    # Inspect at most 5 recent records per queue. Never reset/commit business offsets.
    queues = []
    if isinstance(out['mqTopic'], str):
        for line in out['mqTopic'].splitlines():
            fields = line.split()
            if len(fields) >= 6 and fields[1].isdigit() and fields[2].isdigit() and fields[3].isdigit():
                if re.fullmatch(r'[A-Za-z0-9_-]+', fields[0]):
                    queues.append(fields)
    out['mqMatchedMessage'] = None
    for fields in sorted(queues, key=lambda f: ' '.join(f[4:]), reverse=True):
        broker, queue, low, high = fields[:4]
        for offset in range(int(high) - 1, max(int(low) - 1, int(high) - 6), -1):
            message = run(mq + ['queryMsgByOffset', '-n', 'rocketmq-namesrv:9876', '-t', topic,
                '-b', broker, '-i', queue, '-o', str(offset), '-f', 'UTF-8'])
            if isinstance(message, str) and re.search(r'^Message Body:\s*' + re.escape(order_id) + r'\s*$', message, re.M):
                consumer_offset = None
                if isinstance(out['mqConsumers'], str):
                    for line in out['mqConsumers'].splitlines():
                        cols = line.split()
                        if len(cols) >= 6 and cols[:3] == [topic, broker, queue] and cols[4].isdigit():
                            consumer_offset = int(cols[4])
                out['mqMatchedMessage'] = {'topic': topic, 'broker': broker, 'queue': int(queue),
                    'offset': offset, 'bodyMatchesOrderId': True, 'consumerOffset': consumer_offset,
                    'consumerPassedOffset': consumer_offset is not None and consumer_offset > offset}
                break
        if out['mqMatchedMessage']: break
    out['orderLogs'] = {}
    for dep in ['galaxy-mall-order', 'galaxy-mall-payment', 'galaxy-mall-stock']:
        # Filtering in memory avoids printing unrelated customers' logs.
        logs = run(K + ['-n', 'galaxy-mall', 'logs', 'deploy/' + dep, '--all-pods=true', '--since=30m', '--tail=5000'])
        out['orderLogs'][dep] = [l for l in logs.splitlines() if order_id in l] if isinstance(logs, str) else logs
    redis = K + ['-n', 'middleware', 'exec', 'deploy/redis', '--', 'sh', '-c',
        'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw "$@"', 'audit']
    out['redisReservation'] = run(redis + ['HGETALL', 'galaxy:stock:reservation:' + order_id])
    out['redisStock'] = run(redis + ['GET', 'galaxy:stock:sku-coffee'])
print(json.dumps(out, ensure_ascii=False, indent=2))
