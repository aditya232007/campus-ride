const express = require('express');
const admin = require('firebase-admin');

const app = express();
const PORT = process.env.PORT || 10000;

// Firebase Admin Initialization (Graceful Fallback)
let isFirebaseAdminInitialized = false;
try {
  if (!admin.apps.length) {
    admin.initializeApp({
      projectId: process.env.FIREBASE_PROJECT_ID || 'campus-ride-2b21b'
    });
    isFirebaseAdminInitialized = true;
  } else {
    isFirebaseAdminInitialized = true;
  }
} catch (e) {
  console.log('Firebase Admin notice:', e.message);
}

// CORS & Body Parser Middleware
app.use((req, res, next) => {
  res.header('Access-Control-Allow-Origin', '*');
  res.header('Access-Control-Allow-Methods', 'GET, POST, PUT, DELETE, OPTIONS');
  res.header('Access-Control-Allow-Headers', 'Origin, X-Requested-With, Content-Type, Accept, Authorization');
  if (req.method === 'OPTIONS') {
    return res.sendStatus(200);
  }
  next();
});

app.use(express.json());

// In-Memory Data Store (Provides instant response if Firestore is offline or unauthenticated)
const ridesStore = new Map();
const cartsStore = new Map([
  ['cart_1', {
    cartId: 'cart_1',
    cartName: 'Golf Cart 1',
    latitude: 25.2531616,
    longitude: 87.0370730,
    speedKmH: 15,
    bearing: 90,
    status: 'HALTED',
    batteryLevel: 92,
    driverStatus: 'Available',
    isAvailable: true,
    lastUpdatedMillis: Date.now(),
    distanceToGateMeters: 120,
    relativeMovement: 'Stationary near Gate',
    etaMinutes: 2
  }],
  ['cart_2', {
    cartId: 'cart_2',
    cartName: 'Golf Cart 2',
    latitude: 25.2510000,
    longitude: 87.0350000,
    speedKmH: 0,
    bearing: 0,
    status: 'HALTED',
    batteryLevel: 85,
    driverStatus: 'Available',
    isAvailable: true,
    lastUpdatedMillis: Date.now(),
    distanceToGateMeters: 350,
    relativeMovement: 'Halted near Guest House',
    etaMinutes: 5
  }]
]);

const usersStore = new Map([
  ['usr_default', {
    userId: 'usr_default',
    name: 'Campus User',
    email: 'user@iiitbh.ac.in',
    role: 'STUDENT',
    department: 'Computer Science & Engineering',
    phone: '+91 9876543210'
  }]
]);

const chatStore = new Map(); // rideId -> Array of messages
const fcmTokensStore = new Map(); // role/userId -> token

// -------------------------------------------------------------
// 1. Health & Service Metadata Endpoints
// -------------------------------------------------------------
app.get('/', (req, res) => {
  res.json({
    service: 'campus-ride-backend',
    status: 'online',
    version: '1.0.0',
    environment: process.env.NODE_ENV || 'production',
    projectId: process.env.FIREBASE_PROJECT_ID || 'campus-ride-2b21b',
    renderUrl: 'https://campus-ride-backend-df0n.onrender.com',
    firebaseAdminActive: isFirebaseAdminInitialized,
    timestamp: new Date().toISOString()
  });
});

app.get('/health', (req, res) => {
  res.json({
    status: 'healthy',
    service: 'campus-ride-backend',
    projectId: process.env.FIREBASE_PROJECT_ID || 'campus-ride-2b21b',
    uptime: Math.floor(process.uptime()),
    timestamp: new Date().toISOString()
  });
});

