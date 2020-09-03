FROM mozilla/sbt as build

# Use /staging/data, since /app/../data is equal to /data and would be cleared
RUN mkdir -p /staging/conf /staging/data/db /staging/data/logs /staging/data/rsync /staging/data/rrdp

ADD . /app

WORKDIR /app
RUN sbt assembly
RUN cp docker/publication-server-docker.conf /staging/conf/

FROM gcr.io/distroless/java-debian10:11-debug

COPY --from=build /staging/conf/ /conf/
COPY --from=build /staging/data/ /data/
COPY --from=build /app/target/rpki-publication-server.jar /app/

EXPOSE 7766
EXPOSE 7788

VOLUME ["/conf", "/data"]

ENV _JAVA_OPTIONS="-Djava.net.preferIPv4Stack=true \
    -Djava.net.preferIPv4Addresses=true \
    -Dapp.name=rpki-publication-server \
    -Dconfig.file=/conf/publication-server-docker.conf"

CMD ["/app/rpki-publication-server.jar"]
