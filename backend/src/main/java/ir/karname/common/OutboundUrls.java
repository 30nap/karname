package ir.karname.common;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;
import java.util.Set;

/**
 * Checks addresses the server calls on an administrator's behalf (AI services, price feeds).
 * Services on this machine or the local network stay allowed (Ollama, an internal price API), but
 * not link-local addresses: they include the cloud metadata service that hands out the server's
 * own credentials.
 */
public final class OutboundUrls {

    private static final Set<String> METADATA_HOSTS = Set.of("metadata.google.internal", "metadata", "instance-data");

    private OutboundUrls() {
    }

    /** An http(s) address without credentials in it, whose host is not link-local, multicast or "any". */
    public static boolean isAllowed(String url) {
        if (url == null || url.isEmpty() || url.length() > 500) {
            return false;
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String scheme = uri.getScheme();
        if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme) || uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        String host = uri.getHost().replaceAll("^\\[|]$", "").toLowerCase(Locale.ROOT);
        if (METADATA_HOSTS.contains(host) || host.matches("0x[0-9a-f]+")) {
            return false;
        }
        if (!host.matches("[0-9.]+") && !host.contains(":")) {
            return true;
        }
        try {
            // literal addresses, including the short forms "169.254.43518" and "2852039166"
            InetAddress address = InetAddress.ofLiteral(host);
            return !address.isLinkLocalAddress() && !address.isMulticastAddress() && !address.isAnyLocalAddress();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
