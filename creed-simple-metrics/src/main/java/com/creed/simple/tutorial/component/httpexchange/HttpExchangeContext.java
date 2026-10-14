package com.creed.simple.tutorial.component.httpexchange;

import org.apache.camel.Exchange;

import java.util.concurrent.Callable;

/**
 * Exposes the Camel {@link Exchange} being processed to code that runs inside the
 * {@code @HttpExchange} proxy call, such as the {@code ArtisanHeaders} interceptor adding headers.
 *
 * <p>Spring's HTTP interface machinery knows nothing about Camel: it only sees the method and its
 * arguments. Passing the Exchange through a method parameter would put it back on every
 * interface method, which is exactly what centralised header handling is meant to avoid.
 * A thread-local is safe here because the proxy call is synchronous: {@link HttpExchangeProducer}
 * sets it, invokes the proxy on the same thread (RestClient is blocking), and clears it afterwards.
 */
public final class HttpExchangeContext {

    private static final ThreadLocal<Exchange> CURRENT = new ThreadLocal<>();

    private HttpExchangeContext() {
    }

    /** The Exchange whose proxy call is in progress on this thread, or null outside one. */
    public static Exchange current() {
        return CURRENT.get();
    }

    static <T> T callWith(Exchange exchange, Callable<T> call) throws Exception {
        Exchange previous = CURRENT.get();
        CURRENT.set(exchange);
        try {
            return call.call();
        } finally {
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}
