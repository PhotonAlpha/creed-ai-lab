# Apache HTTP Server 2.4 + mod_proxy_cluster (mod_cluster's httpd side), built from source.
#
# The official httpd image has no mod_cluster modules and no distro package fits it, so they are
# compiled here against the image's own headers with upstream's cmake build, and only the .so files
# are carried into the runtime stage. (The per-module buildconf/configure loop from upstream's
# test/httpd/Containerfile fails against this image: the modules share ../common, and the second one
# links a common.lo the first one left behind — "'common.lo' is not a valid libtool object".)
#
# MPC_REF pins the upstream commit (the repository publishes no release tags); bump it deliberately.

ARG HTTPD_VERSION=2.4.68

FROM httpd:${HTTPD_VERSION} AS builder
ARG MPC_REF=f3761f22799d5e85421446d8cd6ff439ebd8f1cc
RUN apt-get update \
 && apt-get install -y --no-install-recommends gcc make cmake libc6-dev \
      libapr1-dev libaprutil1-dev ca-certificates curl \
 && rm -rf /var/lib/apt/lists/*
RUN curl -fsSL "https://github.com/modcluster/mod_proxy_cluster/archive/${MPC_REF}.tar.gz" \
      | tar -xz -C /tmp \
 && mv /tmp/mod_proxy_cluster-${MPC_REF} /mpc
WORKDIR /mpc/native
# mod_manager: receives MCMP (CONFIG/ENABLE-APP/STATUS/INFO …) and keeps the node tables.
# mod_proxy_cluster: turns those tables into proxy workers/balancers and routes requests.
# advertise / balancers (mod_lbmethod_cluster) are built for completeness but not loaded.
RUN cmake -S . -B build -DAPACHE_INCLUDE_DIR=/usr/local/apache2/include \
 && cmake --build build -j"$(nproc)" \
 && mkdir -p /out && cp build/modules/*.so /out/ && ls -l /out

FROM httpd:${HTTPD_VERSION}
COPY --from=builder /out/*.so /usr/local/apache2/modules/
# The whole httpd.conf is ours (mounted by docker-compose): minimal modules, no port 80.
EXPOSE 6666 9443 16666
