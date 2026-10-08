# creed-gateway-proxy

An nginx-style reverse proxy (`location` + `proxy_pass`) built on Spring Cloud Gateway (reactive). It
runs **on a remote server** and forwards to ports opened by `ssh -R` reverse tunnels. A page served
on your laptop, e.g. the Vite dev server at `http://localhost:5173`, then opens from the server's
address, hot reload included.

```
 browser ──► server:8088  creed-gateway-proxy
                 │  http://127.0.0.1:15173   (loopback port opened by sshd for the -R tunnel)
                 ▼
            ssh -R tunnel ─────────────────► laptop  localhost:5173  (Vite)
```

The tunnel port binds to the server's **loopback** (sshd's default `GatewayPorts no`). From the
network, the only way in is through this proxy.

## Quick start

**1. Laptop:** start the app and open the tunnel.

```bash
cd creed-env-matrix-design && npm run dev          # Vite on :5173
ssh -N -R 127.0.0.1:15173:localhost:5173 \
    -o ServerAliveInterval=30 -o ExitOnForwardFailure=yes user@server
```

`ExitOnForwardFailure=yes` makes ssh quit instead of hanging on when 15173 is already taken on the
server, for example by a stale session. For a tunnel that comes back on its own, use
`autossh -M 0 -N -R …` with the same options.

**2. Server:** build the jar once, copy it over, then run it.

```bash
# on a build machine, from the repo root (plain `package` gives no executable jar here)
mvn -q -pl creed-gateway-proxy -am -DskipTests install
mvn -q -pl creed-gateway-proxy -DskipTests package spring-boot:repackage
scp creed-gateway-proxy/target/creed-gateway-proxy-1.0.0-SNAPSHOT.jar user@server:

# on the server (Java 21+)
java -jar creed-gateway-proxy-1.0.0-SNAPSHOT.jar
```

**3.** Open `http://server:8088/`. Locally you can also use
`mvn -pl creed-gateway-proxy spring-boot:run` from the repo root.

## Configuration

| Variable | Default | |
|---|---|---|
| `CREED_PROXY_PORT` | `8088` | public listener |
| `CREED_PROXY_ADDRESS` | `0.0.0.0` | bind address |
| `CREED_PROXY_TARGET` | `http://127.0.0.1:15173` | the default route's target = the server end of `-R` |
| `CREED_PROXY_MANAGEMENT_PORT` | `8089` | actuator, bound to `127.0.0.1` only |
| `CREED_PROXY_CONNECT_TIMEOUT_MS` | `3000` | connect timeout to a target |

Routes live in `creed.proxy.routes` (`application.yml`, or an external
`--spring.config.additional-location=file:./proxy.yml`). They are matched **in list order**, so put
the catch-all `/**` last. Each tunnel needs its own `-R` port:

```yaml
creed:
  proxy:
    routes:
      - id: grafana            # ssh -R 127.0.0.1:13000:localhost:3000
        path: /grafana/**
        uri: http://127.0.0.1:13000
        strip-prefix: 1        # /grafana/d/x  ->  /d/x
      - id: vite               # ssh -R 127.0.0.1:15173:localhost:5173
        path: /**
        uri: http://127.0.0.1:15173
```

| Field | Default | |
|---|---|---|
| `id` | required | shown in logs and `/actuator/health` |
| `path` | `/**` | Spring path pattern |
| `uri` | required | `http://` or `https://`. WebSockets are upgraded automatically (Vite HMR). |
| `strip-prefix` | `0` | number of leading path segments to drop |
| `preserve-host` | `false` | forward the browser's `Host` instead of the target's (see below) |

A bad route (no id, no host, a non-http scheme) fails startup.

## Behaviour worth knowing

- **Host header.** By default the target receives `Host: 127.0.0.1:15173`. Vite 6+ rejects a `Host`
  it doesn't know with *403 Blocked request. This host is not allowed*. An IP is always allowed, so
  there's nothing to configure on the laptop. Turning `preserve-host: true` on means adding the
  server's name to Vite's `server.allowedHosts`.
- **Tunnel down.** If nothing listens on the target port, every request gets
  `502 Bad Gateway: nothing is listening on 127.0.0.1:15173. Is the ssh -R tunnel connected…`. The
  gateway's default here would be a bare 500. Requests recover as soon as ssh reconnects, without a
  restart.
- **Health.** `curl localhost:8089/actuator/health/tunnels` makes a TCP connect to every target:
  UP/DOWN per route, and 503 overall while any route is down. Use it for monitoring the tunnel. An
  UP tunnel says nothing about whether the laptop's app is running.
- **No `X-Forwarded-*` headers.** Spring Cloud Gateway 4.3 adds them only for clients matching
  `spring.cloud.gateway.server.webflux.trusted-proxies`, and then also trusts the client's own
  `X-Forwarded-For`. Vite doesn't need them.
- **Plain HTTP, no auth.** Whatever the tunnel exposes, anyone who can reach port 8088 can see it. Keep
  8088 firewalled to your network. For TLS or a login, put the server's real nginx or another
  gateway in front.

## Tests

```bash
mvn -pl creed-gateway-proxy test
```

The tests use a JDK `HttpServer` stub as the far end of the tunnel. They cover catch-all forwarding
and the Host header, strip-prefix, the 502 for a dead port, and the `tunnels` health indicator. They
need no ssh, no Docker and no network.
