const WebSocket = require('ws');
const http = require('http');
const fs = require('fs');
const path = require('path');

const wss = new WebSocket.Server({ port: process.env.PORT || 8080 });
const androidClients = new Set();
const browserClients = new Set();

const server = http.createServer((req, res) => {
    let filePath = req.url === '/' ? '/index.html' : req.url;
    filePath = path.join(__dirname, 'public', filePath);
    fs.readFile(filePath, (err, data) => {
        if (err) { res.writeHead(404); res.end(); return; }
        const ext = path.extname(filePath);
        const types = { '.html': 'text/html', '.js': 'application/javascript', '.css': 'text/css' };
        res.writeHead(200, { 'Content-Type': types[ext] || 'text/plain' });
        res.end(data);
    });
});
server.listen(process.env.PORT || 8081);

wss.on('connection', (ws, req) => {
    const isAndroid = req.headers['user-agent']?.includes('Android') || false;
    if (isAndroid) {
        androidClients.add(ws);
        console.log('[Android] Connected');
        ws.on('message', (data) => {
            if (Buffer.isBuffer(data)) {
                for (const client of browserClients) {
                    if (client.readyState === WebSocket.OPEN) client.send(data);
                }
            } else {
                if (data.toString() === 'status') {
                    ws.send(JSON.stringify({ battery: 85, online: true, camera: 'back' }));
                }
            }
        });
        ws.on('close', () => androidClients.delete(ws));
    } else {
        browserClients.add(ws);
        console.log('[Browser] Connected');
        ws.on('message', (msg) => {
            for (const client of androidClients) {
                if (client.readyState === WebSocket.OPEN) client.send(msg.toString());
            }
        });
        ws.on('close', () => browserClients.delete(ws));
    }
});

console.log('Server running');