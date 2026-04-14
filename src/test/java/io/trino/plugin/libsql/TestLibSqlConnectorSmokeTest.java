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

import com.google.common.collect.ImmutableMap;
import io.trino.testing.BaseConnectorSmokeTest;
import io.trino.testing.QueryRunner;
import io.trino.testing.TestingConnectorBehavior;
import io.trino.tpch.Nation;
import io.trino.tpch.NationGenerator;
import io.trino.tpch.Region;
import io.trino.tpch.RegionGenerator;
import org.junit.jupiter.api.AfterAll;

import java.sql.SQLException;

import static io.trino.plugin.libsql.LibSqlQueryRunner.createLibSqlQueryRunner;

public class TestLibSqlConnectorSmokeTest
        extends BaseConnectorSmokeTest
{
    private TestingLibSqlServer server;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = new TestingLibSqlServer();
        seedTpchData(server);

        return createLibSqlQueryRunner(
                server,
                ImmutableMap.of(),
                ImmutableMap.of());
    }

    @AfterAll
    public void tearDown()
    {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Override
    protected boolean hasBehavior(TestingConnectorBehavior connectorBehavior)
    {
        return switch (connectorBehavior) {
            case SUPPORTS_CREATE_TABLE,
                    SUPPORTS_CREATE_TABLE_WITH_DATA,
                    SUPPORTS_INSERT,
                    SUPPORTS_UPDATE,
                    SUPPORTS_DELETE,
                    SUPPORTS_MERGE,
                    SUPPORTS_TRUNCATE,
                    SUPPORTS_RENAME_TABLE,
                    SUPPORTS_RENAME_TABLE_ACROSS_SCHEMAS,
                    SUPPORTS_CREATE_SCHEMA,
                    SUPPORTS_RENAME_SCHEMA,
                    SUPPORTS_DROP_SCHEMA_CASCADE,
                    SUPPORTS_ADD_COLUMN,
                    SUPPORTS_DROP_COLUMN,
                    SUPPORTS_RENAME_COLUMN,
                    SUPPORTS_SET_COLUMN_TYPE,
                    SUPPORTS_CREATE_VIEW,
                    SUPPORTS_CREATE_MATERIALIZED_VIEW,
                    SUPPORTS_COMMENT_ON_TABLE,
                    SUPPORTS_COMMENT_ON_COLUMN,
                    SUPPORTS_TOPN_PUSHDOWN -> false;
            default -> super.hasBehavior(connectorBehavior);
        };
    }

    private static void seedTpchData(TestingLibSqlServer server)
            throws SQLException
    {
        server.execute("CREATE TABLE region (regionkey INTEGER, name TEXT, comment TEXT)");
        for (Region region : new RegionGenerator()) {
            server.execute("INSERT INTO region VALUES (%d, '%s', '%s')".formatted(
                    region.getRegionKey(),
                    region.getName().replace("'", "''"),
                    region.getComment().replace("'", "''")));
        }

        server.execute("CREATE TABLE nation (nationkey INTEGER, name TEXT, regionkey INTEGER, comment TEXT)");
        for (Nation nation : new NationGenerator()) {
            server.execute("INSERT INTO nation VALUES (%d, '%s', %d, '%s')".formatted(
                    nation.getNationKey(),
                    nation.getName().replace("'", "''"),
                    nation.getRegionKey(),
                    nation.getComment().replace("'", "''")));
        }
    }
}
