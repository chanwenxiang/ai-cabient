FROM postgres:16-alpine@sha256:f2f98726820d0342055351c6e9d36e705f5bfffb3dd53ff556325114b08afb6d

# Replace the upstream gosu binary built with vulnerable Go 1.24.6.
RUN apk add --no-cache gosu=1.19-r6 \
    && rm -f /usr/local/bin/gosu \
    && ln -s /usr/bin/gosu /usr/local/bin/gosu \
    && gosu --version
