"""Replay ONE event for a recently PAID test order, then audit business state."""
import datetime
import json
import re
import subprocess
import sys
import time

K = ['/usr/local/bin/k3s', 'kubectl']
order_id = sys.argv[1] if len(sys.argv) > 1 else ''
if not re.fullmatch(r'ORD-[A-Za-z0-9_-]+', order_id): raise RuntimeError('Provide this run\'s test order ID')

def run(args, data=None):
    p = subprocess.run(args, input=data, text=True, capture_output=True, timeout=40)
    if p.returncode: raise RuntimeError('Audit/replay command failed')
    return p.stdout.strip()

config = json.loads(run(K + ['-n', 'galaxy-mall', 'get', 'cm', 'galaxy-mall-config', '-o', 'json']))['data']
topic = config['GALAXY_ORDER_ROCKETMQ_TOPIC']
group = config['GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP']
if any(not re.fullmatch(r'[A-Za-z0-9_-]+', s) for s in [topic, group]): raise RuntimeError('Unexpected topic/group')
mysql = K + ['-n', 'middleware', 'exec', '-i', 'deploy/mysql', '--', 'sh', '-c',
    'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B']
redis = K + ['-n', 'middleware', 'exec', 'deploy/redis', '--', 'sh', '-c',
    'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw "$@"', 'audit', 'GET', 'galaxy:stock:sku-coffee']
mq = K + ['-n', 'middleware', 'exec', 'rocketmq-broker-0', '--', 'sh', 'mqadmin']

def db():
    return [r for shard in range(2) for table in range(4)
            for r in run(mysql, "SELECT order_id,user_id,product_id,quantity,amount_cent,status,async_attempts FROM galaxy_order_%d.t_order_%d WHERE order_id='%s' AND created_at >= NOW() - INTERVAL 1 HOUR;" % (shard, table, order_id)).splitlines() if r]

def offsets():
    result = {}
    for line in run(mq + ['topicStatus', '-n', 'rocketmq-namesrv:9876', '-t', topic]).splitlines():
        f = line.split()
        if len(f) >= 4 and f[1].isdigit() and f[3].isdigit(): result[(f[0], f[1])] = int(f[3])
    return result

out = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'orderId': order_id,
       'scope': 'ONE duplicate event for an owned PAID test order; no offset reset'}
try:
    before_db = db()
    if len(before_db) != 1 or before_db[0].split('\t')[1:6] != ['u100', 'sku-coffee', '1', '12800', 'PAID']:
        raise RuntimeError('Refuse replay: order is not a recent owned paid test sample')
    before_stock = run(redis)
    before_offset = offsets()
    sent = run(mq + ['sendMessage', '-n', 'rocketmq-namesrv:9876', '-t', topic, '-p', order_id,
                      '-k', 'e2e-replay-' + order_id])
    out['sendResult'] = sent
    if 'SEND_OK' not in sent: raise RuntimeError('No SEND_OK acknowledgement')
    after_offset = offsets()
    location = None
    for (broker, queue), high in after_offset.items():
        low = before_offset.get((broker, queue), high)
        if high - low > 10: raise RuntimeError('Concurrent MQ writes exceed bounded audit window')
        for offset in range(low, high):
            message = run(mq + ['queryMsgByOffset', '-n', 'rocketmq-namesrv:9876', '-t', topic,
                '-b', broker, '-i', queue, '-o', str(offset), '-f', 'UTF-8'])
            if re.search(r'^Message Body:\s*' + re.escape(order_id) + r'\s*$', message, re.M):
                location = (broker, queue, offset)
    if location is None: raise RuntimeError('Replay message not found in new offsets')
    consumed = False
    for attempt in range(8):
        progress = run(mq + ['consumerProgress', '-n', 'rocketmq-namesrv:9876', '-g', group])
        for line in progress.splitlines():
            f = line.split()
            if len(f) >= 6 and f[:3] == [topic, location[0], location[1]] and f[4].isdigit():
                consumed = int(f[4]) > location[2]
        if consumed: break
        time.sleep(1)
    after_db = db()
    after_stock = run(redis)
    out.update({'replayLocation': {'broker': location[0], 'queue': int(location[1]), 'offset': location[2]},
        'consumerPassedReplay': consumed, 'stockBefore': before_stock, 'stockAfter': after_stock,
        'dbBefore': before_db, 'dbAfter': after_db,
        'passed': consumed and before_stock == after_stock and before_db == after_db})
except Exception as error:
    out.update({'passed': False, 'error': str(error)})
print(json.dumps(out, ensure_ascii=False, indent=2))
sys.exit(0 if out.get('passed') else 1)
