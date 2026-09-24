package com.example.s3service.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
public class S3Service {

    @Autowired
    private S3Client s3Client;

    @Autowired
    private S3AsyncClient s3AsyncClient;

    @Autowired
    private S3PutGateway s3PutGateway;

    @Autowired
    private S3ListGateway s3ListGateway;

    /**
     * 受保護上傳的結果。
     *
     * success=true 代表 PUT 成功，或 PUT 發生例外後由 HEAD 確認物件存在。
     * confirmedByHead=true 代表這次成功是透過 Double-Check 得到的結論。
     * fallbackMessage 不為 null 代表斷路器已跳閘，直接降級，未發出任何 S3 請求。
     */
    public record ProtectedUploadResult(
            String uploadId,
            boolean success,
            boolean confirmedByHead,
            String fallbackMessage) {

        /** 正常成功（直接 PUT 成功） */
        public static ProtectedUploadResult success(String uploadId) {
            return new ProtectedUploadResult(uploadId, true, false, null);
        }

        /** 自癒成功（HEAD 確認物件存在） */
        public static ProtectedUploadResult recoveredByHead(String uploadId) {
            return new ProtectedUploadResult(uploadId, true, true, null);
        }

        /** 確認失敗（HEAD 回報 404） */
        public static ProtectedUploadResult failed(String uploadId) {
            return new ProtectedUploadResult(uploadId, false, false, null);
        }

        /** 斷路器跳閘降級 */
        public static ProtectedUploadResult circuitBreakerOpen(String uploadId, String message) {
            return new ProtectedUploadResult(uploadId, false, false, message);
        }
    }

    // ==========================================
    // 流派 A：Apache 同步阻塞模式 (Sync)
    // ==========================================