// -------------------------------------------------------------
// 2. Authentication & User Profile REST Endpoints
// -------------------------------------------------------------
app.post('/api/auth/login', (req, res) => {
  const { role, userId, email, accessCode } = req.body;

  if (!role) {
    return res.status(400).json({ success: false, error: 'Role is required (STUDENT, FACULTY, or DRIVER)' });
  }

  // Validate access code for Faculty/Driver if provided
  if (role === 'FACULTY' && accessCode && accessCode !== 'IIITFAC2026') {
    return res.status(401).json({ success: false, error: 'Invalid Faculty Access Code' });
  }
  if (role === 'DRIVER' && accessCode && accessCode !== 'IIITDRV2026') {
    return res.status(401).json({ success: false, error: 'Invalid Driver Access Code' });
  }

  const userKey = userId || email || `usr_${role.toLowerCase()}_1`;
  let userProfile = usersStore.get(userKey);

  if (!userProfile) {
    userProfile = {
      userId: userKey,
      name: role === 'FACULTY' ? 'Faculty Member' : role === 'DRIVER' ? 'Golf Cart Driver' : 'Campus Student',
      email: email || `${userKey}@iiitbh.ac.in`,
      role: role.toUpperCase(),
      department: 'IIIT Bhagalpur',
      phone: '+91 9876543210'
    };
    usersStore.set(userKey, userProfile);
  }

  res.json({
    success: true,
    token: `bearer_token_${userKey}_${Date.now()}`,
    user: userProfile
  });
});

app.post('/api/auth/register', (req, res) => {
  const { name, email, role, department, phone } = req.body;

  if (!name || !email || !role) {
    return res.status(400).json({ success: false, error: 'Name, email, and role are required' });
  }

  const userId = `usr_${Date.now()}`;
  const newUser = {
    userId,
    name,
    email,
    role: role.toUpperCase(),
    department: department || 'IIIT Bhagalpur',
    phone: phone || ''
  };

  usersStore.set(userId, newUser);

  res.status(201).json({
    success: true,
    message: 'User registered successfully',
    user: newUser
  });
});

app.get('/api/user/profile', (req, res) => {
  const userId = req.query.userId || 'usr_default';
  const profile = usersStore.get(userId) || usersStore.get('usr_default');
  res.json({ success: true, user: profile });
});

app.put('/api/user/profile', (req, res) => {
  const { userId, name, department, phone } = req.body;
  const key = userId || 'usr_default';
  const current = usersStore.get(key) || { userId: key, email: 'user@iiitbh.ac.in', role: 'STUDENT' };

  const updated = {
    ...current,
    name: name || current.name,
    department: department || current.department,
    phone: phone || current.phone
  };

  usersStore.set(key, updated);
  res.json({ success: true, user: updated });
});

