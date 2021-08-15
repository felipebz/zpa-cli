FROM adoptopenjdk:11 AS builder

COPY . /app

RUN cd /app && \
    ./gradlew build && \
    cd build/distributions && \
    mv -f zpa-cli-shadow-*.tar zpa-cli.tar

FROM adoptopenjdk:11-jre

COPY --from=builder /app/build/distributions/zpa-cli.tar /opt/

RUN cd /opt && \
    tar xvf zpa-cli.tar && \
    rm -f zpa-cli.tar && \
    mv -f zpa-cli-*/ zpa-cli/

ENV PATH=/opt/zpa-cli/bin:$PATH

WORKDIR /wd

CMD [ "zpa-cli", "--help" ]
