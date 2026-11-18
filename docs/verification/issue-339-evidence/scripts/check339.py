"""Issue 339, P3-MQA-LIVE-02: prove sender ownership and template approval against real Meta Graph (read-only on Meta).

Runs through Dokene's normal API: ownership verification and template approval refresh both call Graph. Prints only
redacted readiness (booleans, statuses, language, HTTP codes), never an id, token, phone number or template body.
"""
import json, sys
sys.path.insert(0, '/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local')
sys.path.insert(0, '/home/stevdrey/qa339')
from local_api339 import LocalApi
from live_cases_oct24 import map_template

api = LocalApi()
s, h, integ = api.call('GET', '/api/integrations/whatsapp')
print(json.dumps({'step': 'integration', 'http': s, 'enabled': integ.get('enabled'),
                  'webhookVerified': integ.get('webhookVerified'), 'outboundEnabled': integ.get('outboundEnabled')}))
s, h, proof = api.call('POST', '/api/integrations/whatsapp/ownership-verifications', None, {'If-Match': h['ETag']})
print(json.dumps({'step': 'ownership-verification (Graph)', 'http': s,
                  'webhookVerified': (proof or {}).get('webhookVerified'),
                  'problem': None if s < 300 else {k: (proof or {}).get(k) for k in ('code', 'title', 'status')}}))
if s >= 300:
    sys.exit(3)
m = map_template(api, 'es')
print(json.dumps({'step': 'template approval-refresh (Graph)', **{k: m.get(k) for k in
                  ('templateIntent', 'approvalStatus', 'sendable', 'language', 'approvalCheckedAt')},
                  'bodyPresent': bool(m.get('content', {}).get('bodyText'))}))
