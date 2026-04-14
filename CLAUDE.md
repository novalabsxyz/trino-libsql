# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Read-only Trino JDBC connector for libSQL/Turso databases. Targets Trino 479, JDK 25, using the DBeaver libSQL JDBC driver over the hrana HTTP protocol.

## Build Commands

```bash
mvn clean package -DskipTests   # Build plugin JAR
mvn test                         # Run all tests (requires Docker for Testcontainers)
mvn test -pl . -Dtest=TestLibSqlConnectorSmokeTest#testSelect  # Run single test
mvn checkstyle:check             # Run checkstyle only
mvn license:format               # Auto-add Apache 2.0 license headers
mvn com.github.ekryd.sortpom:sortpom-maven-plugin:3.3.0:sort  # Sort pom.xml
```

JVM flag `--add-modules jdk.incubator.vector` is configured in surefire for tests (Trino 479 requires it).

## Architecture

Three production files, following the standard Trino out-of-tree JDBC connector pattern:

- **LibSqlPlugin** → extends `JdbcPlugin`, registers connector name `"libsql"`
- **LibSqlClientModule** → Guice module, binds `LibSqlClient` and creates `ConnectionFactory` with `LibSqlDriver`
- **LibSqlClient** → extends `BaseJdbcClient`, contains all connector logic

### Why LibSqlClient overrides so many BaseJdbcClient methods

The DBeaver libSQL JDBC driver has metadata limitations that force us to bypass the base class:

- `DatabaseMetaData.getColumns()` hardcodes all types as VARCHAR → we override `getColumns()` to query `pragma_table_info()` directly and map SQLite type affinity strings to proper JDBC types
- `DatabaseMetaData.getTables()` throws on non-null schema parameters → we override `getTableHandle()` to query `sqlite_master` directly
- `Connection.setReadOnly()` throws → we override `getConnection()` to skip it
- All write operations (CREATE/INSERT/UPDATE/DELETE/DDL) throw `NOT_SUPPORTED` with standard Trino error messages matching the pattern "This connector does not support ..."

### Type Mapping (sqliteTypeToJdbcType)

SQLite type affinity string → JDBC type → Trino type:
- Contains "INT" → BIGINT → bigint
- Contains "REAL"/"FLOAT"/"DOUB" → DOUBLE → double
- Contains "BOOL" → BOOLEAN → boolean
- Contains "BLOB" → VARBINARY → varbinary
- Everything else → VARCHAR → varchar

### LIMIT Pushdown

Critical because the DBeaver driver buffers entire HTTP JSON responses in memory. Without `limitFunction()`, `SELECT ... LIMIT 10` still fetches all rows. TopN pushdown is intentionally disabled — SQLite doesn't support `NULLS FIRST`/`NULLS LAST` syntax.

## Build Configuration Notes

- Parent POM: `io.airlift:airbase:334` (matches Trino 479)
- Airlift BOM imported in `<dependencyManagement>` — manages all airlift dependency versions
- `air.check.skip-extended=true` skips spotbugs/pmd/jacoco/modernizer (standard for out-of-tree connectors)
- Checkstyle is re-enabled explicitly (`air.check.skip-checkstyle=false`)
- Duplicate-finder skipped due to transitive test dep conflicts from trino-main

## Testing

Tests extend `BaseConnectorSmokeTest` (24 tests run, 12 write tests skipped via `hasBehavior`). Test data is seeded using TPC-H generators (`NationGenerator`, `RegionGenerator`) inserted via the JDBC driver into a Testcontainers-managed libsql-server instance.

## Catalog Configuration (deployment)

```properties
connector.name=libsql
connection-url=jdbc:dbeaver:libsql:https://your-db.turso.io
connection-password=your-turso-auth-token
```
