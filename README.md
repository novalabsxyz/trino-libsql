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
mvn clean package -DskipTests

# Run all tests (requires Docker)
mvn test

# Run a single test
mvn test -Dtest=TestLibSqlConnectorSmokeTest#testSelect
```

## Deploy

After building, copy the plugin directory to your Trino installation:

```bash
cp -r target/trino-libsql-0.1-SNAPSHOT/ <trino-install>/plugin/libsql/
```

Restart the Trino server to load the connector.

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

All tables are exposed under a single schema called `default`. Query them as:

```sql
SELECT * FROM libsql.default.my_table LIMIT 10;
```

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

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for details on how to contribute code or report bugs.

## License

Apache License 2.0 -- see [LICENSE](LICENSE).