// -------------------------------------------------------------
// 3. Rides Management REST Endpoints
// -------------------------------------------------------------
app.post('/api/rides/request', async (req, res) => {
  const { requesterType, studentName, pickupLocation, dropoffLocation, distanceToGateMeters, assignedCartId } = req.body;

  if (!requesterType || !pickupLocation) {
    return res.status(400).json({ success: false, error: 'requesterType and pickupLocation are required' });
  }

  const rideId = `ride_${Date.now()}`;
  const cartId = assignedCartId || 'cart_1';
  const cart = cartsStore.get(cartId) || cartsStore.get('cart_1');

  const newRide = {
    id: rideId,
    requesterType: requesterType.toUpperCase(),
    studentName: studentName || (requesterType === 'FACULTY' ? 'Faculty Member' : 'Student Passenger'),
    pickupLocation: pickupLocation,
    dropoffLocation: dropoffLocation || 'Academic Block',
    distanceToGateMeters: Number(distanceToGateMeters || 0),
    status: 'PENDING',
    assignedCartId: cart.cartId,
    assignedCartName: cart.cartName,
    timestamp: Date.now(),
    updatedAt: Date.now()
  };

  ridesStore.set(rideId, newRide);

  // High Priority NOTIFICATION + DATA FCM Dispatch for Driver Alert (System handles display when app is closed)
  const fcmPayload = {
    notification: {
      title: `🚨 URGENT ${newRide.requesterType || 'RIDE'} REQUEST`,
      body: `Pickup: ${newRide.pickupLocation || 'Main Gate'} • Tap to accept`
    },
    data: {
      type: 'RIDE_REQUEST',
      requestId: String(newRide.id),
      rideId: String(newRide.id),
      requesterType: String(newRide.requesterType),
      passengerName: String(newRide.studentName || 'Passenger'),
      studentName: String(newRide.studentName || 'Passenger'),
      pickupLocation: String(newRide.pickupLocation || 'Main Gate'),
      dropoffLocation: String(newRide.dropoffLocation || 'Campus'),
      distanceToGateMeters: String(newRide.distanceToGateMeters || 0),
      assignedCartId: String(newRide.assignedCartId || 'cart_1'),
      assignedCartName: String(newRide.assignedCartName || 'Golf Cart 1'),
      title: `🚨 URGENT ${newRide.requesterType || 'RIDE'} REQUEST`,
      body: `Pickup: ${newRide.pickupLocation || 'Main Gate'}`
    },
    android: {
      priority: 'high',
      ttl: 0,
      notification: {
        channelId: 'driver_critical_alerts',
        sound: 'default',
        visibility: 'public',
        notificationPriority: 'PRIORITY_MAX'
      }
    }
  };

  let driverToken = fcmTokensStore.get('DRIVER') || fcmTokensStore.get('cart_1');
  if (!driverToken && isFirebaseAdminInitialized) {
    try {
      const doc = await admin.firestore().collection('drivers').doc('cart_1').get();
      if (doc.exists && doc.data().fcmToken) {
        driverToken = doc.data().fcmToken;
        fcmTokensStore.set('DRIVER', driverToken);
        fcmTokensStore.set('cart_1', driverToken);
        console.log(`[FCM] Driver token restored from Firestore: ${driverToken}`);
      }
    } catch (e) {
      console.error(`[FCM] Firestore token restore error:`, e.message);
    }
  }

  if (driverToken) {
    fcmPayload.token = driverToken;
    console.log(`[FCM] Driver token found: ${driverToken}`);
  } else {
    fcmPayload.topic = 'drivers';
    console.log(`[FCM] Driver token found: NONE - Sending to topic 'drivers'`);
  }

  console.log('[FCM] Sending notification+data ride request payload:\n', JSON.stringify(fcmPayload, null, 2));

  // Sync to Firestore if admin SDK initialized & send high priority FCM push
  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(rideId).set(newRide);
      console.log('[FCM] Calling admin.messaging().send(fcmPayload)...');
      const response = await admin.messaging().send(fcmPayload);
      console.log('[FCM] Firebase Admin send success: MESSAGE_ID:', response);
    } catch (err) {
      console.error('[FCM] Firebase Admin send error code:', err.code || 'UNKNOWN', 'message:', err.message);
      if (err.code === 'messaging/registration-token-not-registered' || err.code === 'messaging/invalid-registration-token' || (err.message && err.message.includes('registration-token-not-registered'))) {
        console.warn('[FCM] Driver token is invalid/unregistered. Removing stale token...');
        fcmTokensStore.delete('DRIVER');
        fcmTokensStore.delete('cart_1');
        try {
          await admin.firestore().collection('drivers').doc('cart_1').update({ fcmToken: admin.firestore.FieldValue.delete() });
        } catch (e) {
          console.error('[FCM] Error clearing stale token in Firestore:', e.message);
        }
      }
    }
  }

  res.status(201).json({
    success: true,
    message: 'Ride request created successfully',
    ride: newRide
  });
});

app.get('/api/rides', (req, res) => {
  const { status, requesterType, limit } = req.query;
  let list = Array.from(ridesStore.values());

  if (status) {
    list = list.filter(r => r.status === status.toUpperCase());
  }
  if (requesterType) {
    list = list.filter(r => r.requesterType === requesterType.toUpperCase());
  }

  list.sort((a, b) => b.timestamp - a.timestamp);

  if (limit) {
    list = list.slice(0, parseInt(limit, 10));
  }

  res.json({
    success: true,
    count: list.length,
    rides: list
  });
});

