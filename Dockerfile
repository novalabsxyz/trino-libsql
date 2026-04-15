ARG TRINO_VERSION
FROM trinodb/trino-core:${TRINO_VERSION}

ARG VERSION

ADD target/trino-libsql-${VERSION}/ /usr/lib/trino/plugin/libsql/
ADD catalog/libsql.properties /etc/trino/catalog/libsql.properties
