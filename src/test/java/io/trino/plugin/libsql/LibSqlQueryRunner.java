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
import io.trino.Session;
import io.trino.testing.DistributedQueryRunner;

import java.util.Map;

import static io.trino.testing.TestingSession.testSessionBuilder;

public final class LibSqlQueryRunner
{
    private LibSqlQueryRunner() {}

    public static DistributedQueryRunner createLibSqlQueryRunner(
            TestingLibSqlServer server,
            Map<String, String> extraProperties,
            Map<String, String> connectorProperties)
            throws Exception
    {
        Session session = testSessionBuilder()
                .setCatalog("libsql")
                .setSchema("default")
                .build();

        DistributedQueryRunner queryRunner = DistributedQueryRunner.builder(session)
                .setExtraProperties(extraProperties)
                .build();

        try {
            queryRunner.installPlugin(new LibSqlPlugin());
            queryRunner.createCatalog(
                    "libsql",
                    "libsql",
                    ImmutableMap.<String, String>builder()
                            .put("connection-url", server.getJdbcUrl())
                            .putAll(connectorProperties)
                            .buildOrThrow());
            return queryRunner;
        }
        catch (Exception e) {
            queryRunner.close();
            throw e;
        }
    }
}
