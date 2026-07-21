package com.creed.simple.web.logbook;

import org.junit.jupiter.api.Test;
import org.zalando.logbook.HttpRequest;
import org.zalando.logbook.HttpResponse;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ContentAwareBodyStrategy#process}: the response body is buffered
 * ({@code withBody}) only when the content-type is allowed and — when {@code bodyOnError} is on — the
 * status is at least {@code minimumStatus}; otherwise it is skipped ({@code withoutBody}).
 */
class ContentAwareBodyStrategyTest {

    private final HttpRequest request = mock(HttpRequest.class);
    private final HttpResponse response = mock(HttpResponse.class);
    private final HttpResponse buffered = mock(HttpResponse.class);
    private final HttpResponse skipped = mock(HttpResponse.class);

    private void wire(int status, String contentType) throws IOException {
        lenient().when(response.getStatus()).thenReturn(status);
        lenient().when(response.getContentType()).thenReturn(contentType);
        lenient().when(response.withBody()).thenReturn(buffered);
        lenient().when(response.withoutBody()).thenReturn(skipped);
    }

    private HttpResponse process(ContentAwareBodyStrategy strategy) {
        try {
            return strategy.process(request, response);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    void buffersAllowedContentType() throws IOException {
        wire(200, "application/json");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), false, 500);
        assertThat(process(strategy)).isSameAs(buffered);
    }

    @Test
    void skipsDisallowedContentType() throws IOException {
        wire(200, "text/html");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), false, 500);
        assertThat(process(strategy)).isSameAs(skipped);
    }

    @Test
    void emptyAllowListBuffersAnyContentType() throws IOException {
        wire(200, "application/octet-stream");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of(), false, 500);
        assertThat(process(strategy)).isSameAs(buffered);
    }

    @Test
    void nullOrBlankContentTypeIsBufferedNothingToSuppress() throws IOException {
        wire(200, null);
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), false, 500);
        assertThat(process(strategy)).isSameAs(buffered);
    }

    @Test
    void bodyOnErrorSkipsBelowMinimumStatusEvenForAllowedType() throws IOException {
        wire(200, "application/json");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), true, 500);
        assertThat(process(strategy)).isSameAs(skipped);
    }

    @Test
    void bodyOnErrorBuffersAtOrAboveMinimumStatus() throws IOException {
        wire(500, "application/json");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), true, 500);
        assertThat(process(strategy)).isSameAs(buffered);
    }

    @Test
    void contentTypeMatchIsCaseInsensitiveAndPrefixBased() throws IOException {
        wire(200, "Application/JSON; charset=UTF-8");
        ContentAwareBodyStrategy strategy = new ContentAwareBodyStrategy(List.of("application/json"), false, 500);
        assertThat(process(strategy)).isSameAs(buffered);
    }
}
