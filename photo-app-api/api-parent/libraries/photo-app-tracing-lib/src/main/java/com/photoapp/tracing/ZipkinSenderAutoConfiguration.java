package com.photoapp.tracing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.zipkin.autoconfigure.ZipkinAutoConfiguration;
import org.springframework.context.annotation.Bean;
import zipkin2.reporter.BytesMessageSender;
import zipkin2.reporter.urlconnection.URLConnectionSender;

/*
    Sends spans over HttpURLConnection instead of Boot's default
    ZipkinHttpClientSender, which uses the JDK java.net.http.HttpClient.

    WHY
    In containers that client fails permanently with

        java.net.ConnectException
          caused by java.nio.channels.ClosedChannelException
            at sun.nio.ch.SocketChannelImpl.ensureOpen

    The socket channel is already closed before connect is attempted, so no
    network I/O ever happens. The address is irrelevant: the same failure occurs
    against the hostname, against the raw IP, and against a deliberately dead
    port. Meanwhile curl from the same container succeeds, and a freshly built
    java.net.http.HttpClient on a clean thread in the same container returns 202
    for the same request - so the network, the collector and the JDK client are
    all fine. Only the long-lived client inside the application is broken.

    ClosedChannelException on a fresh channel is the signature of a thread
    carrying an interrupt flag, since NIO auto-closes interruptible channels. The
    underlying trigger is still unidentified and is tracked in backlog.txt; this
    swaps the transport so observability works while that is investigated.

    HOW
    Boot declares its sender under @ConditionalOnMissingBean(BytesMessageSender),
    so publishing one here makes it back off. Registered as an auto-configuration
    ordered before ZipkinAutoConfiguration rather than as a @Component, because
    the Config Server and the Discovery Service do not component-scan any
    com.photoapp package.
 */
@AutoConfiguration(before = ZipkinAutoConfiguration.class)
@ConditionalOnClass({URLConnectionSender.class, BytesMessageSender.class})
public class ZipkinSenderAutoConfiguration {

    /*
        Read straight from the Environment rather than from ZipkinProperties or
        ZipkinConnectionDetails: both are contributed by the auto-configuration
        this class is ordered before, and the endpoint is a plain string anyway.
     */
    @Bean
    @ConditionalOnMissingBean(BytesMessageSender.class)
    public BytesMessageSender zipkinUrlConnectionSender(
            @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}") String endpoint,
            @Value("${management.zipkin.tracing.connect-timeout:PT1S}") java.time.Duration connectTimeout,
            @Value("${management.zipkin.tracing.read-timeout:PT10S}") java.time.Duration readTimeout) {

        return URLConnectionSender.newBuilder()
                .endpoint(endpoint)
                .connectTimeout((int) connectTimeout.toMillis())
                .readTimeout((int) readTimeout.toMillis())
                .build();
    }

}
