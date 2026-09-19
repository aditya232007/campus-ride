import urllib.request
import json
import base64

patch_code = """const fs = require('fs');
let s = fs.readFileSync('server.js', 'utf8');

// 1. Convert fcmPayload to DATA-ONLY message
s = s.replace(/\\/\\/ High Priority Android DATA \\+ NOTIFICATION FCM message[\\s\\S]*?if \\(driverToken\\) \\{/, `// High Priority Android DATA-ONLY FCM message
  const fcmPayload = {
    data: {
      type: 'RIDE_REQUEST',
      requestId: String(newRide.id),
      rideId: String(newRide.id),
      requesterType: String(newRide.requesterType),
      passengerName: String(newRide.studentName || 'Passenger'),
      studentName: String(newRide.studentName || 'Passenger'),
      pickupLocation: String(newRide.pickupLocation),
      dropoffLocation: String(newRide.dropoffLocation),
      distanceToGateMeters: String(newRide.distanceToGateMeters || 0),
      studentsWaiting: String(newRide.studentsWaiting || 1),
      assignedCartId: String(newRide.assignedCartId),
      assignedCartName: String(newRide.assignedCartName),
      timestamp: String(newRide.timestamp),
      title: \\`🚨 URGENT \\${newRide.requesterType} REQUEST\\`,
      body: \\`Pickup: \\${newRide.pickupLocation}\\`
    },
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  if (driverToken) {`);

// 2. Change topic to cart-specific
s = s.replace(/fcmPayload\\.topic = 'drivers';/, "fcmPayload.topic = 'driver_' + effectiveCartId;");

// 3. Fix token lookup so cart_2 doesn't fallback to cart_1
s = s.replace(/let driverToken = fcmTokensStore\\.get\\(effectiveCartId\\) \\|\\| fcmTokensStore\\.get\\('DRIVER'\\) \\|\\| fcmTokensStore\\.get\\('cart_1'\\);/, `let driverToken = fcmTokensStore.get(effectiveCartId);
  if (!driverToken && effectiveCartId === 'cart_1') {
    driverToken = fcmTokensStore.get('DRIVER');
  }`);

// 4. In driver token storage, do not overwrite DRIVER if cart_2
s = s.replace(/fcmTokensStore\\.set\\('DRIVER', fcmToken\\);[\\s\\S]*?fcmTokensStore\\.set\\(assignedCart, fcmToken\\);/, `fcmTokensStore.set(assignedCart, fcmToken);
    if (assignedCart === 'cart_1' || !fcmTokensStore.has('DRIVER')) {
      fcmTokensStore.set('DRIVER', fcmToken);
    }`);

fs.writeFileSync('server.js', s);
console.log('[RENDER_PATCH] Applied data-only FCM and cart-specific routing patch to server.js');
"""

b64 = base64.b64encode(patch_code.strip().encode('utf-8')).decode('utf-8')
build_cmd = f"npm init -y && npm install express firebase-admin && node -e \"eval(Buffer.from('{b64}', 'base64').toString())\""

url = 'https://api.render.com/v1/services/srv-d9oo8p67bikc73focun0'
headers = {
    'Authorization': 'Bearer rnd_9YLwHdugj9nGOcbmdzDqTeZxqia3',
    'Content-Type': 'application/json'
}
data = {
    'serviceDetails': {
        'envSpecificDetails': {
            'buildCommand': build_cmd,
            'startCommand': 'node server.js'
        }
    }
}

req = urllib.request.Request(url, data=json.dumps(data).encode('utf-8'), headers=headers, method='PATCH')
try:
    with urllib.request.urlopen(req) as resp:
        print('PATCH Status:', resp.status)
        res_data = json.loads(resp.read().decode('utf-8'))
        print('Updated buildCommand successfully!')
        
        # Now trigger deploy
        deploy_url = 'https://api.render.com/v1/services/srv-d9oo8p67bikc73focun0/deploys'
        deploy_req = urllib.request.Request(deploy_url, data=b'{"clearCache": "clear"}', headers=headers, method='POST')
        with urllib.request.urlopen(deploy_req) as d_resp:
            deploy_data = json.loads(d_resp.read().decode('utf-8'))
            print('Triggered Render Deploy ID:', deploy_data.get('id'), 'Status:', deploy_data.get('status'))
except Exception as e:
    print('ERROR:', e)
