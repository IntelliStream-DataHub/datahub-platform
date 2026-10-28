// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * Whether a URL a tenant supplied may be called from one of the platform's own services.
 *
 * <p>A backend service sits on networks a tenant's browser does not: the database hosts, the
 * cloud's metadata endpoint, the other services. A URL a tenant may point it at is therefore a
 * way to make requests from inside, and when the reply comes back to the tenant — as a tool
 * result an agent's model reads and its transcript shows — a way to read from inside too. So a
 * host that resolves to a loopback, link-local, private, carrier-grade NAT, unique-local or
 * multicast address is refused, unless the deployment says its private networks are fair game
 * ({@code datahub.outbound.allow-private-networks}: a development stack, or an intranet where
 * the tool servers live beside the platform).
 *
 * <p>Checked when the URL is stored and again right before it is first connected to, so a DNS
 * record that changed in between is caught. What remains is a record that changes between that
 * second check and the HTTP client's own lookup a moment later; closing that needs a resolver the
 * JDK client does not expose, and is not attempted here.
 */
public final class OutboundUrls {

    private OutboundUrls() {
    }

    /**
     * Null when the URL may be called; otherwise why not, in a sentence for the person who
     * supplied it.
     */
    public static String refuse(URI url, boolean allowPrivateNetworks) {
        if (url == null) {
            return "No URL.";
        }
        String scheme = url.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return "Not an http(s) URL.";
        }
        String host = url.getHost();
        if (host == null || host.isBlank()) {
            return "The URL names no host.";
        }
        if (url.getRawUserInfo() != null) {
            return "The URL must not carry a user name or password.";
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            return "The host could not be resolved.";
        }
        if (allowPrivateNetworks) {
            return null;
        }
        for (InetAddress address : addresses) {
            if (isPrivate(address)) {
                return "The host resolves to a private or local address (" + address.getHostAddress()
                        + "), which the platform does not call.";
            }
        }
        return null;
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            // 100.64.0.0/10, carrier-grade NAT: as private as 10/8, and not in isSiteLocalAddress.
            return (bytes[0] & 0xFF) == 100 && (bytes[1] & 0xC0) == 0x40;
        }
        // fc00::/7, unique local: the IPv6 private range, which isSiteLocalAddress (fec0::/10) predates.
        return address instanceof Inet6Address && (bytes[0] & 0xFE) == 0xFC;
    }
}
