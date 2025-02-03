FROM eclipse-temurin:21.0.6_7-jdk-alpine AS jre-build

RUN "$JAVA_HOME"/bin/jlink \
         --add-modules java.logging,java.xml \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --output /javaruntime

FROM alpine:3.21
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

RUN addgroup -S -g 1001 zpa-cli && adduser -S -D -u 1001 -G zpa-cli zpa-cli

COPY --from=jre-build /javaruntime $JAVA_HOME

ADD build/distributions/zpa-cli-*.tar /opt/

RUN set -eux; \
    mv /opt/zpa-cli-*/ /opt/zpa-cli/; \
    chown -R zpa-cli:zpa-cli /opt

ENV PATH=/opt/zpa-cli/bin:$PATH

USER zpa-cli

WORKDIR /src

ENTRYPOINT ["zpa-cli"]

CMD [ "zpa-cli", "--help" ]