app.get('/api/rides/my-rides', (req, res) => {
  const { role } = req.query;
  let list = Array.from(ridesStore.values());

  if (role) {
    list = list.filter(r => r.requesterType === role.toUpperCase() || role.toUpperCase() === 'DRIVER');
  }

  res.json({
    success: true,
    count: list.length,
    rides: list
  });
});

app.get('/api/rides/:id', (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }
  res.json({ success: true, ride });
});

app.post('/api/rides/:id/accept', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'ACCEPTED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  // Update cart availability
  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = false;
    cart.driverStatus = 'Occupied';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(ride.id).update({ status: 'ACCEPTED', updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    } catch (err) {
      console.log('Firestore accept notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride request accepted', ride });
});

app.post('/api/rides/:id/decline', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'REJECTED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = true;
    cart.driverStatus = 'Available';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(ride.id).update({ status: 'REJECTED', updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    } catch (err) {
      console.log('Firestore decline notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride request rejected', ride });
});

app.post('/api/rides/:id/complete', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'COMPLETED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = true;
    cart.driverStatus = 'Available';
    cartsStore.set(ride.assignedCartId, cart);
  }

  if (isFirebaseAdminInitialized) {
    try {
      await admin.firestore().collection('ride_requests').doc(ride.id).update({ status: 'COMPLETED', updatedAt: admin.firestore.FieldValue.serverTimestamp() });
    } catch (err) {
      console.log('Firestore complete notice:', err.message);
    }
  }

  res.json({ success: true, message: 'Ride marked as completed', ride });
});

app.post('/api/rides/:id/cancel', async (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.status = 'CANCELLED';
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  if (ride.assignedCartId && cartsStore.has(ride.assignedCartId)) {
    const cart = cartsStore.get(ride.assignedCartId);
    cart.isAvailable = true;
    cart.driverStatus = 'Available';
    cartsStore.set(ride.assignedCartId, cart);
  }

  res.json({ success: true, message: 'Ride request cancelled', ride });
});

app.post('/api/rides/:id/join', (req, res) => {
  const ride = ridesStore.get(req.params.id);
  if (!ride) {
    return res.status(404).json({ success: false, error: 'Ride request not found' });
  }

  ride.passengerCount = (ride.passengerCount || 1) + 1;
  ride.updatedAt = Date.now();
  ridesStore.set(ride.id, ride);

  res.json({ success: true, message: 'Joined ride successfully', ride });
});

// -------------------------------------------------------------
// 4. Golf Cart & Driver Telemetry REST Endpoints
// -------------------------------------------------------------
app.get('/api/carts', (req, res) => {
  res.json({
    success: true,
    count: cartsStore.size,
    carts: Array.from(cartsStore.values())
  });
});

app.post('/api/carts/location', (req, res) => {
  const { cartId, latitude, longitude, speedKmH, bearing } = req.body;
  const id = cartId || 'cart_1';

  const cart = cartsStore.get(id) || {
    cartId: id,
    cartName: `Golf Cart ${id}`,
    batteryLevel: 90,
    driverStatus: 'Available',
    isAvailable: true
  };

  cart.latitude = Number(latitude || 25.2531616);
  cart.longitude = Number(longitude || 87.0370730);
  cart.speedKmH = Number(speedKmH || 0);
  cart.bearing = Number(bearing || 0);
  cart.status = cart.speedKmH > 0 ? 'MOVING' : 'HALTED';
  cart.lastUpdatedMillis = Date.now();

  cartsStore.set(id, cart);

  res.json({ success: true, message: 'Cart telemetry updated successfully', cart });
});

