package com.example.s3service.controller;

import com.example.s3service.service.S3Service;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;

@RestController
@RequestMapping("/api/s3")
public class S3Controller {

    private final S3Service s3Service;
    private final Semaphore uploadSemaphore;

    private final String bucketName;

    public S3Controller(
            S3Service s3Service,
            @org.springframework.beans.factory.annotation.Value("${s3.bucket:ithome-iron}") String bucketName,
            @org.springframework.beans.factory.annotation.Value("${s3.upload.max-concurrency:50}") int maxUploadConcurrency) {
        this.s3Service = s3Service;
        this.bucketName = bucketName;
        this.uploadSemaphore = new Semaphore(maxUploadConcurrency);
    }

    @GetMapping("/check")
    public String check() {
        return "ok";
    }

    // ==========================================
    // 1. 同步 API 路由 (Sync Endpoints)
    // ==========================================

    @PostMapping("/sync/upload")
    public ResponseEntity<String> uploadSync(@RequestParam("file") MultipartFile file) throws IOException {
        s3Service.uploadSync(bucketName, file.getOriginalFilename(), file.getBytes());
        return ResponseEntity.ok("[Sync] Upload success: " + file.getOriginalFilename());
    }

    @GetMapping("/sync/files")
    public ResponseEntity<List<String>> listFilesSync() {
        return ResponseEntity.ok(s3Service.listFilesSync(bucketName));
    }

    @GetMapping("/sync/download/{key}")
    public ResponseEntity<byte[]> downloadSync(@PathVariable String key) {
        byte[] data = s3Service.downloadFileSync(bucketName, key);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"" + key + "\"")
                .body(data);
    }

    @DeleteMapping("/sync/delete/{key}")
    public ResponseEntity<String> deleteSync(@PathVariable String key) {
        s3Service.deleteFileSync(bucketName, key);
        return ResponseEntity.ok("[Sync] Deleted: " + key);
    }

    // ==========================================
    // 2. 非同步 API 路由 (Async Endpoints - Tomcat 執行緒不卡死！)
    // ==========================================

    @PostMapping("/async/upload")
    public CompletableFuture<ResponseEntity<String>> uploadAsync(@RequestParam("file") MultipartFile file) throws IOException {
        return s3Service.uploadAsync(bucketName, file.getOriginalFilename(), file.getBytes())
                .thenApply(v -> ResponseEntity.ok("[Async] Upload success: " + file.getOriginalFilename()));
    }

    /**
     * 2026/09/07 Day11具備限流、uploadId 冪等 key 與 Double-Check 的非同步上傳 API。
     *
     * uploadId 必須是 UUID，並直接作為 S3 Object Key。重試時使用同一個
     * uploadId，便不會因檔名不同而產生另一個 S3 物件。
     *
     * tryAcquire() 會在併發上傳達到上限時立即回傳 429，不阻塞 Tomcat request
     * thread。permit 只有在 PUT 或 PUT 失敗後的 HEAD 確認完整結束時才釋放。
     */
    @PostMapping("/async/upload/protected")
    public CompletableFuture<ResponseEntity<String>> uploadAsyncProtected(
            @RequestHeader("uploadId") String uploadId,
            @RequestParam(value = "simulateScenario", defaultValue = "NORMAL") String simulateScenario,
            @RequestParam("file") MultipartFile file) throws IOException {

        final String normalizedUploadId;
        try {
            normalizedUploadId = UUID.fromString(uploadId).toString();
        } catch (IllegalArgumentException ex) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.badRequest().body("uploadId must be a valid UUID"));
        }

        if (!uploadSemaphore.tryAcquire()) {
            return CompletableFuture.completedFuture(
                    ResponseEntity.status(429).body("Too many concurrent uploads"));
        }

        CompletableFuture<S3Service.ProtectedUploadResult> uploadFuture;
        try {
            uploadFuture = s3Service.uploadAsyncProtected(
                    bucketName,
                    normalizedUploadId,
                    file.getBytes(),
                    simulateScenario,
                    file.getOriginalFilename());
        } catch (RuntimeException ex) {
            uploadSemaphore.release();
            return CompletableFuture.completedFuture(
                    ResponseEntity.internalServerError().body("Upload failed"));
        }

        return uploadFuture.handle((result, error) -> {
            try {
                if (error != null) {
                    return ResponseEntity.internalServerError().body(
                            "Upload failed; retry with the same uploadId");
                }
                // 斷路器跳閘降級：回傳 503，告知客戶端保存 uploadId 稍後重試
                if (result.fallbackMessage() != null && !result.fallbackMessage().isBlank()) {
                    return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                            .body(result.fallbackMessage());
                }
                if (!result.success()) {
                    return ResponseEntity.internalServerError().body(
                            "Upload failed; retry with the same uploadId");
                }
                String message = result.confirmedByHead()
                        ? "Upload success (confirmed by S3 HEAD): " + normalizedUploadId
                        : "Upload success: " + normalizedUploadId;
                return ResponseEntity.ok(message);
            } finally {
                uploadSemaphore.release();
            }
        });
    }

    @GetMapping("/async/files")
    public CompletableFuture<ResponseEntity<List<String>>> listFilesAsync() {
        return s3Service.listFilesAsync(bucketName)
                .thenApply(ResponseEntity::ok);
    }

    /**
     * 2026/09/15 Day19 具備 Resilience4j 斷路器的非同步列舉 API。
     *
     * List 是無副作用的讀取操作，不需要 Semaphore 限流。
     * 依賴斷路器在 S3 持續失敗時快速降級，回傳 503；
     * 一般列舉失敗回傳 500。
     */
    @GetMapping("/async/files/protected")
    public CompletableFuture<ResponseEntity<?>> listFilesAsyncProtected() {
        return s3Service.listFilesAsyncProtected(bucketName)
                .thenApply(result -> {
                    if (result.fallbackMessage() != null && !result.fallbackMessage().isBlank()) {
                        return ResponseEntity.status(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE)
                                .<Object>body(result.fallbackMessage());
                    }
                    if (!result.success()) {
                        return ResponseEntity.internalServerError()
                                .<Object>body("List failed; please retry later");
                    }
                    return ResponseEntity.ok().<Object>body(result.keys());
                });
    }

    @GetMapping("/async/download/{key}")
    public CompletableFuture<ResponseEntity<byte[]>> downloadAsync(@PathVariable String key) {
        return s3Service.downloadFileAsync(bucketName, key)
                .thenApply(result -> {
                    // 非 ASCII 檔名（中文、空格等）需要依 RFC 5987 編碼才能安全放進 Header
                    String encodedFilename = java.net.URLEncoder.encode(result.filename(), java.nio.charset.StandardCharsets.UTF_8)
                            .replace("+", "%20");
                    return ResponseEntity.ok()
                            .header("Content-Disposition",
                                    "attachment; filename=\"" + result.filename() + "\"; filename*=UTF-8''" + encodedFilename)
                            .body(result.content());
                });
    }

    @DeleteMapping("/async/delete/{key}")
    public CompletableFuture<ResponseEntity<String>> deleteAsync(@PathVariable String key) {
        return s3Service.deleteFileAsync(bucketName, key)
                .thenApply(v -> ResponseEntity.ok("[Async] Deleted: " + key));
    }
}