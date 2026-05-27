import ws from 'k6/ws';
import { check, sleep } from 'k6';
import { Trend, Counter, Rate } from 'k6/metrics';

// =========================================================================
// 1. Define Custom Metrics for Observability
// =========================================================================
const sessionDurationTrend = new Trend('ws_session_duration_ms');
const timeToFirstChunkTrend = new Trend('ws_time_to_first_chunk_ms');
const messagesReceivedCounter = new Counter('ws_messages_received_total');
const rateLimitRejectionRate = new Rate('ws_rate_limit_rejections');

export const options = {
    stages: [
        { duration: '10s', target: 50 },  // Ramp up
        { duration: '30s', target: 50 },  // Sustained load
        { duration: '10s', target: 0 },   // Ramp down
    ],
    // Optional: Auto-fail the CI pipeline if average response time exceeds 2 seconds
    thresholds: {
        'ws_session_duration_ms': ['avg<2000'], 
    }
};

export default function () {
    const url = 'ws://localhost:8080/ws/analyze';
    const params = {
        headers: { 'X-Sentinel-Tenant-ID': 'LOAD_TESTER_CORP' },
    };

    const res = ws.connect(url, params, function (socket) {
        let startTime;
        let firstChunkTime;

        socket.on('open', function open() {
            startTime = Date.now();
            const payload = JSON.stringify({
                content: "I am testing my persistent Redis memory.",
                focusArea: "General",
                userId: __VU 
            });
            
            socket.send(payload);
        });

        socket.on('message', function (msg) {
            messagesReceivedCounter.add(1);
            
            // Track Time-To-First-Byte (TTFB) for the AI stream
            if (!firstChunkTime) {
                firstChunkTime = Date.now();
                timeToFirstChunkTrend.add(firstChunkTime - startTime);
            }

            // Scenario A: Backend accepts connection but sends a rate limit error message
            if (msg.includes('RATE_LIMIT') || msg.includes('429')) {
                rateLimitRejectionRate.add(1);
                socket.close();
            }

            // When the AI finishes streaming, record the total session duration
            if (msg.includes('DONE') || msg.includes('complete')) {
                sessionDurationTrend.add(Date.now() - startTime);
                socket.close();
            }
        });

        socket.on('error', function (e) {
            if (e.error() != 'websocket: close sent') {
                console.error(`[VU ${__VU}] WS Error: `, e.error());
            }
        });

        socket.setTimeout(function () {
            socket.close();
        }, 15000);
    });

    // =========================================================================
    // 2. Assertions & Handshake Rate Limit Tracking
    // =========================================================================
    
    // Scenario B: Backend rejects the initial HTTP upgrade request entirely
    if (res && res.status === 429) {
        rateLimitRejectionRate.add(1);
    }

    // Verify system behavior
    check(res, { 
        'Handshake successful (101)': (r) => r && r.status === 101,
        'Handshake rate-limited (429)': (r) => r && r.status === 429,
    });

    sleep(1);
}