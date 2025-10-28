# Admin Dashboard Guide

## 🎯 Overview

The SMPP-MLS Admin Dashboard provides real-time monitoring and control of your SMPP gateway with interactive charts, session management, and performance metrics.

---

## 🚀 Quick Start

### Access the Dashboard

1. **Start your application:**
   ```bash
   ./gradlew bootRun
   ```

2. **Open the dashboard:**
   ```
   http://localhost:8080/dashboard.html
   ```

The dashboard auto-refreshes every 5 seconds to show real-time data.

---

## 📊 Dashboard Features

### 1. Overview Statistics (Top Cards)

Four key metrics displayed prominently:

- **Total Messages** - Total messages processed
  - Shows today's count
  
- **Success Rate** - Percentage of successfully delivered messages
  - Shows last hour's message count
  
- **Active Sessions** - Number of connected SMPP sessions
  - Shows X/Y format (active/total)
  - ✓ All connected or ⚠ Some disconnected
  
- **Queue Depth** - Messages waiting to be sent
  - ⚠ Warning if > 100 messages queued
  - ✓ Normal otherwise

### 2. Interactive Charts

#### Throughput Chart (Line Chart)
- **Shows:** Messages per minute for last 10 minutes
- **Use:** Monitor traffic patterns and spikes
- **Color:** Blue line with gradient fill

#### Messages by Status (Doughnut Chart)
- **Shows:** Distribution of message statuses
  - SENT (Green)
  - QUEUED (Orange)
  - FAILED (Red)
  - DELIVERED (Blue)
- **Use:** Quick status overview

#### Performance Metrics (Bar Chart)
- **Shows:** Four key performance indicators
  - Submission Delay (ms) - Time to submit to SMPP
  - Delivery Time (seconds) - Total delivery time
  - Success Rate (%) - Delivery success percentage
  - Retry Rate (%) - Messages requiring retry
- **Use:** Monitor system performance

#### Messages by Operator (Bar Chart)
- **Shows:** Message distribution across operators
  - AFTEL, Roshan, AWCC, MTN, Salaam
- **Use:** Load balancing verification

### 3. SMPP Sessions Panel

Each session shows:

**Session Information:**
- Session ID (e.g., `roshan:roshan_user1`)
- Status badge: CONNECTED (green) or DISCONNECTED (red)
- Metrics: Sent, Queued, Failed counts

**Session Controls:**
- **Stop Button** (Red) - Stop active session
- **Start Button** (Green) - Start disconnected session

**Note:** Session control is currently a placeholder and requires SmppSessionManager enhancement.

### 4. Alerts Panel

Real-time alerts for:

- **SESSION_DISCONNECTED** (Warning)
  - Shows when sessions are down
  - Count of disconnected sessions
  
- **HIGH_QUEUE_DEPTH** (Warning)
  - Triggers when queue > 100 messages
  - Shows exact queue count
  
- **HIGH_RETRY_RATE** (Error)
  - Triggers when retry rate > 10%
  - Shows current retry percentage

### 5. Recent Activity Table

Shows last 50 messages with:
- Message ID
- Phone number
- Priority (HIGH/NORMAL)
- Status (color-coded badge)
- Operator
- Session ID
- Created timestamp

**Status Colors:**
- SENT - Green
- QUEUED - Orange
- FAILED - Red
- DELIVERED - Blue

---

## 🔌 API Endpoints

The dashboard uses these REST endpoints:

### Main Dashboard Data
```
GET /api/admin/dashboard
```
Returns complete dashboard data (overview, sessions, throughput, performance, activity, alerts)

### Individual Endpoints

```
GET /api/admin/overview
```
Overview statistics

```
GET /api/admin/sessions
```
Detailed session status

```
GET /api/admin/throughput
```
Throughput metrics (TPS, messages per minute)

```
GET /api/admin/performance
```
Performance metrics (delays, success rates)

```
GET /api/admin/activity
```
Recent message activity

```
GET /api/admin/alerts
```
System alerts

### Session Control

```
POST /api/admin/session/{sessionId}/stop
```
Stop a specific session

```
POST /api/admin/session/{sessionId}/start
```
Start a specific session

---

## 📈 Key Metrics Explained

### Throughput Metrics

- **Current TPS** - Messages per second (last minute / 60)
- **Peak TPS** - Highest TPS in last hour
- **Messages/Minute** - Real-time traffic chart

### Performance Metrics

- **Avg Submission Delay** - Time from receiving to submitting (ms)
  - Target: < 100ms
  - Good: < 500ms
  - Poor: > 1000ms

- **Avg Delivery Time** - Total end-to-end delivery (ms)
  - Target: < 3000ms (3 seconds)
  - Good: < 5000ms (5 seconds)
  - Poor: > 10000ms (10 seconds)

- **Delivery Success Rate** - % of messages delivered
  - Excellent: > 95%
  - Good: > 90%
  - Poor: < 90%

- **Retry Rate** - % of messages requiring retry
  - Excellent: < 5%
  - Good: < 10%
  - Poor: > 10%

---

## 🎨 Visual Indicators

### Color Coding

- **Green** - Success, healthy, connected
- **Blue** - Information, neutral status
- **Orange** - Warning, attention needed
- **Red** - Error, critical issue
- **Purple** - Header gradient

### Status Badges

