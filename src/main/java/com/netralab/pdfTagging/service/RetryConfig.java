package com.netralab.pdfTagging.service;


import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.annotation.EnableRetry;

@Slf4j
@Configuration
@EnableRetry
public class RetryConfig {

    @Bean
    public ChatGptClient chatGptClient(){
        log.info("Creating ChatGptClient bean");
        ChatGptClient client = new ChatGptClient();
        log.info("ChatGptClient bean created successfully");
        return client;
    }

}
