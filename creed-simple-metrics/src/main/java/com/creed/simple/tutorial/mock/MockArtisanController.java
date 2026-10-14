package com.creed.simple.tutorial.mock;

import com.creed.simple.tutorial.client.MyAccountDetailsVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The "artisan" external system that {@link com.creed.simple.tutorial.client.ArtisanClient} talks to.
 * A plain Spring MVC controller, so API-7 exercises a real HTTP round trip offline.
 *
 * <p>The client sends every body as a JSON string; this side still binds it to a typed
 * {@code MyAccountDetailsVO}, which shows the wire format is ordinary JSON either way.
 *
 * <p>It logs the headers it received, which makes the centrally added X-Client-Id / X-Trace-Id /
 * X-Channel visible. Accounts opened through {@code /create} are kept in memory, so
 * {@code /detail} can find them again; an unknown id is a 404.
 */
@RestController
@RequestMapping("/mock/artisan")
public class MockArtisanController {

    private static final Logger log = LoggerFactory.getLogger(MockArtisanController.class);

    private final Map<String, MyAccountDetailsVO> accounts = new ConcurrentHashMap<>();

    @PostMapping("/list")
    public List<MyAccountDetailsVO> list(@RequestBody MyAccountDetailsVO user,
                                         @RequestHeader HttpHeaders headers,
                                         @RequestParam Map<String, String> params) {
        logHeaders("list", headers);
        log.info("[MOCK-ARTISAN][list] body={} query params={}", user, params);

        double base = user.amount() == null ? 0d : user.amount();
        List<MyAccountDetailsVO> result = List.of(
                new MyAccountDetailsVO(user.userId() + "-PRIMARY", user.userName(),
                        user.channel(), round2(base)),
                new MyAccountDetailsVO(user.userId() + "-SAVINGS", user.userName(),
                        user.channel(), round2(base * 0.3)),
                new MyAccountDetailsVO(user.userId() + "-CREDIT", user.userName(),
                        user.channel(), round2(base * 1.5)));
        log.info("[MOCK-ARTISAN][list] returning {} accounts", result.size());
        return result;
    }

    /** Opens an account; the id is "ACC-" + the userId the caller sent. */
    @PostMapping("/create")
    public MyAccountDetailsVO create(@RequestBody MyAccountDetailsVO user,
                                     @RequestHeader HttpHeaders headers) {
        logHeaders("create", headers);
        MyAccountDetailsVO created = new MyAccountDetailsVO("ACC-" + user.userId(), user.userName(),
                user.channel(), round2(user.amount() == null ? 0d : user.amount()));
        accounts.put(created.userId(), created);
        log.info("[MOCK-ARTISAN][create] opened {}", created);
        return created;
    }

    /** Looks an account up by the userId field of the body. */
    @PostMapping("/detail")
    public ResponseEntity<MyAccountDetailsVO> detail(@RequestBody MyAccountDetailsVO query,
                                                     @RequestHeader HttpHeaders headers) {
        logHeaders("detail", headers);
        MyAccountDetailsVO found = accounts.get(query.userId());
        log.info("[MOCK-ARTISAN][detail] {} -> {}", query.userId(), found == null ? "not found" : found);
        return found == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(found);
    }

    private static void logHeaders(String op, HttpHeaders headers) {
        log.info("[MOCK-ARTISAN][{}] X-Client-Id={} X-Trace-Id={} X-Channel={} Content-Type={}", op,
                headers.getFirst("X-Client-Id"), headers.getFirst("X-Trace-Id"),
                headers.getFirst("X-Channel"), headers.getFirst(HttpHeaders.CONTENT_TYPE));
    }

    private static double round2(double v) {
        return Math.round(v * 100d) / 100d;
    }
}
