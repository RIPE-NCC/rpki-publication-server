FROM mozilla/sbt as build

# Use /staging/data, since /app/../data is equal to /data and would be cleared
# by the tests.
RUN mkdir -p /staging/conf /staging/conf/ssl /staging/data/db /staging/data/logs /staging/data/rsync /staging/data/rrdp

ADD . /app

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

COPY --from=build /staging/conf/ /conf/
COPY --from=build /staging/data/ /data/
COPY --from=build /app/target/rpki-publication-server.jar /app/

EXPOSE 7766
EXPOSE 7788

VOLUME ["/conf", "/data"]

ENV _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true \    
    -Djava.net.preferIPv4Addresses=true \
    -Dapp.name=rpki-publication-server \    
    -Xms256m -Xmx800m \
    -XX:HeapDumpPath=/data/dumps/pubserver-heap-dump.hprof \
    -Dconfig.file=/conf/publication-server-docker.conf"

CMD ["/app/rpki-publication-server.jar"]
