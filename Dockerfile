FROM eclipse-temurin:17-jre-alpine

COPY build/distributions/zpa-cli-shadow-*.tar /opt/

RUN cd /opt && \
    mv zpa-cli-shadow-*.tar zpa-cli.tar && \
    tar xvf zpa-cli.tar && \
    rm -f zpa-cli.tar && \
    mv -f zpa-cli-*/ zpa-cli/

ENV PATH=/opt/zpa-cli/bin:$PATH

WORKDIR /src

ENTRYPOINT ["zpa-cli"]

CMD [ "zpa-cli", "--help" ]
