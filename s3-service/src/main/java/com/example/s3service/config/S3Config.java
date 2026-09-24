package com.example.s3service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.http.apache.ProxyConfiguration;
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.time.Duration;

@Configuration
public class S3Config {

        private final Region region;

        public S3Config(@org.springframework.beans.factory.annotation.Value("${s3.region:ap-southeast-2}") String region) {
                this.region = Region.of(region);
        }

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(ApacheHttpClient.builder()
                        .maxConnections(100) // 同步連線池上限
                        .connectionTimeout(Duration.ofSeconds(5))
                        .socketTimeout(Duration.ofSeconds(30))
                        .connectionAcquisitionTimeout(Duration.ofSeconds(10))
                )
                .overrideConfiguration(configuration -> configuration
                        .apiCallAttemptTimeout(Duration.ofSeconds(45))
                        .apiCallTimeout(Duration.ofSeconds(60)))
                .build();
    }

    @Bean
    public S3AsyncClient s3AsyncClient() {
        return S3AsyncClient.builder()
                .region(region)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .httpClientBuilder(NettyNioAsyncHttpClient.builder()
                        .maxConcurrency(100) // Netty 併發連線數上限
                        .connectionTimeout(Duration.ofMillis(15000))
                        .readTimeout(Duration.ofMillis(15000))
                        .writeTimeout(Duration.ofMillis(15000))
                )
                .overrideConfiguration(configuration -> configuration
                        .apiCallAttemptTimeout(Duration.ofMillis(14000)) // 略低於 Netty timeout，讓 SDK 先觸發
                        .apiCallTimeout(Duration.ofMillis(15000)))       // 給 HEAD Double-Check 一點空間
                .build();
    }
}