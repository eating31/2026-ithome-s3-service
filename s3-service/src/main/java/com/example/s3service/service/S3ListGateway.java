package com.example.s3service.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;

import java.util.concurrent.CompletableFuture;

/**
 * S3 ListObjectsV2 操作的獨立 Gateway Bean。
 *
 * 與 S3PutGateway 同理，@CircuitBreaker 必須放在獨立的 Bean，
 * Spring AOP proxy 才能正確攔截，從 S3Service 的 this.method() 呼叫
 * 無法被 proxy 攔截，斷路器會完全失效。
 */
@Component
public class S3ListGateway {

    @Autowired
    private S3AsyncClient s3AsyncClient;

    /**
     * 實際發出 S3 listObjectsV2 的 method，由 Resilience4j @CircuitBreaker 包裝。
     *
     * 斷路器統計此 method 的失敗率：
     * - 記錄：SdkException（S3 網路/服務異常）與 RuntimeException
     * - 忽略：IllegalArgumentException / NullPointerException（本地參數錯誤）
     *
     * 當失敗率超過 YAML 設定的閾值時，斷路器跳閘，後續呼叫直接觸發 fallback。
     */
    @CircuitBreaker(name = "s3ListCircuitBreaker", fallbackMethod = "s3ListObjectsFallback")
    public CompletableFuture<ListObjectsV2Response> listObjects(String bucket) {
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(bucket).build();
        return s3AsyncClient.listObjectsV2(request);
    }

    /**
     * 斷路器跳閘時的 Fallback method。
     * 參數簽名必須與 listObjects 完全一致，末尾多加一個 Throwable。
     * 回傳 failedFuture，讓 S3Service 的 exceptionallyCompose 接手判斷。
     */
    public CompletableFuture<ListObjectsV2Response> s3ListObjectsFallback(
            String bucket,
            Throwable throwable) {
        boolean isCircuitOpen = throwable instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException;
        if (isCircuitOpen) {
            System.err.println("[" + Thread.currentThread().getName()
                    + "] ⚡ [斷路器 OPEN] List 請求被拒絕: " + throwable.getMessage());
        } else {
            System.err.println("[" + Thread.currentThread().getName()
                    + "] 📊 [斷路器統計失敗] List 失敗原因: " + throwable.getMessage());
        }
        return CompletableFuture.failedFuture(throwable);
    }
}
