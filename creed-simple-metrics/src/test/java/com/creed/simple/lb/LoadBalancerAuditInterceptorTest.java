package com.creed.simple.lb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link LoadBalancerAuditInterceptor}: it forwards the exchange to the execution and
 * returns the response on success, and rethrows (without swallowing) on failure. Because it is purely
 * a logging pass-through, the assertions focus on the transparency of both paths.
 */
@ExtendWith(MockitoExtension.class)
class LoadBalancerAuditInterceptorTest {

    private final LoadBalancerAuditInterceptor interceptor = new LoadBalancerAuditInterceptor();

    @Mock
    private HttpRequest request;
    @Mock
    private ClientHttpRequestExecution execution;

    private void stubRequest() {
        when(request.getURI()).thenReturn(URI.create("https://localhost:8081/api/catalog/items"));
        lenient().when(request.getMethod()).thenReturn(HttpMethod.GET);
    }

    @Test
    void returnsTheResponseUnchangedOnSuccess() throws IOException {
        stubRequest();
        ClientHttpResponse response = mock(ClientHttpResponse.class);
        when(response.getStatusCode()).thenReturn(HttpStatus.OK);
        byte[] body = "body".getBytes();
        when(execution.execute(request, body)).thenReturn(response);

        ClientHttpResponse result = interceptor.intercept(request, body, execution);

        assertThat(result).isSameAs(response);
        verify(execution).execute(request, body);
    }

    @Test
    void rethrowsIoExceptionFromTheExecution() throws IOException {
        stubRequest();
        IOException boom = new IOException("connection reset");
        when(execution.execute(any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isSameAs(boom);
    }

    @Test
    void rethrowsRuntimeExceptionFromTheExecution() throws IOException {
        stubRequest();
        RuntimeException boom = new IllegalStateException("pool exhausted");
        when(execution.execute(any(), any())).thenThrow(boom);

        assertThatThrownBy(() -> interceptor.intercept(request, new byte[0], execution))
                .isSameAs(boom);
    }
}
