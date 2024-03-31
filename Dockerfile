FROM eclipse-temurin:21-alpine as jre-build

RUN $JAVA_HOME/bin/jlink \
         --add-modules java.logging,java.xml,java.sql \
         --strip-debug \
         --no-man-pages \
         --no-header-files \
         --output /javaruntime

FROM alpine:latest
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH "${JAVA_HOME}/bin:${PATH}"
COPY --from=jre-build /javaruntime $JAVA_HOME

ADD build/distributions/zpa-cli-*.tar /opt/

RUN mv /opt/zpa-cli-*/ /opt/zpa-cli/

ENV PATH=/opt/zpa-cli/bin:$PATH

WORKDIR /src

ENTRYPOINT ["zpa-cli"]

CMD [ "zpa-cli", "--help" ]
