const ws = new WebSocket('wss://' + location.hostname);
const video = document.getElementById('video');
let sourceBuffer;
const mimeCodec = 'video/mp4; codecs="avc1.42E01E"';
if ('MediaSource' in window && MediaSource.isTypeSupported(mimeCodec)) {
    const mediaSource = new MediaSource();
    video.src = URL.createObjectURL(mediaSource);
    mediaSource.addEventListener('sourceopen', () => {
        sourceBuffer = mediaSource.addSourceBuffer(mimeCodec);
    });
}
ws.binaryType = 'arraybuffer';
ws.onmessage = (event) => {
    if (event.data instanceof ArrayBuffer) {
        if (sourceBuffer && !sourceBuffer.updating) {
            try { sourceBuffer.appendBuffer(new Uint8Array(event.data)); } catch(e) {}
        }
        document.getElementById('fps').textContent = '30';
    } else {
        try {
            const data = JSON.parse(event.data);
            if (data.battery) document.getElementById('battery').textContent = data.battery + '%';
            if (data.online !== undefined) {
                const dot = document.getElementById('statusDot');
                const text = document.getElementById('statusText');
                if (data.online) { dot.className = 'dot online'; text.textContent = 'Online'; document.getElementById('deviceStatus').textContent = 'Streaming'; }
                else { dot.className = 'dot offline'; text.textContent = 'Offline'; document.getElementById('deviceStatus').textContent = 'Disconnected'; }
            }
            if (data.camera) document.getElementById('cameraMode').textContent = data.camera;
        } catch(e) {}
    }
};
ws.onopen = () => {
    document.getElementById('statusDot').className = 'dot online';
    document.getElementById('statusText').textContent = 'Online';
    setInterval(() => { if (ws.readyState === WebSocket.OPEN) ws.send('status'); }, 5000);
};
ws.onclose = () => {
    document.getElementById('statusDot').className = 'dot offline';
    document.getElementById('statusText').textContent = 'Offline';
};

document.getElementById('btnSwitch').addEventListener('click', () => { if (ws.readyState === WebSocket.OPEN) ws.send('switch'); });
document.getElementById('btnSnapshot').addEventListener('click', () => {
    const canvas = document.createElement('canvas');
    canvas.width = video.videoWidth || 640;
    canvas.height = video.videoHeight || 480;
    canvas.getContext('2d').drawImage(video, 0, 0);
    const link = document.createElement('a');
    link.href = canvas.toDataURL('image/jpeg', 0.9);
    link.download = 'snapshot_' + Date.now() + '.jpg';
    link.click();
});
let isRecording = false;
let mediaRecorderChunks = [];
document.getElementById('btnRecord').addEventListener('click', function() {
    if (isRecording) {
        isRecording = false;
        this.textContent = '⏺ Record';
        const blob = new Blob(mediaRecorderChunks, { type: 'video/mp4' });
        const link = document.createElement('a');
        link.href = URL.createObjectURL(blob);
        link.download = 'recording_' + Date.now() + '.mp4';
        link.click();
        mediaRecorderChunks = [];
    } else {
        isRecording = true;
        this.textContent = '⏹ Stop';
        mediaRecorderChunks = [];
        const stream = video.captureStream ? video.captureStream(30) : null;
        if (stream) {
            const recorder = new MediaRecorder(stream, { mimeType: 'video/mp4' });
            recorder.ondataavailable = e => mediaRecorderChunks.push(e.data);
            recorder.start(1000);
            setTimeout(() => { if (isRecording) recorder.stop(); }, 1000);
        }
    }
});
document.getElementById('btnFullscreen').addEventListener('click', () => { if (video.requestFullscreen) video.requestFullscreen(); });
document.addEventListener('keydown', (e) => {
    if (e.key === 'f') document.getElementById('btnFullscreen').click();
    if (e.key === 's') document.getElementById('btnSnapshot').click();
    if (e.key === 'r') document.getElementById('btnRecord').click();
});