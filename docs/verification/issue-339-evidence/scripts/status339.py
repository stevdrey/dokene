"""Issue 339, P3-MQA-LIVE-01: local-only readiness read. Calls only Dokene's own API (no Meta Graph call).

Prints redacted facts only: booleans, statuses, counts and times. Never a phone number, id from Meta, token or body.
"""
import json, sys
from datetime import datetime, timezone
sys.path.insert(0, '/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local')
sys.path.insert(0, '/home/stevdrey/qa339')
from pathlib import Path
from local_api339 import LocalApi
from live_cases_oct24 import request

DIAG = json.loads(Path('/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local/live-cases-20261024.json').read_text())
api = LocalApi()
_, integ = request(api, 'GET', '/api/integrations/whatsapp')
print(json.dumps({'integration': {'enabled': integ['enabled'], 'outboundEnabled': integ['outboundEnabled'],
                  'webhookVerified': integ['webhookVerified'], 'secretRefSet': bool(integ['secretRef']),
                  'senderConfigured': bool(integ['phoneNumberId'])}}))
_, templates = request(api, 'GET', '/api/templates')
m = next(t for t in templates['items'] if t['templateIntent'] == 'GENERAL_FOLLOW_UP')
print(json.dumps({'template': {k: m.get(k) for k in ('mapped', 'enabled', 'language', 'approvalStatus', 'sendable', 'approvalCheckedAt')}}))
base = '/api/customers/' + DIAG['customerId']
_, policy = request(api, 'GET', base + '/contact-policy')
consent = next((c for c in policy['consents'] if c['contactId'] == DIAG['contactId'] and c['channel'] == 'WHATSAPP'), {})
print(json.dumps({'recipient': {'doNotContact': policy['doNotContact'], 'whatsappConsent': consent.get('status')}}))
_, page = request(api, 'GET', '/api/messages?customerId=' + DIAG['customerId'] + '&limit=50')
items = page.get('items') or page.get('messages') or []
last = max((i.get('updatedAt') for i in items if i.get('updatedAt')), default=None)
hours = None
if last:
    hours = round((datetime.now(timezone.utc) - datetime.fromisoformat(last.replace('Z', '+00:00'))).total_seconds() / 3600, 1)
print(json.dumps({'recipientMessages': {'count': len(items), 'byStatus': sorted({i.get('status') for i in items}),
                  'lastActivityAt': last, 'hoursSinceLastActivity': hours}}))
