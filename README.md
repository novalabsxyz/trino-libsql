# Trino libSQL Connector

A [Trino](https://trino.io/) connector for querying [libSQL](https://github.com/tursodatabase/libsql) and [Turso](https://turso.tech/) databases. It uses the [DBeaver libSQL JDBC driver](https://github.com/dbeaver/dbeaver-jdbc-libsql) to communicate over the Hrana HTTP protocol.

This is an out-of-tree JDBC connector targeting **Trino 479**. It is currently **read-only**.

## Requirements

- JDK 25 (Temurin recommended)
- Apache Maven 3.9+
- Docker (required for running tests via Testcontainers)

## Build

```bash
# Build the plugin (skip tests)
./mvnw -B clean package -DskipTests

# Run all tests (requires Docker)
./mvnw -B test

# Run a single test
./mvnw -B test -Dtest=TestLibSqlConnectorSmokeTest#testSelect
```

## Deploy

### Adding to an existing Trino Docker image

Download the plugin zip from [GitHub Releases](https://github.com/novalabsxyz/trino-libsql/releases) and add it to your Trino Dockerfile:

```dockerfile
# In your existing Trino Dockerfile
ADD https://github.com/novalabsxyz/trino-libsql/releases/download/v0.1/trino-libsql-0.1.zip /tmp/trino-libsql.zip
RUN mkdir -p /usr/lib/trino/plugin/libsql && \
    unzip /tmp/trino-libsql.zip -d /usr/lib/trino/plugin/libsql && \
    rm /tmp/trino-libsql.zip
```

Then add a catalog properties file (see [Configuration](#configuration) below) to your image or mount it at runtime.

### Standalone test image

A `Dockerfile` is included for standalone testing:

```bash
./mvnw -B clean package -DskipTests
docker build --build-arg TRINO_VERSION=479 --build-arg VERSION=0.1-SNAPSHOT -t trino-libsql .
docker run -p 8080:8080 -e LIBSQL_URL=https://your-db.turso.io -e LIBSQL_TOKEN=your-token trino-libsql
```

For an end-to-end local setup with a dockerized libSQL server and DBeaver, see [docs/LOCAL_DEVELOPMENT.md](docs/LOCAL_DEVELOPMENT.md).

### Manual install

Copy the plugin directory to your Trino installation:

```bash
cp -r target/trino-libsql-0.1-SNAPSHOT/ <trino-install>/plugin/libsql/
```

Restart Trino to load the connector.

## Configuration

Create a catalog properties file at `<trino-install>/etc/catalog/libsql.properties`.

### Turso Cloud

```properties
connector.name=libsql
connection-url=jdbc:dbeaver:libsql:https://your-db.turso.io
connection-password=your-turso-auth-token
```

### Local sqld

```properties
connector.name=libsql
connection-url=jdbc:dbeaver:libsql:http://localhost:8080
```

### Local sqld over a Docker network (or other trusted network)

When Trino and `sqld` run in separate containers on a shared Docker network, the connection URL hostname is the container name (e.g. `libsql`), which is not loopback. Plain `http://` is rejected by default; opt in explicitly:

```properties
connector.name=libsql
connection-url=jdbc:dbeaver:libsql:http://libsql:8080
libsql.allow-insecure-http=true
```

⚠️ Only enable `libsql.allow-insecure-http` for trusted networks (Docker bridge, VPN, bastion). Never enable it for traffic that crosses the public internet.

All tables are exposed under a single schema called `default`. Query them as:

```sql
SELECT * FROM libsql.default.my_table LIMIT 10;
```

## Pushdowns

The connector pushes the following down to libSQL:

- **`LIMIT`** — critical because the underlying driver buffers the full HTTP response in memory.
- **Aggregations** — `count(*)`, `count(col)`, `sum`, `min`, `max`, and `GROUP BY` are pushed down. `avg`, `stddev`, `variance` are intentionally not pushed down (SQLite type semantics differ from Trino's).

## Type Mapping

The connector maps SQLite type affinity strings to Trino types:

| SQLite declared type contains | Trino type |
|-------------------------------|------------|
| `INT`                         | `bigint`   |
| `REAL`, `FLOAT`, `DOUB`      | `double`   |
| `BOOL`                        | `boolean`  |
| `BLOB`                        | `varbinary`|
| Everything else               | `varchar`  |

## Known Limitations

- **Read-only.** All write operations (CREATE, INSERT, UPDATE, DELETE, DDL) return a `NOT_SUPPORTED` error.
- **Full result buffering.** The DBeaver JDBC driver buffers entire HTTP JSON responses in memory. LIMIT pushdown is implemented to mitigate this, but queries without a LIMIT will fetch all matching rows before Trino processes them.
- **No HTTP timeouts.** The underlying JDBC driver does not expose HTTP timeout configuration.
- **Single schema.** All tables appear under the `default` schema. libSQL does not have a schema concept.
- **No TopN pushdown.** SQLite does not support `NULLS FIRST` / `NULLS LAST` syntax, so ORDER BY pushdown is intentionally disabled.
- **No `avg`/`stddev`/`variance` pushdown.** SQLite's `avg` returns a different type than Trino expects, and `avg(bigint)` requires a CAST that varies by dialect.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute code or report bugs.

## License

Apache License 2.0 -- see [LICENSE](LICENSE).
