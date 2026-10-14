package com.creed.simple.tutorial.upload;

import jakarta.activation.DataHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.Part;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.attachment.AttachmentMessage;
import org.apache.camel.http.common.HttpBinding;
import org.apache.camel.http.common.HttpCommonEndpoint;
import org.apache.camel.http.common.HttpMessage;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockMultipartHttpServletRequest;
import org.springframework.mock.web.MockPart;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * API-8's two steps in front of {@link FileUploadProcessor}: the servlet filter decodes the Base64
 * text field into a binary file part, and {@link MultipartAttachmentProcessor} turns that part into
 * the Camel attachment the processor reads (the Camel servlet's REST binding does not).
 */
class Base64UploadTest {

    private static final byte[] CONTENT = "hello camel upload\n".getBytes(StandardCharsets.UTF_8);

    private static MockMultipartHttpServletRequest upload(String fileField) {
        MockMultipartHttpServletRequest request = new MockMultipartHttpServletRequest();
        request.setMethod("POST");
        request.setRequestURI("/simple/camel/api/v1/files/upload");
        request.setContentType("multipart/form-data; boundary=x");
        // A text part (no file name): what curl -F file=<base64> sends
        request.addPart(new MockPart("file", null, fileField.getBytes(StandardCharsets.UTF_8)));
        request.addParameter("file", fileField);
        request.addParameter("fileName", "hello.txt");
        request.addParameter("orderId", "H-8001");
        return request;
    }

    private static HttpServletRequest filter(MockMultipartHttpServletRequest request, MockHttpServletResponse response)
            throws Exception {
        MockFilterChain chain = new MockFilterChain();
        new Base64FileMultipartFilter().doFilter(request, response, chain);
        return (HttpServletRequest) chain.getRequest();
    }

    @Test
    void theFilterTurnsTheBase64FieldIntoABinaryFilePart() throws Exception {
        HttpServletRequest decoded = filter(upload(Base64.getEncoder().encodeToString(CONTENT)),
                new MockHttpServletResponse());

        Part file = decoded.getPart("file");
        assertThat(file.getSubmittedFileName()).isEqualTo("hello.txt");
        assertThat(file.getInputStream().readAllBytes()).isEqualTo(CONTENT);
        // the Base64 text is gone from the parameters, so it never becomes an Exchange header
        assertThat(decoded.getParameter("file")).isNull();
        assertThat(decoded.getParameter("orderId")).isEqualTo("H-8001");
    }

    @Test
    void aDataUrlCarriesItsOwnMediaType() throws Exception {
        String dataUrl = "data:text/plain;base64," + Base64.getEncoder().encodeToString(CONTENT);
        Part file = filter(upload(dataUrl), new MockHttpServletResponse()).getPart("file");
        assertThat(file.getContentType()).isEqualTo("text/plain");
    }

    @Test
    void invalidBase64IsA400BeforeCamelSeesTheRequest() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();
        new Base64FileMultipartFilter().doFilter(upload("@@@ not base64 @@@"), response, chain);

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void theDecodedPartBecomesTheAttachmentTheUploadProcessorReads() throws Exception {
        HttpServletRequest decoded = filter(upload(Base64.getEncoder().encodeToString(CONTENT)),
                new MockHttpServletResponse());
        CamelContext camel = new DefaultCamelContext();
        Exchange exchange = new DefaultExchange(camel);
        // What the Camel servlet hands the route: an HttpMessage over the (filter-wrapped) request.
        // Its constructor runs the endpoint's binding; a do-nothing one keeps this test about the parts.
        HttpCommonEndpoint endpoint = mock(HttpCommonEndpoint.class);
        when(endpoint.getHttpBinding()).thenReturn(mock(HttpBinding.class));
        exchange.setIn(new HttpMessage(exchange, endpoint, decoded, new MockHttpServletResponse()));
        exchange.getIn().setHeader("orderId", "H-8001");

        new MultipartAttachmentProcessor().process(exchange);
        DataHandler attachment = exchange.getIn(AttachmentMessage.class).getAttachment("file");
        assertThat(attachment.getName()).isEqualTo("hello.txt");

        new FileUploadProcessor().process(exchange);
        Map<String, Object> reply = exchange.getIn().getBody(Map.class);
        assertThat(reply).containsEntry("code", "OK").containsEntry("fileName", "hello.txt")
                .containsEntry("size", CONTENT.length).containsEntry("orderId", "H-8001");
    }
}