app.post('/api/carts/duty-status', (req, res) => {
  const { cartId, driverStatus } = req.body;
  const id = cartId || 'cart_1';

  const cart = cartsStore.get(id);
  if (!cart) {
    return res.status(404).json({ success: false, error: 'Cart not found' });
  }

  cart.driverStatus = driverStatus || 'Available';
  cart.isAvailable = (driverStatus === 'Available');
  cart.status = driverStatus === 'Available' ? 'HALTED' : 'OFFLINE';
  cart.lastUpdatedMillis = Date.now();

  cartsStore.set(id, cart);

  res.json({ success: true, message: 'Driver duty status updated', cart });
});

// -------------------------------------------------------------
// 5. Notifications & FCM REST Endpoints
// -------------------------------------------------------------
app.post('/api/notifications/fcm-token', async (req, res) => {
  console.log('[FCM TOKEN] REQUEST RECEIVED');
  const { role, userId, fcmToken } = req.body;
  console.log('[FCM TOKEN] BODY RECEIVED:', JSON.stringify(req.body));

  if (!fcmToken) {
    console.log('[FCM TOKEN] ERROR: fcmToken missing');
    return res.status(400).json({ success: false, error: 'fcmToken is required' });
  }

  console.log('[FCM TOKEN] TOKEN PRESENT:', `${fcmToken.substring(0, 15)}...`);

  const key = userId || role || 'DRIVER';
  fcmTokensStore.set(key, fcmToken);
  if (role) fcmTokensStore.set(role.toUpperCase(), fcmToken);
  fcmTokensStore.set('DRIVER', fcmToken);
  fcmTokensStore.set('cart_1', fcmToken);

  if (isFirebaseAdminInitialized) {
    console.log('[FCM TOKEN] BEFORE FIRESTORE');
    (async () => {
      try {
        await Promise.race([
          Promise.all([
            admin.firestore().collection('drivers').doc('cart_1').set({
              fcmToken,
              cartId: 'cart_1',
              lastUpdatedMillis: Date.now()
            }, { merge: true }),
            admin.firestore().collection('fcm_tokens').doc('driver_cart_1').set({
              fcmToken,
              role: role || 'DRIVER',
              userId: userId || 'cart_1',
              updatedAt: Date.now()
            }, { merge: true })
          ]),
          new Promise((_, reject) => setTimeout(() => reject(new Error('Firestore operation timeout')), 3000))
        ]);
        console.log('[FCM TOKEN] AFTER FIRESTORE');
      } catch (e) {
        console.error('[FCM TOKEN] AFTER FIRESTORE (Error/Timeout):', e.message);
      }
    })();
  } else {
    console.log('[FCM TOKEN] BEFORE FIRESTORE (Skipped: Firebase Admin not active)');
    console.log('[FCM TOKEN] AFTER FIRESTORE (Skipped)');
  }

  console.log('[FCM TOKEN] RESPONSE SENT');
  res.json({
    success: true,
    message: 'FCM token registered successfully',
    key,
    tokenPreview: `${fcmToken.substring(0, 10)}...`
  });
});

