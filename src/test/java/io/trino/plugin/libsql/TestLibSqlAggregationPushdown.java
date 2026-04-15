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
import io.trino.testing.AbstractTestQueryFramework;
import io.trino.testing.QueryRunner;
import io.trino.tpch.Nation;
import io.trino.tpch.NationGenerator;
import io.trino.tpch.Region;
import io.trino.tpch.RegionGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.sql.SQLException;

import static io.trino.plugin.libsql.LibSqlQueryRunner.createLibSqlQueryRunner;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies that supported aggregations are pushed down to libSQL instead of being
 * computed in Trino. The assertion uses {@code .isFullyPushedDown()} which checks
 * the explain plan shows only a single TableScan node (no Aggregation node).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class TestLibSqlAggregationPushdown
        extends AbstractTestQueryFramework
{
    private TestingLibSqlServer server;

    @Override
    protected QueryRunner createQueryRunner()
            throws Exception
    {
        server = new TestingLibSqlServer();
        seedTpchData(server);
        return createLibSqlQueryRunner(server, ImmutableMap.of(), ImmutableMap.of());
    }

    @AfterAll
    public void tearDown()
    {
        if (server != null) {
            server.close();
            server = null;
        }
    }

    @Test
    public void testCountAllPushdown()
    {
        assertThat(query("SELECT count(*) FROM nation")).isFullyPushedDown();
    }

    @Test
    public void testCountColumnPushdown()
    {
        assertThat(query("SELECT count(nationkey) FROM nation")).isFullyPushedDown();
    }

    @Test
    public void testSumPushdown()
    {
        assertThat(query("SELECT sum(nationkey) FROM nation")).isFullyPushedDown();
    }

    @Test
    public void testMinMaxPushdown()
    {
        assertThat(query("SELECT min(nationkey), max(nationkey) FROM nation")).isFullyPushedDown();
    }

    @Test
    public void testGroupByPushdown()
    {
        assertThat(query("SELECT regionkey, count(*) FROM nation GROUP BY regionkey")).isFullyPushedDown();
    }

    @Test
    public void testSumGroupByPushdown()
    {
        assertThat(query("SELECT regionkey, sum(nationkey) FROM nation GROUP BY regionkey")).isFullyPushedDown();
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
