# s3-async-service

一個示範 AWS S3 操作的 Spring Boot 微服務，重點在於比較**同步（Apache HttpClient）**與**非同步（Netty）**兩種 S3 存取模式，並整合 Resilience4j 斷路器、Semaphore 限流、Double-Check 自癒等容錯機制。

---

## 核心 API

所有路徑皆以 `/api/s3` 為前綴。

### 重點端點（非同步 + 斷路器保護）

| Method | Path | 說明 |
|--------|------|------|
| `GET` | `/async/files/protected` | 列出 Bucket 內所有物件 Key，帶斷路器保護。S3 持續異常時自動降級回傳 503。 |
| `POST` | `/async/upload/protected` | 上傳檔案，帶 Semaphore 限流、冪等 uploadId、Double-Check 自癒與斷路器保護。 |

#### `POST /async/upload/protected` 請求格式

```
Header:  uploadId: <UUID>          # 必填，每次上傳用新的 UUID；重試時使用同一個 UUID
Param:   file=<multipart file>     # 必填
Param:   simulateScenario=NORMAL   # 可選，預設 NORMAL。測試用：INBOUND_LOST / OUTBOUND_LOST
```

**回傳狀態碼：**
- `200` — 上傳成功
- `400` — uploadId 不是合法 UUID
- `429` — 同時上傳數超過上限（Semaphore 滿）
- `500` — 上傳失敗且 HEAD Double-Check 確認 404
- `503` — 斷路器跳閘，S3 暫時不可用

---

### 其他端點

| Method | Path | 說明 |
|--------|------|------|
| `GET` | `/check` | 健康確認，回傳 `ok` |
| `POST` | `/async/upload` | 非同步上傳（無保護） |
| `GET` | `/async/files` | 列出所有 Key（無斷路器） |
| `GET` | `/async/download/{key}` | 非同步下載，支援中文檔名 |
| `DELETE` | `/async/delete/{key}` | 非同步刪除 |
| `POST` | `/sync/upload` | 同步上傳 |
| `GET` | `/sync/files` | 同步列出 |
| `GET` | `/sync/download/{key}` | 同步下載 |
| `DELETE` | `/sync/delete/{key}` | 同步刪除 |

---

## 環境變數

| 變數名稱 | 必填 | 預設值 | 說明 |
|----------|------|--------|------|
| `AWS_ACCESS_KEY_ID` | ✅ | — | AWS IAM Access Key |
| `AWS_SECRET_ACCESS_KEY` | ✅ | — | AWS IAM Secret Key |
| `AWS_REGION` | | `ap-southeast-2` | S3 Bucket 所在 Region |
| `S3_BUCKET` | | `iron-netty` | 目標 Bucket 名稱 |
| `SERVER_PORT` | | `8080` | 服務監聽 Port |
| `SEMAPHORE_PERMITS` | | `50` | 全域 Semaphore 上限 |
| `S3_UPLOAD_MAX_CONCURRENCY` | | `50` | 上傳端點並發上限 |
| `S3_LIST_MAX_CONCURRENCY` | | `1000` | List 操作並發上限 |

> **重要：** `AWS_ACCESS_KEY_ID` 與 `AWS_SECRET_ACCESS_KEY` 絕對不可硬編碼或 commit 進版本控制。

---

## 部署方式

### 1. 本地 Docker Compose（含 Prometheus + Grafana）

```bash
# 方式 A：使用 .env 檔（推薦，已加入 .gitignore）
cp .env.example .env
# 編輯 .env，填入真實的 AK/SK 與 Bucket 名稱

# 方式 B：直接 export
export AWS_ACCESS_KEY_ID=your_ak
export AWS_SECRET_ACCESS_KEY=your_sk

# 啟動所有服務
docker compose up -d
```

服務啟動後：
- 應用程式：http://localhost:8080
- Prometheus：http://localhost:9090
- Grafana：http://localhost:3000（帳號 admin / 密碼 admin）

---

### 2. Kubernetes（Minikube）

#### 步驟一：建立 AWS 憑證 Secret

```bash
# AK/SK 以命令式建立，不留任何金鑰檔案在 Git 倉庫中
kubectl create secret generic aws-credentials \
  --from-literal=aws-access-key-id=YOUR_AK \
  --from-literal=aws-secret-access-key=YOUR_SK
```

#### 步驟二：建置並載入 Docker Image 到 Minikube

```bash
# 切換 Docker context 到 Minikube 的 daemon
eval $(minikube docker-env)

# 建置 image
docker build -t my-app:latest .
```

#### 步驟三：套用 k8s 資源

```bash
# ConfigMap（通用設定：Region、Bucket、Semaphore 上限）
kubectl apply -f k8s/s3-app-configmap.yaml

# PVC（Log 持久化磁碟，5Gi）
kubectl apply -f k8s/s3-pvc.yaml

# Deployment、Service、HPA
kubectl apply -f k8s/s3-app-deployment.yaml
kubectl apply -f k8s/s3-app-service.yaml
kubectl apply -f k8s/s3-app-hpa.yaml
```

#### 步驟四：確認部署狀態

```bash
# 確認 Pod 都是 Running
kubectl get pods

# 確認 Service
kubectl get svc s3-app-service

# 查看 Pod 日誌
kubectl logs -l app=my-app --tail=50
```

#### 步驟五：存取服務

```bash
# Minikube 取得 Service URL
minikube service s3-app-service --url

# 或用 port-forward 在本機測試
kubectl port-forward svc/s3-app-service 8080:80
# 然後存取 http://localhost:8080/api/s3/check
```

#### 清除資源

```bash
kubectl delete -f k8s/
kubectl delete secret aws-credentials
```

---

## Actuator 端點

| 路徑 | 說明 |
|------|------|
| `/actuator/health` | 整體健康狀態 |
| `/actuator/health/liveness` | Liveness Probe（K8s 用） |
| `/actuator/health/readiness` | Readiness Probe（K8s 用） |
| `/actuator/prometheus` | Prometheus metrics scrape 端點 |
