// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

/** Literal addresses, so no test depends on what a name resolves to here. */
class OutboundUrlsTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/mcp", "http://[::1]/mcp", "http://localhost:8080/mcp",
            "http://10.1.2.3/", "http://172.16.0.1/", "http://192.168.1.1/",
            "http://169.254.169.254/latest/meta-data", "http://100.64.0.1/",
            "http://[fc00::1]/", "http://[fd12::1]/", "http://[fe80::1]/",
            "http://[::ffff:10.0.0.1]/", "http://224.0.0.1/", "http://0.0.0.0/"})
    void privateAndLocalAddressesAreRefusedUnlessAllowed(String url) {
        assertThat(OutboundUrls.refuse(URI.create(url), false)).contains("private or local address");
        assertThat(OutboundUrls.refuse(URI.create(url), true)).isNull();
    }

    @Test
    void aPublicAddressIsAllowed() {
        assertThat(OutboundUrls.refuse(URI.create("https://8.8.8.8/mcp"), false)).isNull();
        assertThat(OutboundUrls.refuse(URI.create("https://[2001:4860:4860::8888]/mcp"), false)).isNull();
    }

    @Test
    void theShapeIsCheckedBeforeTheAddressWhateverTheDeploymentAllows() {
        assertThat(OutboundUrls.refuse(URI.create("ftp://8.8.8.8/"), true)).isEqualTo("Not an http(s) URL.");
        assertThat(OutboundUrls.refuse(URI.create("/mcp"), true)).isEqualTo("Not an http(s) URL.");
        assertThat(OutboundUrls.refuse(URI.create("http:///mcp"), true)).isEqualTo("The URL names no host.");
        assertThat(OutboundUrls.refuse(URI.create("https://user:pw@8.8.8.8/"), true))
                .isEqualTo("The URL must not carry a user name or password.");
        assertThat(OutboundUrls.refuse(null, true)).isEqualTo("No URL.");
    }

    @Test
    void aHostThatDoesNotResolveIsSaidSo() {
        assertThat(OutboundUrls.refuse(URI.create("https://nothing-here.invalid/mcp"), false))
                .isEqualTo("The host could not be resolved.");
    }
}
