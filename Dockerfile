FROM bellsoft/liberica-runtime-container:jre-25.0.1_11-slim-musl

RUN addgroup -S -g 1001 zpa-cli \
 && adduser -S -D -u 1001 -G zpa-cli -h /home/zpa-cli zpa-cli

COPY build/distributions/zpa-cli-*.tar /tmp/

RUN set -eux; \
    mkdir -p /opt/zpa-cli; \
    tar -xf /tmp/zpa-cli-*.tar --strip-components=1 -C /opt/zpa-cli; \
    rm -f /tmp/zpa-cli-*.tar; \
    chown -R 1001:1001 /opt/zpa-cli

ENV PATH=/opt/zpa-cli/bin:$PATH

USER 1001:1001
WORKDIR /src

ENTRYPOINT ["/opt/zpa-cli/bin/zpa-cli"]
CMD ["--help"]

LABEL org.opencontainers.image.title="zpa-cli" \
      org.opencontainers.image.description="ZPA CLI tool" \
      org.opencontainers.image.source="https://github.com/felipebz/zpa-cli" \
      org.opencontainers.image.licenses="LGPL-3.0"
