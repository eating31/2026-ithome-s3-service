package com.example.s3service.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.async.AsyncRequestBody;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.util.concurrent.CompletableFuture;

/**
 * S3 PUT 操作的獨立 Gateway Bean。
 *
 * 之所以把 @CircuitBreaker 抽到獨立的 class，是因為 Spring AOP 透過
 * proxy 攔截方法呼叫，只有從外部 Bean 呼叫才會被攔截到。
 * 如果把 @CircuitBreaker 和呼叫方放在同一個 class，
 * 內部的 this.method() 呼叫會直接繞過 proxy，導致斷路器完全失效。
 */
@Component
public class S3PutGateway {

    @Autowired
    private S3AsyncClient s3AsyncClient;

    /**
     * 實際發出 S3 putObject 的 method，由 Resilience4j @CircuitBreaker 包裝。
     *
     * 斷路器統計此 method 的失敗率：
     * - 記錄：SdkException（S3 網路/服務異常）與 RuntimeException（模擬後門）
     * - 忽略：IllegalArgumentException / NullPointerException（本地參數錯誤，非 S3 問題）
     *
     * 當失敗率超過 YAML 設定的閾值時，斷路器跳閘，
     * 後續呼叫直接觸發 s3PutObjectFallback。
     */
    @CircuitBreaker(name = "s3PutCircuitBreaker", fallbackMethod = "s3PutObjectFallback")
    public CompletableFuture<PutObjectResponse> putObject(
            String bucket, String key,
            PutObjectRequest putRequest, byte[] content,
            String simulateScenario) {

        if ("INBOUND_LOST".equalsIgnoreCase(simulateScenario)) {
            // 真的把檔案傳上 S3，再故意拋出例外，強迫觸發 Double-Check
            return s3AsyncClient.putObject(putRequest, AsyncRequestBody.fromBytes(content))
                    .thenCompose(resp -> CompletableFuture.failedFuture(
                            new RuntimeException("Simulated TimeoutException (Inbound Lost)")));
        } else if ("OUTBOUND_LOST".equalsIgnoreCase(simulateScenario)) {
            // 完全不連線 S3，直接拋出例外，模擬去程封包遺失
            return CompletableFuture.failedFuture(
                    new RuntimeException("Simulated Connection Timeout (Outbound Lost)"));
        } else {
            // 正常上傳
            return s3AsyncClient.putObject(putRequest, AsyncRequestBody.fromBytes(content));
        }
    }

    /**
     * 斷路器跳閘時的 Fallback method。
     * 參數簽名必須與 putObject 完全一致，末尾多加一個 Throwable。
     * 回傳 failedFuture，讓 S3Service 的 exceptionallyCompose 接手判斷。
     */
    public CompletableFuture<PutObjectResponse> s3PutObjectFallback(
            String bucket, String key,
            PutObjectRequest putRequest, byte[] content,
            String simulateScenario,
            Throwable throwable) {
        // CallNotPermittedException 代表斷路器真的跳閘了
        // 其他 exception 代表這次呼叫失敗，斷路器只是把它記錄進失敗率統計
        boolean isCircuitOpen = throwable instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException;
        if (isCircuitOpen) {
            System.err.println("[" + Thread.currentThread().getName()
                    + "] ⚡ [斷路器 OPEN] 拒絕請求: " + throwable.getMessage());
        } else {
            System.err.println("[" + Thread.currentThread().getName()
                    + "] 📊 [斷路器統計失敗] 原因: " + throwable.getMessage());
        }
        return CompletableFuture.failedFuture(throwable);
    }
}
