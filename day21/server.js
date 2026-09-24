// server.js (Node.js)
const express = require('express');
const app = express();
const PORT = 3000;

// 1. 模擬 CPU 密集運算（卡死主執行緒）
app.get('/heavy', (req, res) => {
    console.log("⚠️ 開始執行 CPU 密集運算...");
    let start = Date.now();

    // 跑一億次無意義的計算
    let count = 0;
    for (let i = 0; i < 2000000000; i++) {
        count++;
    }

    let duration = Date.now() - start;
    res.send(`✅ 計算完成！耗時 ${duration} ms`);
});

// 2. 正常的輕量非同步請求
app.get('/ping', (req, res) => {
    res.send("pong");
});

app.listen(PORT, () => {
    console.log(`🚀 Node.js 服務已啟動，監聽 Port ${PORT}`);
});