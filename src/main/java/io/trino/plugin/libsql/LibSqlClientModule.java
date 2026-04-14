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

import com.dbeaver.jdbc.driver.libsql.LibSqlDriver;
import com.google.inject.Binder;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import com.google.inject.Singleton;
import io.airlift.configuration.AbstractConfigurationAwareModule;
import io.opentelemetry.api.OpenTelemetry;
import io.trino.plugin.jdbc.BaseJdbcConfig;
import io.trino.plugin.jdbc.ConnectionFactory;
import io.trino.plugin.jdbc.DriverConnectionFactory;
import io.trino.plugin.jdbc.ForBaseJdbc;
import io.trino.plugin.jdbc.JdbcClient;
import io.trino.plugin.jdbc.credential.CredentialProvider;
import io.trino.spi.TrinoException;

import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;

public class LibSqlClientModule
        extends AbstractConfigurationAwareModule
{
    private static final String URL_PREFIX = "jdbc:dbeaver:libsql:";

    @Override
    public void setup(Binder binder)
    {
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(LibSqlClient.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public static ConnectionFactory getConnectionFactory(
            BaseJdbcConfig config,
            CredentialProvider credentialProvider,
            OpenTelemetry openTelemetry)
    {
        String url = config.getConnectionUrl();
        if (!url.startsWith(URL_PREFIX)) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url must start with '%s', got: %s".formatted(URL_PREFIX, url));
        }

        String serverUrl = url.substring(URL_PREFIX.length());
        if (serverUrl.startsWith("http://") && !isLocalhost(serverUrl)) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url uses insecure HTTP for a remote server. Use https:// or set connection-url to a localhost address for local development.");
        }

        return DriverConnectionFactory.builder(new LibSqlDriver(), url, credentialProvider)
                .setOpenTelemetry(openTelemetry)
                .build();
    }

    private static boolean isLocalhost(String serverUrl)
    {
        // Allow http:// only for localhost/127.0.0.1/[::1] (local development)
        String lower = serverUrl.toLowerCase();
        return lower.startsWith("http://localhost") ||
                lower.startsWith("http://127.0.0.1") ||
                lower.startsWith("http://[::1]");
    }
}