- **CONNECTED** - Session is active and bound
- **DISCONNECTED** - Session is down
- **SENT** - Message submitted to operator
- **QUEUED** - Message waiting to be sent
- **FAILED** - Message failed permanently
- **DELIVERED** - Message delivered successfully

---

## 🔄 Auto-Refresh

The dashboard automatically refreshes every **5 seconds**.

**Refresh Indicator** (top-right):
- Shows last update time
- Turns green when refreshing
- Shows "Refreshing..." during update

To change refresh interval, edit this line in `dashboard.html`:
```javascript
setInterval(updateDashboard, 5000); // 5000ms = 5 seconds
```

---

## 🛠️ Customization

### Change Chart Colors

Edit the chart initialization in `dashboard.html`:

```javascript
backgroundColor: ['#10b981', '#f59e0b', '#ef4444', '#3b82f6']
```

### Add More Metrics

1. Add endpoint in `AdminDashboardController.java`
2. Fetch data in `updateDashboard()` function
3. Create chart or display element
4. Update in refresh cycle

### Adjust Alert Thresholds

Edit `getAlerts()` method in `AdminDashboardController.java`:

```java
if (queueDepth != null && queueDepth > 100) { // Change 100 to your threshold
```

---

## 📱 Responsive Design

The dashboard is fully responsive:
- **Desktop:** 4-column grid for stats, 2-column for charts
- **Tablet:** 2-column grid adapts automatically
- **Mobile:** Single column layout

---

## 🔍 Monitoring Best Practices

### What to Watch

1. **Success Rate** - Should stay > 95%
   - If dropping, check operator connections
   
2. **Queue Depth** - Should stay < 100
   - If growing, check session throughput
   
3. **Active Sessions** - All should be connected
   - If disconnected, check network/credentials
   
4. **Retry Rate** - Should stay < 5%
   - If high, investigate operator issues

### Alert Response

**SESSION_DISCONNECTED:**
1. Check simulator/operator is running
2. Check network connectivity
3. Verify credentials
4. Check application logs

**HIGH_QUEUE_DEPTH:**
1. Check if sessions are connected
2. Verify TPS limits
3. Check for operator throttling
4. Consider adding more sessions

**HIGH_RETRY_RATE:**
1. Check operator stability
2. Review error logs
3. Verify message format
4. Check network quality

---

## 🎯 Performance Optimization

### For High Traffic (> 500 TPS)

1. **Increase Sessions**
   - Add more sessions per operator
   - Distribute load evenly

2. **Optimize Database**
   - Switch from H2 to PostgreSQL
   - Add indexes on frequently queried columns

3. **Tune Refresh Rate**
   - Reduce auto-refresh to 10-15 seconds
   - Use WebSocket for real-time updates

4. **Cache Metrics**
   - Implement Redis for metric caching
   - Reduce database queries

---

## 🐛 Troubleshooting

### Dashboard Not Loading

1. Check application is running: `http://localhost:8080`
2. Check browser console for errors (F12)
3. Verify static resources are enabled in Spring Boot

### Charts Not Updating

1. Check API endpoints are responding: `/api/admin/dashboard`
2. Check browser console for JavaScript errors
3. Verify CORS settings if accessing from different domain

### Session Controls Not Working

Session start/stop is currently a placeholder. To implement:

1. Add session control methods to `SmppSessionManager`
2. Update `AdminDashboardController` to call these methods
3. Implement proper session lifecycle management

---

## 📊 Sample Dashboard Data

### Healthy System
- Success Rate: 98-100%
- Active Sessions: All connected
- Queue Depth: < 50
- Retry Rate: < 3%
- Avg Submission Delay: < 200ms
- Avg Delivery Time: < 4000ms

### Warning Signs
- Success Rate: 90-95%
- Some sessions disconnected
- Queue Depth: 100-500
- Retry Rate: 5-10%
- Avg Submission Delay: 500-1000ms

### Critical Issues
- Success Rate: < 90%
- Multiple sessions down
- Queue Depth: > 500
- Retry Rate: > 10%
- Avg Submission Delay: > 1000ms

---

## 🔐 Security Considerations

### Production Deployment

1. **Add Authentication**
   - Implement login page
   - Use Spring Security
   - Require admin role

2. **Enable HTTPS**
   - Use SSL certificates
   - Redirect HTTP to HTTPS

3. **API Security**
   - Add CSRF protection
   - Implement rate limiting
   - Use API keys for endpoints

4. **Access Control**
   - Restrict dashboard to admin IPs
   - Implement audit logging
   - Monitor access patterns

---

## 📚 Related Documentation

- **Tracking API:** `TRACKING_API.md`
- **Implementation Status:** `IMPLEMENTATION_STATUS.md`
- **Testing Guide:** `TESTING_GUIDE.md`
- **Session Summary:** `SESSION_SUMMARY.md`

---

## 🎓 Next Steps

1. **Test the Dashboard**
   ```bash
   # Start app
   ./gradlew bootRun
   
   # Open dashboard
   http://localhost:8080/dashboard.html
   
   # Submit test messages
   python test_routing_priority.py
   
   # Watch metrics update in real-time
   ```

2. **Customize for Your Needs**
   - Adjust thresholds
   - Add custom metrics
   - Modify chart types

3. **Implement Session Control**
   - Add start/stop methods to SmppSessionManager
   - Wire up dashboard buttons
   - Test session lifecycle

4. **Add Authentication**
   - Implement Spring Security
   - Create login page
   - Protect admin endpoints

---

*Dashboard created: October 29, 2025*
