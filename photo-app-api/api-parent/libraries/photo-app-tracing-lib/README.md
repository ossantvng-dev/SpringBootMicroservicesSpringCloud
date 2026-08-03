# photo-app-tracing-lib

Replaces Boot 4's default Zipkin span sender. Exists to fix one specific, reproducible bug.

**Not a service — a library.** No port, no `main`, no container.

## The problem it solves

Spring Boot 4 ships `ZipkinHttpClientSender`, built on the JDK's `java.net.http.HttpClient`.
Inside these containers that sender fails with **`ClosedChannelException`** on its long-lived
client, and spans silently stop reaching Zipkin.

The diagnosis ruled out the usual suspects — IPv4/IPv6, DNS, address form, bean/property binding
timing — and a control test (a *fresh* `HttpClient` on a clean thread posting the same payload)
returned `202`. That isolated the fault to the long-lived client rather than to networking or
configuration.

## What it does

Contributes a `BytesMessageSender` backed by `zipkin2.reporter.urlconnection.URLConnectionSender`
— a plain `HttpURLConnection` transport with no persistent channel to lose:

```java
@AutoConfiguration(before = ZipkinAutoConfiguration.class)
@ConditionalOnClass({URLConnectionSender.class, BytesMessageSender.class})
public class ZipkinSenderAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(BytesMessageSender.class)
    public BytesMessageSender zipkinUrlConnectionSender(
            @Value("${management.zipkin.tracing.endpoint:http://localhost:9411/api/v2/spans}") String endpoint, ...) {
        return URLConnectionSender.newBuilder().endpoint(endpoint)...build();
    }
}
```

`before = ZipkinAutoConfiguration.class` plus `@ConditionalOnMissingBean` means it wins by
default but backs out cleanly if anything else supplies a sender.

## Registered via `AutoConfiguration.imports`, not component scan

`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — **not**
`@Component`. This is load-bearing: the config-server and discovery-service do not component-scan
`com.photoapp`, so a scanned bean would silently not apply to exactly the two components hardest
to notice it on.

## Consumed by

**All eight deployables.** The jar is byte-identical across every image — verified by content,
not by build timestamp (BuildKit's `CreatedAt` does not align after a cached rebuild).

## Follow-up

What actually triggers the interrupt that closes the channel is still open — the leading
hypothesis involves virtual threads. Logged in
[`../../../docs/plans/backlog.txt`](../../../docs/plans/backlog.txt). If a future Boot release
fixes the default sender, this library can be deleted: remove the dependency and the default
`@ConditionalOnMissingBean` path takes over with no other change.

## Symptom to recognise

If a Zipkin trace shows only *some* of the services that participated in a request, check that
the missing ones have this library on the classpath.

## See also

- [../../../docs/OBSERVABILITY.md](../../../docs/OBSERVABILITY.md) — using traces day to day
- [../../../docs/ARCHITECTURE.md](../../../docs/ARCHITECTURE.md) — the observability stack
