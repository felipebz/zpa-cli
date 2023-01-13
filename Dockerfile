FROM ubuntu:latest

ADD build/jreleaser/assemble/zpa-cli/jlink/zpa-cli-*-linux-x86_64.tar.gz /opt/

RUN mv /opt/zpa-cli-*/ /opt/zpa-cli/

ENV PATH=/opt/zpa-cli/bin:$PATH

WORKDIR /src

ENTRYPOINT ["zpa-cli"]

CMD [ "zpa-cli", "--help" ]
