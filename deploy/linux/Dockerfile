ARG JAVA_BASE_IMAGE=eclipse-temurin:21-jre-jammy
FROM ${JAVA_BASE_IMAGE}

ARG MODULE
ARG APP_UID=10001
ARG APP_GID=10001
RUN case "$MODULE" in front|admin|crawl) ;; *) echo 'MODULE must be front, admin or crawl' >&2; exit 1 ;; esac \
    && command -v curl >/dev/null \
    && command -v java >/dev/null \
    && groupadd --gid "$APP_GID" novel \
    && useradd --uid "$APP_UID" --gid "$APP_GID" --create-home --shell /usr/sbin/nologin novel \
    && mkdir -p /app/config /data/pic /data/books /data/seo /opt/reader-resources \
    && chown "$APP_UID:$APP_GID" /data/pic /data/books /data/seo

WORKDIR /app
COPY --chown=novel:novel apps/${MODULE}.jar /app/app.jar
COPY --chown=novel:novel templates/ /app/templates/
COPY --chmod=0555 entrypoint.sh /usr/local/bin/novel-entrypoint
ENV APP_MODULE=${MODULE} SERVER_PORT=8080
USER novel:novel
EXPOSE 8080
ENTRYPOINT ["/usr/local/bin/novel-entrypoint"]
