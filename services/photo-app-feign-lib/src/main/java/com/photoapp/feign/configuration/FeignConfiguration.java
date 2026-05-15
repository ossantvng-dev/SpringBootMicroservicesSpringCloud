package com.photoapp.feign.configuration;

import com.photoapp.feign.decoder.CustomFeignErrorDecoder;
import com.photoapp.feign.interceptor.FeignAuthInterceptor;
import feign.codec.ErrorDecoder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FeignConfiguration {

    @Bean
    public ErrorDecoder errorDecoder() { return new CustomFeignErrorDecoder(); }

    @Bean
    public FeignAuthInterceptor feignAuthInterceptor() { return new FeignAuthInterceptor(); }

}
