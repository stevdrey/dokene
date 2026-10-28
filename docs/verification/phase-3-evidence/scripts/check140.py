"""Issue 140: integration state and a template approval refresh through Graph (read only on Meta); prints no secrets."""
import json, sys
sys.path.insert(0, '/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local')
from local_api import LocalApi
from live_cases_oct24 import map_template
api = LocalApi()
s, h, integ = api.call('GET', '/api/integrations/whatsapp')
print(json.dumps({'integration_http': s, 'enabled': integ.get('enabled'), 'webhookVerified': integ.get('webhookVerified'),
                  'outboundEnabled': integ.get('outboundEnabled')}))
m = map_template(api, 'es')
print(json.dumps({k: m.get(k) for k in ('templateIntent', 'approvalStatus', 'sendable', 'language', 'version')}))
