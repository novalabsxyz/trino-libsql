# Local Development with Docker + DBeaver

End-to-end recipe for running the connector locally against a dockerized libSQL
server, with DBeaver (or any Trino client) connecting to Trino on `localhost:8080`.

## Architecture

```
DBeaver  ──▶  Trino (container)  ──▶  libsql-server (container)
localhost:8080         trino-net          libsql:8080
```

Both containers join a user-defined Docker network (`trino-net`) so Trino can
reach `sqld` by container name. Because `http://libsql:8080` is not a loopback
address, the connector requires an explicit opt-in: `libsql.allow-insecure-http=true`.
This flag should **only** be set for trusted networks.

## Prerequisites

- Docker
- JDK 25 + Maven (only needed for the initial plugin build)

## 1. Build the plugin

```bash
./mvnw -B clean package -DskipTests
```

Produces `target/trino-libsql-<version>/`, which the `Dockerfile` copies into
the Trino image.

## 2. Create a Docker network

```bash
docker network create trino-net
```

## 3. Start libsql-server

```bash
docker run -d --name libsql --network trino-net -p 8081:8080 \
  ghcr.io/tursodatabase/libsql-server:latest
```

The `-p 8081:8080` mapping is only for seeding test data from the host via
`curl`. Trino reaches the same port over the Docker network as `libsql:8080`.

Verify:

```bash
curl -s http://localhost:8081/health  # should print "ok"
```

## 4. Build the Trino image

```bash
docker build --build-arg TRINO_VERSION=479 \
  --build-arg VERSION=0.3-SNAPSHOT \
  -t trino-libsql .
```

Replace `0.3-SNAPSHOT` with the version in `pom.xml`.

## 5. Run Trino with a local catalog override

The shipped `catalog/libsql.properties` uses `${ENV:LIBSQL_URL}` /
`${ENV:LIBSQL_TOKEN}` placeholders intended for Turso. For a local docker run,
mount a simpler file:

```bash
mkdir -p /tmp/trino-catalog
cat > /tmp/trino-catalog/libsql.properties <<'EOF'
connector.name=libsql
connection-url=jdbc:dbeaver:libsql:http://libsql:8080
libsql.allow-insecure-http=true
EOF

docker run -d --name trino --network trino-net -p 8080:8080 \
  -v /tmp/trino-catalog/libsql.properties:/etc/trino/catalog/libsql.properties:ro \
  trino-libsql
```

Wait for startup (~10–30s):

```bash
curl -sf http://localhost:8080/v1/info && echo " — Trino ready"
```

## 6. Seed test data

The libSQL Hrana HTTP pipeline accepts batched statements:

```bash
curl -s http://localhost:8081/v2/pipeline \
  -H 'content-type: application/json' -d '{
    "requests":[
      {"type":"execute","stmt":{"sql":"CREATE TABLE t(id INTEGER, name TEXT)"}},
      {"type":"execute","stmt":{"sql":"INSERT INTO t VALUES (1,'\''alice'\''),(2,'\''bob'\'')"}},
      {"type":"close"}
    ]
  }'
```

## 7. Verify end-to-end via the Trino CLI

```bash
docker exec trino trino --execute "SELECT * FROM libsql.default.t"
```

Should print:

```
"1","alice"
"2","bob"
```

## 8. Connect DBeaver

- **Driver**: Trino
- **Host**: `localhost`
- **Port**: `8080`
- **User**: `admin` (any non-empty string)
- **Password**: leave empty

Browse `libsql.default.*` and run queries.

## Iterating on the connector

After modifying connector code, rebuild the plugin and the image:

```bash
./mvnw -B clean package -DskipTests \
  && docker build --build-arg TRINO_VERSION=479 \
       --build-arg VERSION=0.3-SNAPSHOT -t trino-libsql . \
  && docker rm -f trino \
  && docker run -d --name trino --network trino-net -p 8080:8080 \
       -v /tmp/trino-catalog/libsql.properties:/etc/trino/catalog/libsql.properties:ro \
       trino-libsql
```

The libsql container (and its data) is preserved across these cycles.

## Teardown

```bash
docker rm -f trino libsql
docker network rm trino-net
rm -rf /tmp/trino-catalog
```
