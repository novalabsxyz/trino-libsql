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

import io.trino.spi.TrinoException;
import org.junit.jupiter.api.Test;

import static io.trino.plugin.libsql.LibSqlClientModule.validateConnectionUrl;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLibSqlClientModule
{
    @Test
    void acceptsHttpsForAnyHost()
    {
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:https://example.turso.io", false))
                .doesNotThrowAnyException();
    }

    @Test
    void acceptsHttpForLoopback()
    {
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://localhost:8080", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://127.0.0.1:8080", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://[::1]:8080", false))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsHttpForRemoteHostByDefault()
    {
        assertThatThrownBy(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://db.example.com", false))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("insecure http://")
                .hasMessageContaining("libsql.allow-insecure-http");
    }

    @Test
    void rejectsLoopbackSubdomainSpoof()
    {
        // Prefix-matching bug — http://localhost.evil.com would previously pass.
        assertThatThrownBy(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://localhost.evil.com", false))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("insecure http://");
        assertThatThrownBy(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://127.0.0.1.evil.com", false))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("insecure http://");
    }

    @Test
    void allowsHttpForAnyHostWhenOptedIn()
    {
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://libsql:8080", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> validateConnectionUrl("jdbc:dbeaver:libsql:http://db.example.com", true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingPrefix()
    {
        assertThatThrownBy(() -> validateConnectionUrl("jdbc:libsql:http://localhost", false))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("must start with");
    }

    @Test
    void rejectsUnsupportedScheme()
    {
        assertThatThrownBy(() -> validateConnectionUrl("jdbc:dbeaver:libsql:ftp://localhost", false))
                .isInstanceOf(TrinoException.class)
                .hasMessageContaining("http:// or https://");
    }
}