app.post('/api/notifications/dispatch', async (req, res) => {
  const { targetTopic, targetToken, title, body, rideId, requesterType, pickupLocation, studentName } = req.body;

  const payload = {
    data: {
      type: 'RIDE_REQUEST',
      requestId: String(rideId || `ride_${Date.now()}`),
      rideId: String(rideId || `ride_${Date.now()}`),
      requesterType: String(requesterType || 'STUDENT'),
      studentName: String(studentName || 'Passenger'),
      pickupLocation: String(pickupLocation || 'IIIT Bhagalpur Main Gate'),
      title: String(title || `🚨 URGENT RIDE REQUEST`),
      body: String(body || 'Pickup Location: IIIT Bhagalpur Main Gate')
    },
    android: {
      priority: 'high',
      ttl: 0
    }
  };

  let tokenToUse = targetToken || fcmTokensStore.get('DRIVER') || fcmTokensStore.get('cart_1');

  if (!tokenToUse && isFirebaseAdminInitialized) {
    try {
      const doc = await admin.firestore().collection('drivers').doc('cart_1').get();
      if (doc.exists && doc.data().fcmToken) {
        tokenToUse = doc.data().fcmToken;
        fcmTokensStore.set('DRIVER', tokenToUse);
        fcmTokensStore.set('cart_1', tokenToUse);
        console.log(`[FCM Backend] Restored driver token from Firestore drivers/cart_1`);
      }
    } catch (e) {
      console.error(`[FCM Backend] Token restore from Firestore error:`, e.message);
    }
  }

  if (tokenToUse) {
    payload.token = tokenToUse;
    console.log(`[FCM Direct Dispatch] Targeting token: ${tokenToUse.substring(0, 15)}...`);
  } else {
    payload.topic = targetTopic || 'drivers';
    console.log(`[FCM Direct Dispatch] Targeting topic: ${targetTopic || 'drivers'}`);
  }

  if (isFirebaseAdminInitialized) {
    try {
      const response = await admin.messaging().send(payload);
      console.log('[FCM Direct Dispatch Response]:', response);
      return res.json({ success: true, messageId: response, targetedToken: tokenToUse ? `${tokenToUse.substring(0, 15)}...` : null });
    } catch (err) {
      console.error('[FCM Direct Dispatch Error]:', err.stack || err.message);
      return res.status(500).json({ success: false, error: err.message });
    }
  }

  res.json({
    success: true,
    message: 'Notification dispatch queued (Simulation Mode)',
    payload
  });
});

// -------------------------------------------------------------
// 6. Chat & Messaging REST Endpoints
// -------------------------------------------------------------
app.get('/api/chat/:rideId', (req, res) => {
  const rideId = req.params.rideId;
  const messages = chatStore.get(rideId) || [
    {
      id: 'msg_init',
      rideId,
      senderId: 'driver_1',
      senderRole: 'DRIVER',
      text: 'Hello! I am on my way to your pickup location.',
      timestamp: Date.now() - 30000
    }
  ];

  res.json({ success: true, count: messages.length, messages });
});

app.post('/api/chat/:rideId', (req, res) => {
  const rideId = req.params.rideId;
  const { senderId, senderRole, text } = req.body;

  if (!text) {
    return res.status(400).json({ success: false, error: 'Message text is required' });
  }

  const list = chatStore.get(rideId) || [];
  const newMsg = {
    id: `msg_${Date.now()}`,
    rideId,
    senderId: senderId || 'usr_passenger',
    senderRole: senderRole || 'STUDENT',
    text,
    timestamp: Date.now()
  };

  list.push(newMsg);
  chatStore.set(rideId, list);

  res.status(201).json({ success: true, message: newMsg });
});

// -------------------------------------------------------------
// 7. Operational Analytics Endpoint
// -------------------------------------------------------------
app.get('/api/analytics', (req, res) => {
  const allRides = Array.from(ridesStore.values());
  const accepted = allRides.filter(r => r.status === 'ACCEPTED' || r.status === 'COMPLETED').length;
  const pending = allRides.filter(r => r.status === 'PENDING').length;
  const rejected = allRides.filter(r => r.status === 'REJECTED').length;

  res.json({
    success: true,
    analytics: {
      totalRequests: allRides.length,
      acceptedRequests: accepted,
      pendingRequests: pending,
      rejectedRequests: rejected,
      activeFleetCount: Array.from(cartsStore.values()).filter(c => c.isAvailable).length,
      geofenceRadiusMeters: 70,
      gateCoordinates: '25.2531616, 87.0370730',
      serverUptimeSeconds: Math.floor(process.uptime())
    }
  });
});

app.listen(PORT, () => {
  console.log(`Campus Ride Express Server listening on port ${PORT}`);
});
