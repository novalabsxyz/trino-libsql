/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.plugin.libsql;

import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import io.airlift.log.Logger;
import io.trino.plugin.base.mapping.IdentifierMapping;
import io.trino.plugin.jdbc.BaseJdbcClient;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ColumnMapping;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.JdbcColumnHandle;
import io.trino.plugin.jdbc.JdbcOutputTableHandle;
import io.trino.plugin.jdbc.JdbcTableHandle;
import io.trino.plugin.jdbc.JdbcTypeHandle;
import io.trino.plugin.jdbc.QueryBuilder;
import io.trino.plugin.jdbc.RemoteTableName;
import io.trino.plugin.jdbc.WriteMapping;
import io.trino.plugin.jdbc.logging.RemoteQueryModifier;
import io.trino.spi.TrinoException;
import io.trino.spi.connector.ColumnMetadata;
import io.trino.spi.connector.ColumnPosition;
import io.trino.spi.connector.ConnectorSession;
import io.trino.spi.connector.ConnectorTableMetadata;
import io.trino.spi.connector.SchemaNotFoundException;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.connector.TableNotFoundException;
import io.trino.spi.type.Type;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.BiFunction;

import static io.trino.plugin.jdbc.JdbcErrorCode.JDBC_ERROR;
import static io.trino.plugin.jdbc.StandardColumnMappings.bigintColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.booleanColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.doubleColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varbinaryColumnMapping;
import static io.trino.plugin.jdbc.StandardColumnMappings.varcharColumnMapping;
import static io.trino.spi.StandardErrorCode.NOT_SUPPORTED;
import static io.trino.spi.type.VarcharType.createUnboundedVarcharType;
import static java.lang.String.format;

