"""Issue 140: disable the temporary REPEAT_PURCHASE mapping and print the template slots (no secrets)."""
import json, sys
sys.path.insert(0, '/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local')
from local_api import LocalApi
from live_cases_oct24 import request
api = LocalApi()
_, data = request(api, 'GET', '/api/templates')
for t in data['items']:
    if t['templateIntent'] == 'REPEAT_PURCHASE' and t['mapped'] and t['enabled']:
        request(api, 'PUT', '/api/templates/REPEAT_PURCHASE', {'providerTemplateName': t['providerTemplateName'],
                'language': t['language'], 'parameterNames': t['parameterNames'], 'enabled': False},
                {'If-Match': '"' + str(t['version']) + '"'})
_, data = request(api, 'GET', '/api/templates')
print(json.dumps([{k: t[k] for k in ('templateIntent', 'mapped', 'enabled', 'sendable')} for t in data['items'] if t['mapped']]))
