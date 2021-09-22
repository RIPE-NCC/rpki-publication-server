FROM mozilla/sbt as build

ARG PUBLICATION_SERVER_JAR=target/rpki-publication-server.jar

# Use /staging/data, since /app/../data is equal to /data and would be cleared
# by the tests.
RUN mkdir -p /staging/conf /staging/conf/ssl /staging/data/db /staging/data/logs /staging/data/rrdp

ADD . /app
COPY ${PUBLICATION_SERVER_JAR} /app/rpki-publication-server.jar

#
# Container picks up the artifact from './target/rpki-publication-server.jar'
#
WORKDIR /app
# could RUN sbt assembly or RUN 'set test in assembly := {}' clean assembly here,
# but we pick up the artifact instead.
RUN cp docker/publication-server-docker.conf /staging/conf/

# use gcr.io/distroless/java-debian10:11-debug if you want to be able to run a
# shell in the container (e.g. `docker run -it --entrypoint sh --rm <image>`)
FROM gcr.io/distroless/java-debian10:11
LABEL org.label-schema.vcs-ref="unknown"

COPY --from=build /staging/conf/ /conf/
COPY --from=build /staging/data/ /data/
COPY --from=build /app/rpki-publication-server.jar /app/

EXPOSE 7766
EXPOSE 7788
EXPOSE 5005

VOLUME ["/conf", "/data"]

#ENV JAVA_TOOL_OPTIONS="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

ENV JAVA_TOOL_OPTIONS="-Djava.net.preferIPv4Stack=true \
    -Djava.net.preferIPv4Addresses=true \
    -Dapp.name=rpki-publication-server \
    -Xms1g \
    -Xmx4g \
    -XX:+HeapDumpOnOutOfMemoryError \
    -XX:HeapDumpPath=/data/dumps/pubserver-heap-dump.hprof \
    -agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005 \
    -Dconfig.file=/conf/publication-server-docker.conf"

CMD ["/app/rpki-publication-server.jar"]
