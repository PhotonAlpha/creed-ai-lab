package com.creed.report.mail;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * <pre>
 * POST /api/mail/probe?mode=batch|sequential|concurrent&amp;count=N&amp;to=addr
 *   → {mode, connections, maxConcurrentConnections, results[], events[]}
 * </pre>
 * Sends N <b>real</b> mails through {@code spring.mail.*}. POST, not GET, because it has an effect
 * outside this app; 400 on a bad mode, a count outside 1..{@code creed.report.mail-probe.max-messages}
 * or a missing recipient. A message that fails to send is reported in {@code results}, not as a 5xx,
 * so a half-failed concurrent run still shows its connection counts.
 */
@RestController
public class MailConnectionProbeController {

    private final MailConnectionProbeService probeService;

    public MailConnectionProbeController(MailConnectionProbeService probeService) {
        this.probeService = probeService;
    }

    @PostMapping(path = "/api/mail/probe", produces = MediaType.APPLICATION_JSON_VALUE)
    public MailConnectionProbeService.ProbeResult probe(@RequestParam(defaultValue = "concurrent") String mode,
                                                       @RequestParam(defaultValue = "3") int count,
                                                       @RequestParam String to) {
        return probeService.probe(mode, count, to);
    }
}
