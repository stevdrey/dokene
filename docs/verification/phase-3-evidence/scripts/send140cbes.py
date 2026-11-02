"""Issue 140 live journey: one authorized template send through Dokene's normal API, then read-only status.

Durable state and fixed idempotency keys keep a rerun from creating a second message or provider attempt.
Prints no phone number, credential or provider payload.
"""
from pathlib import Path
import argparse, json, sys
from datetime import date
sys.path.insert(0, '/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local')
from local_api import LocalApi
from live_cases_oct24 import map_template, request

DIAG = Path('/home/stevdrey/.codex/.chatgpt-projects/g-p-6a8b7129b5c081919574ba9938baba0a/whatsapp-local/live-cases-20261024.json')
STATE = Path('/home/stevdrey/qa140/live-140-cb-es.json')
RUN_PREFIX = 'dokene-live-140-20261029-es-callback'
FIELDS = ('status', 'outcomeUnknown', 'failureCategory', 'providerMessageId', 'sentAt', 'deliveredAt', 'readAt')

def save(state):
    STATE.write_text(json.dumps(state, indent=2)); STATE.chmod(0o600)

def outbound(api, enabled):
    headers, _ = request(api, 'GET', '/api/integrations/whatsapp')
    _, data = request(api, 'PUT', '/api/integrations/whatsapp/outbound', {'enabled': enabled}, {'If-Match': headers['ETag']})
    print(json.dumps({'tenantOutboundEnabled': data['outboundEnabled']}))

def tenant(api):
    # Read only: the tenant switch as the API reports it, so a wrapper can verify the window is closed.
    _, data = request(api, 'GET', '/api/integrations/whatsapp')
    print(json.dumps({'tenantOutboundEnabled': data['outboundEnabled']}))

def send(api, state):
    if state.get('messageId'):
        raise RuntimeError('Send already recorded; inspect status, never resend automatically.')
    diag = json.loads(DIAG.read_text())
    base = '/api/customers/' + diag['customerId']
    headers, policy = request(api, 'GET', base + '/contact-policy')
    assert not policy['doNotContact'], 'Recipient opted out'
    consent = next(c for c in policy['consents'] if c['contactId'] == diag['contactId'] and c['channel'] == 'WHATSAPP')
    assert consent['status'] == 'GRANTED', 'No WhatsApp consent'
    headers, _ = request(api, 'GET', base + '/follow-up-policy')
    request(api, 'PUT', base + '/follow-up-policy', {'cadenceDays': None, 'explicitNextDate': date.today().isoformat()},
            {'If-Match': headers['ETag']})
    _, eligible = request(api, 'GET', base + '/follow-up-eligibility')
    assert eligible['eligible'], 'Recipient not eligible: ' + json.dumps(eligible)
    mapping = map_template(api, 'es')
    headers, _ = request(api, 'GET', base + '/follow-up-policy')
    body = {'contactId': diag['contactId'], 'action': 'GENERAL_CHECK_IN', 'templateIntent': 'GENERAL_FOLLOW_UP',
            'body': mapping['content']['bodyText'], 'locale': 'es-419', 'origin': 'MANUAL'}
    headers, message = request(api, 'POST', base + '/messages', body,
                               {'If-Match': headers['ETag'], 'Idempotency-Key': RUN_PREFIX + '-submit'}, expected=(200, 201))
    state.update({'messageId': message['messageId'], 'template': mapping['providerTemplateName']}); save(state)
    headers, message = request(api, 'POST', '/api/messages/' + message['messageId'] + '/approvals',
        {'decision': 'APPROVE', 'note': 'Issue 140 live journey explicitly authorized by the recipient.',
         'templateRevision': message['template']['revision']},
        {'If-Match': headers['ETag'], 'Idempotency-Key': RUN_PREFIX + '-approve'}, expected=(200, 201))
    state['dispatchRequested'] = True; save(state)
    _, message = request(api, 'POST', '/api/messages/' + state['messageId'] + '/sends',
        headers={'If-Match': headers['ETag'], 'Idempotency-Key': RUN_PREFIX + '-send'}, expected=(200, 201))
    state.update({k: message.get(k) for k in FIELDS}); state['attempts'] = len(message['attempts']); save(state)
    print(json.dumps({k: state.get(k) for k in ('template', 'status', 'outcomeUnknown', 'failureCategory', 'sentAt', 'attempts')}))

def status(api, state):
    _, m = request(api, 'GET', '/api/messages/' + state['messageId'])
    _, ev = request(api, 'GET', '/api/messages/' + state['messageId'] + '/events')
    state.update({k: m.get(k) for k in FIELDS}); state['attempts'] = len(m['attempts']); save(state)
    print(json.dumps({**{k: m.get(k) for k in FIELDS if k != 'providerMessageId'}, 'attempts': len(m['attempts']),
                      'events': [(e['type'], e.get('statusTo')) for e in ev['events']]}))

if __name__ == '__main__':
    action = argparse.ArgumentParser(); action.add_argument('action', choices=('enable', 'disable', 'tenant', 'send', 'status'))
    a = action.parse_args().action
    state = json.loads(STATE.read_text()) if STATE.exists() else {'testedCommit': '80c7ab7'}
    api = LocalApi()
    if a in ('enable', 'disable'): outbound(api, a == 'enable')
    elif a == 'tenant': tenant(api)
    elif a == 'send': send(api, state)
    else: status(api, state)
