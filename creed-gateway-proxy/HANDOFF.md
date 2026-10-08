# creed-gateway-proxy — handoff

## State (2026-10-08)

New module. It runs on a remote server and reverse-proxies onto `ssh -R` tunnel ports, so the
server's address serves a laptop's `http://localhost:5173` (Vite). User docs are in `README.md`.

- Spring Cloud Gateway **server-webflux** (reactive), standalone: no config client, no load balancer,
  no OAuth2. Listens on :8088; actuator on 127.0.0.1:8089.
- Routes are `creed.proxy.routes` (`ProxyProperties`) → `ProxyRouteConfig` builds them in list
  order, each with optional `stripPrefix` / `preserveHostHeader`. They are deliberately not
  `spring.cloud.gateway.server.webflux.routes`, so a mistyped route fails startup.
- `UpstreamUnavailableFilter` (highest precedence) turns `ConnectException` into a 502 with a hint.
- `TunnelHealthIndicator` (`tunnels`) does a TCP connect per route.

**Verified:**
- `mvn -pl creed-gateway-proxy test`: 4 tests pass.
- Run end to end against the real Vite dev server, with a TCP relay standing in for the server end
  of `ssh -R`, because this Mac has no sshd. Script: `tmp/fake-ssh-r.mjs`.
- Headless Chrome on a non-localhost name (`devbox.example`, via `--host-resolver-rules`). Script:
  `tmp/gateway-proxy-hmr.mjs`. The page loads, `/api` works through Vite's own proxy, the HMR
  WebSocket gets `101` and `[vite] connected.`, and a source edit reaches the page.
- Killing the relay gives a 502 with the hint and health 503/DOWN; it recovers without a restart.
- The repackaged jar runs.

**Not verified:**
- A real `ssh -R` against a real server.
- TLS (there is none).

## Open items

- Optional Basic auth or TLS, if the server port can't be firewalled.
- systemd unit and `autossh` recipe: written in the README but never run.

## Landmines

- **Don't preserve Host by default.** Vite 6+ answers an unknown Host with 403. The target's
  `127.0.0.1:<port>` always passes.
- **`X-Forwarded-*` needs `trusted-proxies`, and that also trusts the client's own headers.** In
  Gateway 4.3 the filter checks the *client's* remote address, so enabling it for browsers from
  anywhere means `.*`. It is off; the test pins `x-forwarded-host=null`.
- **Actuator stays on its own port.** On :8088, `/actuator/**` belongs to the app behind the tunnel
  (the catch-all route), and the health details name internal ports.
- The `discoveryComposite: UNKNOWN` entries in `/actuator/health` come from spring-cloud-commons and
  are harmless.
- Building a jar needs `package spring-boot:repackage`. Plain `package` is not executable in this
  repo (repo-wide rule).
