FROM postgres:16-alpine@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d AS patched

RUN apk add --no-cache gosu=1.19-r6 libxml2=2.13.9-r3 \
    && rm -f /usr/local/bin/gosu \
    && ln -s /usr/bin/gosu /usr/local/bin/gosu \
    && gosu --version

FROM scratch
COPY --from=patched / /

ENV PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin \
    GOSU_VERSION=1.19 \
    LANG=en_US.utf8 \
    PG_MAJOR=16 \
    PG_VERSION=16.16 \
    PG_SHA256=9d5cd8633043b75740d5d59749c6b7365e6aeb53c47098b1a5e80bb18f87881 \
    DOCKER_PG_LLVM_DEPS="llvm21-dev 		clang21" \
    PGDATA=/var/lib/postgresql/data

ENTRYPOINT ["docker-entrypoint.sh"]
CMD ["postgres"]
EXPOSE 5432/tcp
VOLUME ["/var/lib/postgresql/data"]
STOPSIGNAL SIGINT
WORKDIR /
