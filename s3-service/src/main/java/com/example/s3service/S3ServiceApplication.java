package com.example.s3service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.web.bind.annotation.GetMapping; 
import org.springframework.web.bind.annotation.RestController; 
import java.util.concurrent.CompletableFuture;

@SpringBootApplication
@RestController
public class S3ServiceApplication {

    // 1. 模擬 CPU 密集運算（丟到獨立的執行緒池）
    @GetMapping("/heavy")
    public CompletableFuture<String> heavy() {
        return CompletableFuture.supplyAsync(() -> {
            System.out.println("⚠️ [Thread: " + Thread.currentThread().getName() + "] 開始執行 CPU 密集運算...");
            long start = System.currentTimeMillis();

            long count = 0;
            for (long i = 0; i < 2000000000L; i++) {
                count++;
            }

            long duration = System.currentTimeMillis() - start;
            return "✅ 計算完成！耗時 " + duration + " ms";
        });
    }

    // 2. 正常的輕量請求
    @GetMapping("/ping")
    public String ping() {
        System.out.println("ℹ️ [Thread: " + Thread.currentThread().getName() + "] 處理 ping 請求");
        return "pong";
    }

    public static void main(String[] args) {
        SpringApplication.run(S3ServiceApplication.class, args);
    }
}
