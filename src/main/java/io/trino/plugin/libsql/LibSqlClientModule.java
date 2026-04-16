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

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Set;

import static io.airlift.configuration.ConfigBinder.configBinder;
import static io.trino.spi.StandardErrorCode.CONFIGURATION_INVALID;

public class LibSqlClientModule
        extends AbstractConfigurationAwareModule
{
    private static final String URL_PREFIX = "jdbc:dbeaver:libsql:";
    private static final Set<String> LOOPBACK_HOSTS = Set.of("localhost", "127.0.0.1", "::1");

    @Override
    public void setup(Binder binder)
    {
        configBinder(binder).bindConfig(LibSqlConfig.class);
        binder.bind(JdbcClient.class).annotatedWith(ForBaseJdbc.class).to(LibSqlClient.class).in(Scopes.SINGLETON);
    }

    @Provides
    @Singleton
    @ForBaseJdbc
    public static ConnectionFactory getConnectionFactory(
            BaseJdbcConfig config,
            LibSqlConfig libSqlConfig,
            CredentialProvider credentialProvider,
            OpenTelemetry openTelemetry)
    {
        String url = config.getConnectionUrl();
        validateConnectionUrl(url, libSqlConfig.isAllowInsecureHttp());

        return DriverConnectionFactory.builder(new LibSqlDriver(), url, credentialProvider)
                .setOpenTelemetry(openTelemetry)
                .build();
    }

    static void validateConnectionUrl(String url, boolean allowInsecureHttp)
    {
        if (!url.startsWith(URL_PREFIX)) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url must start with '%s', got: %s".formatted(URL_PREFIX, url));
        }

        String serverUrl = url.substring(URL_PREFIX.length());
        URI uri;
        try {
            uri = new URI(serverUrl);
        }
        catch (URISyntaxException e) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url is not a valid URI: " + serverUrl, e);
        }

        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("http") && !scheme.equalsIgnoreCase("https"))) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url must use http:// or https://, got: " + serverUrl);
        }

        if (scheme.equalsIgnoreCase("https")) {
            return;
        }

        String host = uri.getHost();
        if (host == null) {
            throw new TrinoException(CONFIGURATION_INVALID,
                    "connection-url is missing a host: " + serverUrl);
        }

        if (allowInsecureHttp || isLoopback(host)) {
            return;
        }

        throw new TrinoException(CONFIGURATION_INVALID,
                ("connection-url uses insecure http:// for non-loopback host '%s'. " +
                        "Use https://, connect to a loopback address, or set 'libsql.allow-insecure-http=true' " +
                        "to opt in (e.g. trusted Docker network).").formatted(host));
    }

    private static boolean isLoopback(String host)
    {
        String normalized = host.toLowerCase();
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        return LOOPBACK_HOSTS.contains(normalized);
    }
}