    public void uploadSync(String bucket, String key, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key).build();
        s3Client.putObject(request, RequestBody.fromBytes(content));
    }

    public List<String> listFilesSync(String bucket) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
        ListObjectsV2Response response = s3Client.listObjectsV2(request);
        return response.contents().stream().map(S3Object::key).collect(Collectors.toList());
    }

    public void deleteFileSync(String bucket, String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
        s3Client.deleteObject(request);
    }

    public byte[] downloadFileSync(String bucket, String key) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return s3Client.getObjectAsBytes(request).asByteArray();
    }

    // ==========================================
    // 流派 B：Netty 非同步非阻塞模式 (Async)
    // ==========================================

    public CompletableFuture<Void> uploadAsync(String bucket, String key, byte[] content) {
        PutObjectRequest request = PutObjectRequest.builder().bucket(bucket).key(key).build();
        return s3AsyncClient.putObject(request, AsyncRequestBody.fromBytes(content))
                .thenApply(response -> null);
    }

    // 2026/09/07 Day11 / 2026/09/10 Day14
    /**
     * 非同步保護型上傳（帶有 Double-Check 自癒、故障模擬後門與 Resilience4j 斷路器）。
     *
     * PUT 發生 timeout 或其他網路例外時，不能直接判定上傳失敗，因為 S3
     * 可能已經完成寫入，只是成功回應在網路途中遺失。因此失敗分支會再
     * 呼叫 HEAD：查到物件代表成功；只有確認 HEAD 為 404 時才是確定失敗。
     *
     * 當 S3 連續失敗超過閾值時，Resilience4j 斷路器跳閘（OPEN），
     * 後續請求直接被 s3PutObjectFallback 攔截，回傳 503 降級訊息，
     * 不再對 S3 發射任何實體請求，保護 JVM 執行緒池免於雪崩。
     *
     * simulateScenario 僅用於本地整合測試：
     * - INBOUND_LOST：真的把檔案傳上 S3，再故意拋出例外，模擬回程封包遺失。
     * - OUTBOUND_LOST：完全不呼叫 S3，直接拋出例外，模擬去程封包遺失。
     */
    public CompletableFuture<ProtectedUploadResult> uploadAsyncProtected(
            String bucket, String key, byte[] content, String simulateScenario, String originalFilename) {

        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .metadata(Map.of("original-filename",
                        originalFilename != null && !originalFilename.isBlank() ? originalFilename : key))
                .build();

        String startThread = Thread.currentThread().getName();
        System.out.println("[" + startThread + "] 🚀 [PUT 啟動] 開始安全非同步上傳，Key: " + key
                + " (模擬情境: " + simulateScenario + ")");

        // 透過獨立 Bean（S3PutGateway）呼叫，AOP proxy 才能正確攔截 @CircuitBreaker
        // 斷路器 OPEN 時，直接觸發 S3PutGateway 的 fallback，不發出任何網路請求
        return s3PutGateway.putObject(bucket, key, putRequest, content, simulateScenario)
                .thenApply(response -> {
                    System.out.println("[" + Thread.currentThread().getName() + "] ✅ [PUT 成功] 直接返回 success=true");
                    return ProtectedUploadResult.success(key);
                })
                .exceptionallyCompose(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

                    // 斷路器跳閘：CallNotPermittedException，直接降級，不做 HEAD
                    if (cause instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
                        System.err.println("[" + Thread.currentThread().getName()
                                + "] ⚡ [斷路器跳閘] " + cause.getMessage());
                        String msg = "⚡ [系統自動降級] AWS S3 服務暫時不可用，斷路器已啟動保護。請保存 uploadId 並稍後再試。";
                        return CompletableFuture.completedFuture(ProtectedUploadResult.circuitBreakerOpen(key, msg));
                    }

                    // 一般網路異常：進入 Double-Check 自癒流程
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] ⚠️ [PUT 異常] 觸發自癒，啟動 HEAD Double-Check...");

                    HeadObjectRequest headRequest = HeadObjectRequest.builder()
                            .bucket(bucket)
                            .key(key)
                            .build();

                    return s3AsyncClient.headObject(headRequest)
                            .thenApply(headResponse -> {
                                System.out.println("[" + Thread.currentThread().getName()
                                        + "] 🎉 [自癒成功] HEAD 二次確認：檔案已安然躺在 S3！");
                                return ProtectedUploadResult.recoveredByHead(key);
                            })
                            .exceptionally(headEx -> {
                                System.err.println("[" + Thread.currentThread().getName()
                                        + "] ❌ [自癒失敗] HEAD 回報物件不存在！檔案確實遺失。");
                                return ProtectedUploadResult.failed(key);
                            });
                });
    }

    public CompletableFuture<List<String>> listFilesAsync(String bucket) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
        return s3AsyncClient.listObjectsV2(request)
                .thenApply(response -> response.contents().stream()
                        .map(S3Object::key)
                        .collect(Collectors.toList()));
    }

    /**
     * 受保護 List 的結果。
     *
     * success=true 代表 List 成功。
     * fallbackMessage 不為 null 代表斷路器已跳閘，直接降級，未發出任何 S3 請求。
     */
    public record ProtectedListResult(
            List<String> keys,
            boolean success,
            String fallbackMessage) {

        /** 正常成功 */
        public static ProtectedListResult success(List<String> keys) {
            return new ProtectedListResult(keys, true, null);
        }

        /** 一般失敗（S3 回傳錯誤） */
        public static ProtectedListResult failed() {
            return new ProtectedListResult(null, false, null);
        }

        /** 斷路器跳閘降級 */
        public static ProtectedListResult circuitBreakerOpen(String message) {
            return new ProtectedListResult(null, false, message);
        }
    }

    // 2026/09/15 Day19
    /**
     * 非同步保護型列舉（帶有 Resilience4j 斷路器）。
     *
     * 當 S3 連續失敗超過閾值時，Resilience4j 斷路器跳閘（OPEN），
     * 後續請求直接被 s3ListObjectsFallback 攔截，回傳 503 降級訊息，
     * 不再對 S3 發射任何實體請求。
     *
     * List 是讀取操作，不需要 Double-Check 自癒（不像 PUT 存在「成功但
     * 回應遺失」的問題），也不需要 Semaphore 限流（無狀態且無副作用）。
     */
    public CompletableFuture<ProtectedListResult> listFilesAsyncProtected(String bucket) {
        System.out.println("[" + Thread.currentThread().getName()
                + "] 🚀 [LIST 啟動] 開始安全非同步列舉，Bucket: " + bucket);

        return s3ListGateway.listObjects(bucket)
                .thenApply(response -> {
                    List<String> keys = response.contents().stream()
                            .map(S3Object::key)
                            .collect(Collectors.toList());
                    System.out.println("[" + Thread.currentThread().getName()
                            + "] ✅ [LIST 成功] 共 " + keys.size() + " 個物件");
                    return ProtectedListResult.success(keys);
                })
                .exceptionally(throwable -> {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;

                    if (cause instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException) {
                        System.err.println("[" + Thread.currentThread().getName()
                                + "] ⚡ [斷路器跳閘] " + cause.getMessage());
                        String msg = "⚡ [系統自動降級] AWS S3 服務暫時不可用，斷路器已啟動保護。請稍後再試。";
                        return ProtectedListResult.circuitBreakerOpen(msg);
                    }

                    System.err.println("[" + Thread.currentThread().getName()
                            + "] ❌ [LIST 失敗] " + cause.getMessage());
                    return ProtectedListResult.failed();
                });
    }

    public CompletableFuture<Void> deleteFileAsync(String bucket, String key) {
        DeleteObjectRequest request = DeleteObjectRequest.builder().bucket(bucket).key(key).build();
        return s3AsyncClient.deleteObject(request).thenApply(response -> null);
    }

    /**
     * 下載結果，附帶從 S3 User Metadata 還原出來的原始檔名。
     * 沒有 metadata（例如舊資料）時，退回使用 S3 Key 當檔名。
     */
    public record DownloadResult(byte[] content, String filename) {
    }

    public CompletableFuture<DownloadResult> downloadFileAsync(String bucket, String key) {
        GetObjectRequest request = GetObjectRequest.builder().bucket(bucket).key(key).build();
        return s3AsyncClient.getObject(request, AsyncResponseTransformer.toBytes())
                .thenApply(responseBytes -> {
                    String originalFilename = responseBytes.response().metadata()
                            .getOrDefault("original-filename", key);
                    return new DownloadResult(responseBytes.asByteArray(), originalFilename);
                });
    }
}