public class LibSqlClient
        extends BaseJdbcClient
{
    private static final Logger log = Logger.get(LibSqlClient.class);
    private static final String SCHEMA_NAME = "default";

    @Inject
    public LibSqlClient(
            BaseJdbcConfig config,
            ConnectionFactory connectionFactory,
            QueryBuilder queryBuilder,
            IdentifierMapping identifierMapping,
            RemoteQueryModifier remoteQueryModifier)
    {
        super("\"", connectionFactory, queryBuilder, config.getJdbcTypesMappedToVarchar(), identifierMapping, remoteQueryModifier, true);
    }

    // -- Schema discovery --

    @Override
    public Collection<String> listSchemas(Connection connection)
    {
        return ImmutableList.of(SCHEMA_NAME);
    }

    @Override
    public List<SchemaTableName> getTableNames(ConnectorSession session, Optional<String> schema)
    {
        if (schema.isPresent() && !schema.get().equals(SCHEMA_NAME)) {
            throw new SchemaNotFoundException(schema.get());
        }

        try (Connection connection = connectionFactory.openConnection(session);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            ImmutableList.Builder<SchemaTableName> tables = ImmutableList.builder();
            while (resultSet.next()) {
                String tableName = resultSet.getString("name");
                if (!tableName.startsWith("sqlite_") && !tableName.startsWith("_litestream")) {
                    tables.add(new SchemaTableName(SCHEMA_NAME, tableName));
                }
            }
            return tables.build();
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    // -- Table handle (bypasses DatabaseMetaData.getTables which throws on non-null schema) --

    @Override
    public Optional<JdbcTableHandle> getTableHandle(ConnectorSession session, SchemaTableName schemaTableName)
    {
        if (!schemaTableName.getSchemaName().equals(SCHEMA_NAME)) {
            return Optional.empty();
        }

        String tableName = schemaTableName.getTableName();
        try (Connection connection = connectionFactory.openConnection(session);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT name FROM sqlite_master WHERE type='table' AND name='" +
                                tableName.replace("'", "''") + "'")) {
            if (!resultSet.next()) {
                return Optional.empty();
            }

            RemoteTableName remoteTableName = new RemoteTableName(
                    Optional.empty(),
                    Optional.empty(),
                    tableName);

            return Optional.of(new JdbcTableHandle(schemaTableName, remoteTableName, Optional.empty()));
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    // -- Column discovery (bypasses getColumns() which hardcodes all types as VARCHAR) --

    @Override
    public List<JdbcColumnHandle> getColumns(ConnectorSession session, SchemaTableName schemaTableName, RemoteTableName remoteTableName)
    {
        String tableName = schemaTableName.getTableName();

        try (Connection connection = connectionFactory.openConnection(session);
                Statement statement = connection.createStatement();
                ResultSet resultSet = statement.executeQuery(
                        "SELECT cid, name, type, \"notnull\" FROM pragma_table_info('" +
                                tableName.replace("'", "''") + "')")) {
            ImmutableList.Builder<JdbcColumnHandle> columns = ImmutableList.builder();
            int totalColumns = 0;
            int skippedColumns = 0;
            while (resultSet.next()) {
                totalColumns++;
                String columnName = resultSet.getString("name");
                String declaredType = resultSet.getString("type");
                boolean nullable = resultSet.getInt("notnull") == 0;
                int jdbcType = sqliteTypeToJdbcType(declaredType);

                JdbcTypeHandle typeHandle = new JdbcTypeHandle(
                        jdbcType,
                        Optional.ofNullable(declaredType),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty(),
                        Optional.empty());

                Optional<ColumnMapping> columnMapping = toColumnMapping(session, connection, typeHandle);
                if (columnMapping.isPresent()) {
                    columns.add(JdbcColumnHandle.builder()
                            .setColumnName(columnName)
                            .setJdbcTypeHandle(typeHandle)
                            .setColumnType(columnMapping.get().getType())
                            .setNullable(nullable)
                            .build());
                }
                else {
                    skippedColumns++;
                    log.warn("Skipping column '%s' in table '%s': unsupported type '%s'", columnName, tableName, declaredType);
                }
            }

            List<JdbcColumnHandle> result = columns.build();
            if (result.isEmpty()) {
                if (totalColumns == 0) {
                    throw new TableNotFoundException(schemaTableName);
                }
                throw new TrinoException(NOT_SUPPORTED, format(
                        "Table '%s' has %d columns but none have supported types. Unsupported columns were skipped.",
                        schemaTableName, skippedColumns));
            }
            if (skippedColumns > 0) {
                log.warn("Table '%s': %d of %d columns skipped due to unsupported types", tableName, skippedColumns, totalColumns);
            }
            return result;
        }
        catch (SQLException e) {
            throw new TrinoException(JDBC_ERROR, e);
        }
    }

    // -- Type mapping --

    @Override
    public Optional<ColumnMapping> toColumnMapping(ConnectorSession session, Connection connection, JdbcTypeHandle typeHandle)
    {
        Optional<ColumnMapping> mapping = getForcedMappingToVarchar(typeHandle);
        if (mapping.isPresent()) {
            return mapping;
        }

        return switch (typeHandle.jdbcType()) {
            case Types.BOOLEAN -> Optional.of(booleanColumnMapping());
            case Types.BIGINT -> Optional.of(bigintColumnMapping());
            case Types.DOUBLE -> Optional.of(doubleColumnMapping());
            case Types.VARCHAR -> Optional.of(varcharColumnMapping(createUnboundedVarcharType(), false));
            case Types.VARBINARY -> Optional.of(varbinaryColumnMapping());
            default -> {
                log.debug("No column mapping for JDBC type %d (%s)", typeHandle.jdbcType(), typeHandle.jdbcTypeName());
                yield Optional.empty();
            }
        };
    }

    @Override
    public WriteMapping toWriteMapping(ConnectorSession session, Type type)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables");
    }

    // -- Connection handling (driver throws on setReadOnly/setAutoCommit/setTransactionIsolation) --

    @Override
    public Connection getConnection(ConnectorSession session)
            throws SQLException
    {
        return connectionFactory.openConnection(session);
    }

    // -- LIMIT pushdown (critical: driver buffers entire result set in memory) --

    @Override
    protected Optional<BiFunction<String, Long, String>> limitFunction()
    {
        return Optional.of((sql, limit) -> sql + " LIMIT " + limit);
    }

    @Override
    public boolean isLimitGuaranteed(ConnectorSession session)
    {
        return true;
    }

    // -- DDL guard rails (read-only v1) --

    @Override
    public void createSchema(ConnectorSession session, String schemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating schemas");
    }

    @Override
    public void dropSchema(ConnectorSession session, String schemaName, boolean cascade)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping schemas");
    }

    @Override
    public void renameSchema(ConnectorSession session, String schemaName, String newSchemaName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming schemas");
    }

    @Override
    public void createTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables");
    }

    @Override
    public JdbcOutputTableHandle beginCreateTable(ConnectorSession session, ConnectorTableMetadata tableMetadata)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support creating tables with data");
    }

    @Override
    public JdbcOutputTableHandle beginInsertTable(ConnectorSession session, JdbcTableHandle tableHandle, List<JdbcColumnHandle> columns)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support inserts");
    }

    @Override
    public void truncateTable(ConnectorSession session, JdbcTableHandle handle)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support truncating tables");
    }

    @Override
    public void dropTable(ConnectorSession session, JdbcTableHandle handle)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping tables");
    }

    @Override
    public void renameTable(ConnectorSession session, JdbcTableHandle handle, SchemaTableName newTableName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming tables");
    }

    @Override
    public void addColumn(ConnectorSession session, JdbcTableHandle handle, ColumnMetadata column, ColumnPosition position)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support adding columns");
    }

    @Override
    public void dropColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle column)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support dropping columns");
    }

    @Override
    public void renameColumn(ConnectorSession session, JdbcTableHandle handle, JdbcColumnHandle jdbcColumn, String newColumnName)
    {
        throw new TrinoException(NOT_SUPPORTED, "This connector does not support renaming columns");
    }

    // -- Helpers --

    static int sqliteTypeToJdbcType(String declaredType)
    {
        if (declaredType == null || declaredType.isEmpty()) {
            return Types.VARCHAR;
        }
        String upper = declaredType.toUpperCase(Locale.ENGLISH);
        if (upper.contains("INT")) {
            return Types.BIGINT;
        }
        if (upper.contains("REAL") || upper.contains("FLOAT") || upper.contains("DOUB")) {
            return Types.DOUBLE;
        }
        if (upper.contains("BOOL")) {
            return Types.BOOLEAN;
        }
        if (upper.contains("BLOB")) {
            return Types.VARBINARY;
        }
        // DATE, DATETIME, TIMESTAMP, NUMERIC, DECIMAL, TEXT, and all others
        // map to VARCHAR per SQLite type affinity rules. SQLite stores these
        // as text internally; Trino users can cast as needed.
        return Types.VARCHAR;
    }
}
