"""Audit this run's explicit order IDs across MySQL, Redis and retained MQ messages."""
import datetime
import json
import re
import subprocess
import sys

ids = sys.argv[1].split(',') if len(sys.argv) > 1 else []
if not 1 <= len(ids) <= 100 or len(ids) != len(set(ids)) or any(not re.fullmatch(r'ORD-[0-9]+-[0-9]+', i) for i in ids):
    raise RuntimeError('Provide 1..100 unique order IDs from the checkout soak log')
K = ['/usr/local/bin/k3s', 'kubectl']

def run(args, text=None):
    p = subprocess.run(args, input=text, text=True, capture_output=True, timeout=45)
    if p.returncode: raise RuntimeError('Batch audit command failed')
    return p.stdout.strip()

out = {'utc': datetime.datetime.now(datetime.timezone.utc).isoformat(), 'expectedOrders': len(ids)}
try:
    cfg = json.loads(run(K + ['-n', 'galaxy-mall', 'get', 'cm', 'galaxy-mall-config', '-o', 'json']))['data']
    topic, group = cfg['GALAXY_ORDER_ROCKETMQ_TOPIC'], cfg['GALAXY_ORDER_ROCKETMQ_CONSUMER_GROUP']
    if any(not re.fullmatch(r'[A-Za-z0-9_-]+', s) for s in [topic, group]): raise RuntimeError('Unexpected MQ names')
    mysql = K + ['-n', 'middleware', 'exec', '-i', 'deploy/mysql', '--', 'sh', '-c',
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql -uroot -N -B']
    predicate = ','.join("'%s'" % i for i in ids)
    rows = []
    for shard in range(2):
        for table in range(4):
            query = "SELECT order_id,user_id,product_id,quantity,amount_cent,status,async_attempts FROM galaxy_order_%d.t_order_%d WHERE order_id IN (%s);" % (shard, table, predicate)
            rows.extend([{'table': 'galaxy_order_%d.t_order_%d' % (shard, table), 'fields': line.split('\t')}
                         for line in run(mysql, query).splitlines() if line])
    out['dbRows'] = rows
    lua = 'local r={};for _,id in ipairs(ARGV) do r[id]=redis.call("HMGET","galaxy:stock:reservation:"..id,"productId","quantity","status") end;return cjson.encode(r)'
    redis = K + ['-n', 'middleware', 'exec', 'deploy/redis', '--', 'sh', '-c',
        'REDISCLI_AUTH="$REDIS_PASSWORD" exec redis-cli --raw "$@"', 'audit', 'EVAL_RO', lua, '0'] + ids
    out['redisReservations'] = json.loads(run(redis))
    mq = K + ['-n', 'middleware', 'exec', 'rocketmq-broker-0', '--', 'sh', 'mqadmin']
    status = run(mq + ['topicStatus', '-n', 'rocketmq-namesrv:9876', '-t', topic])
    queues = []
    for line in status.splitlines():
        f = line.split()
        if len(f) >= 4 and f[1].isdigit() and f[2].isdigit() and f[3].isdigit():
            if int(f[3]) - int(f[2]) > 2000: raise RuntimeError('Refuse unbounded retained-topic scan')
            queues.append(f[:4])
    begin = str(min(int(i.split('-')[1]) for i in ids) - 1000)
    end = str(max(int(i.split('-')[1]) for i in ids) + 5000)
    records = []
    for broker, queue, low, high in queues:
        raw = run(mq + ['printMsgByQueue', '-n', 'rocketmq-namesrv:9876', '-t', topic, '-a', broker,
            '-i', queue, '-b', begin, '-e', end, '-p', 'true', '-d', 'true'])
        for line in raw.splitlines():
            match = re.search(r'BODY:\s*(ORD-[0-9]+-[0-9]+)\s*$', line)
            offset = re.search(r'queueOffset=(\d+)', line)
            if match and offset and match.group(1) in ids:
                records.append({'orderId': match.group(1), 'broker': broker, 'queue': int(queue), 'offset': int(offset.group(1))})
    progress = run(mq + ['consumerProgress', '-n', 'rocketmq-namesrv:9876', '-g', group])
    consumer = {}
    for line in progress.splitlines():
        f = line.split()
        if len(f) >= 6 and f[0] == topic and f[2].isdigit() and f[4].isdigit():
            consumer[(f[1], int(f[2]))] = int(f[4])
    for item in records:
        item['consumerPassedOffset'] = consumer.get((item['broker'], item['queue']), -1) > item['offset']
    out['mqRecords'] = records
    per_order = {}
    for order_id in ids:
        db_matches = [r for r in rows if r['fields'][0] == order_id]
        messages = [r for r in records if r['orderId'] == order_id]
        per_order[order_id] = {
            'oneDbRow': len(db_matches) == 1,
            'paidExpectedOwnerProductAmount': len(db_matches) == 1 and db_matches[0]['fields'][1:6] == ['u100', 'sku-coffee', '1', '12800', 'PAID'],
            'redisConfirmed': out['redisReservations'].get(order_id) == ['sku-coffee', '1', 'CONFIRMED'],
            'oneStoredMqMessageInTimeWindow': len(messages) == 1,
            'consumerPassedMessage': len(messages) == 1 and messages[0]['consumerPassedOffset'],
        }
    out['checks'] = per_order
    out['passed'] = all(all(check.values()) for check in per_order.values())
    out['limitation'] = 'One retained publication per ID in the test time window; not proof of exactly-once consumer execution.'
except Exception as error:
    out.update({'passed': False, 'error': str(error)})
print(json.dumps(out, ensure_ascii=False, indent=2))
sys.exit(0 if out.get('passed') else 1)
