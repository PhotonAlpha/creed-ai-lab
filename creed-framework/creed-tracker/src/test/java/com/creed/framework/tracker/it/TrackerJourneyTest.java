package com.creed.framework.tracker.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The whole journey on a real Tomcat: the cookie, the inbound / outbound Logbook lines under one sid, and what is left
 * of a request whose response headers are over Tomcat's 8KB.
 */
@SpringBootTest(classes = TrackerTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "creed.tracker.start-paths=/api/connection",
                "logbook.filter.enabled=true",
                "logbook.format.style=splunk",
                "logging.level.creed.tracker=INFO",
        })
@ExtendWith(OutputCaptureExtension.class)
class TrackerJourneyTest {

    @LocalServerPort
    int port;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> send(String method, String path, String sid) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody());
        if (sid != null) {
            builder.header("Cookie", "sid=" + sid);
        }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String connect() throws Exception {
        HttpResponse<String> response = send("POST", "/api/connection", null);
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertThat(cookie).contains("SameSite=Lax", "HttpOnly", "Path=/");
        return cookie.substring("sid=".length(), cookie.indexOf(';'));
    }

    @Test
    void everyRequestOfTheJourneyIsLoggedUnderItsSidAndDirection(CapturedOutput output) throws Exception {
        String sid = connect();

        HttpResponse<String> journey = send("GET", "/api/journey", sid);

        assertThat(journey.body()).isEqualTo("journey:echo");
        String inboundJourney = "sid=" + sid + " direction=inbound peer=";
        String outboundEcho = "direction=outbound peer=localhost ";
        assertThat(output.getOut())
                .contains("sid=" + sid + " journey started at POST /api/connection")
                // the inbound request and response of /api/journey (Logbook flips origin= on a response: direction= does not) ...
                .containsPattern(inboundJourney + "\\S+ origin=remote type=request .*uri=http://localhost:" + port + "/api/journey")
                .containsPattern(inboundJourney + "\\S+ origin=local type=response .*status=200")
                // ... the call it made on a virtual thread, outbound: no sid - the application's own work, found by traceId ...
                .containsPattern("(?m)^.* " + outboundEcho + "origin=local type=request .*uri=http://localhost:" + port + "/api/echo")
                .containsPattern("(?m)^.* " + outboundEcho + "origin=remote type=response .*status=200")
                .doesNotContainPattern("sid=\\S+ direction=outbound")
                // ... and that call arriving, inbound - it carried no cookie, so it is outside the journey
                .containsPattern("sid=- direction=inbound peer=\\S+ origin=remote type=request .*/api/echo")
                .contains("creed.tracker.http.inbound", "creed.tracker.http.outbound");
    }

    @Test
    void aResponseWithHeadersOverEightKilobytesStillLeavesItsRequestInTheLog(CapturedOutput output) throws Exception {
        String sid = connect();

        HttpResponse<String> big = send("GET", "/api/big-header", sid);

        // Tomcat refuses the headers when it commits the response and answers 500 without them
        assertThat(big.statusCode()).isEqualTo(500);
        assertThat(big.headers().firstValue("X-Big")).isEmpty();
        assertThat(output.getOut())
                // the request was logged before the application ran ...
                .containsPattern("sid=" + sid + " direction=inbound peer=\\S+ origin=remote type=request .*/api/big-header")
                // ... and the tracker names what the 500 hides
                .containsPattern("WARN .*sid=" + sid + " GET /api/big-header response headers ~\\d+ bytes exceed 8192 bytes"
                        + " .*instead of status 200; largest headers: X-Big=10")
                ;
    }
}
