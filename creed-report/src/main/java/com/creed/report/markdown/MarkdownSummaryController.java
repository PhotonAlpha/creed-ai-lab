package com.creed.report.markdown;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/markdown/summary   body: the Markdown document → {heading, items: {Verdict: …, Risk: …}}
 * POST /api/markdown/verdict   body: the Markdown document → {raw, label, indicator}
 * </pre>
 * 400 when there is no summary table / no Verdict row.
 */
@RestController
public class MarkdownSummaryController {

    private final ReviewSummaryParser parser;

    public MarkdownSummaryController(ReviewSummaryParser parser) {
        this.parser = parser;
    }

    @PostMapping(path = "/api/markdown/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    public ReviewSummary summary(@RequestBody byte[] body,
                                 @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return parser.parse(decode(body, contentType));
    }

    @PostMapping(path = "/api/markdown/verdict", produces = MediaType.APPLICATION_JSON_VALUE)
    public Verdict verdict(@RequestBody byte[] body,
                           @RequestHeader(name = HttpHeaders.CONTENT_TYPE, required = false) String contentType) {
        return parser.verdict(decode(body, contentType));
    }

    /**
     * Bytes, decoded here: a {@code String} body without a charset is decoded by the converter's
     * default — ISO-8859-1 in plain Spring (standalone MockMvc), UTF-8 only where Boot configured the
     * converter — and the verdict's emoji would arrive as mojibake. UTF-8 unless the caller names
     * another charset.
     */
    static String decode(byte[] body, String contentType) {
        Charset charset = StandardCharsets.UTF_8;
        if (contentType != null) {
            try {
                Charset named = MediaType.parseMediaType(contentType).getCharset();
                if (named != null) charset = named;
            } catch (RuntimeException ignored) {
                // an unparsable Content-Type keeps UTF-8
            }
        }
        return new String(body, charset);
    }
}
