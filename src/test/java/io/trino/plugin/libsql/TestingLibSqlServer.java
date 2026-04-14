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

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

import java.io.Closeable;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

public class TestingLibSqlServer
        implements Closeable
{
    private static final int SQLD_PORT = 8080;
    private static final String IMAGE = "ghcr.io/tursodatabase/libsql-server:latest";

    private final GenericContainer<?> container;

    public TestingLibSqlServer()
    {
        container = new GenericContainer<>(IMAGE)
                .withExposedPorts(SQLD_PORT)
                .waitingFor(Wait.forHttp("/health").forStatusCode(200));
        container.start();
    }

    public String getJdbcUrl()
    {
        return "jdbc:dbeaver:libsql:http://" + container.getHost() + ":" + container.getMappedPort(SQLD_PORT);
    }

    public void execute(String sql)
            throws SQLException
    {
        try (Connection connection = DriverManager.getConnection(getJdbcUrl());
                Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    @Override
    public void close()
    {
        container.stop();
    }
}
