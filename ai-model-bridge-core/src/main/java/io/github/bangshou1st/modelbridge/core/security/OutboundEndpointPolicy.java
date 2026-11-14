package io.github.bangshou1st.modelbridge.core.security;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/**
 * SSRF boundary for model endpoints.
 *
 * <p>The policy resolves the complete DNS answer before a request, rejects mixed public/private
 * answers (the DNS-rebinding shape), rejects metadata/private/link-local destinations, disables
 * redirects at the HTTP layer, and only accepts insecure loopback HTTP when the host and port are
 * explicitly whitelisted. The resolved address set is returned as a pin for the transport.</p>
 */
public final class OutboundEndpointPolicy {
    private static final Set<String> BLOCKED_HOST_NAMES = Set.of(
            "metadata.google.internal",
            "metadata",
            "instance-data",
            "169.254.169.254",
            "100.100.100.200",
            "168.63.129.16",
            "fd00:ec2::254"
    );
    private static final Set<String> CLOUD_METADATA_ADDRESSES = Set.of(
            "169.254.169.254",
            "100.100.100.200",
            "168.63.129.16",
            "fd00:ec2::254"
    );

    private final boolean allowLoopback;
    private final Set<String> loopbackHosts;
    private final Set<Integer> loopbackPorts;
    private final DnsResolver dnsResolver;

    public OutboundEndpointPolicy() {
        this(false, Set.of(), Set.of(), InetAddress::getAllByName);
    }

    public OutboundEndpointPolicy(boolean allowLoopback, Set<String> loopbackHosts,
                                  Set<Integer> loopbackPorts) {
        this(allowLoopback, loopbackHosts, loopbackPorts, InetAddress::getAllByName);
    }

    public OutboundEndpointPolicy(boolean allowLoopback, Set<String> loopbackHosts,
                                  Set<Integer> loopbackPorts, DnsResolver dnsResolver) {
        this.allowLoopback = allowLoopback;
        this.loopbackHosts = normalizeHosts(loopbackHosts);
        this.loopbackPorts = loopbackPorts == null ? Set.of() : Set.copyOf(loopbackPorts);
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
    }

    public static OutboundEndpointPolicy httpsOnly() {
        return new OutboundEndpointPolicy();
    }

    public static OutboundEndpointPolicy localDevelopment(Set<Integer> ports) {
        return new OutboundEndpointPolicy(true, Set.of("127.0.0.1", "::1", "localhost"), ports);
    }

    public PinnedEndpoint validate(URI uri) {
        Objects.requireNonNull(uri, "uri");
        if (uri.getUserInfo() != null || uri.getFragment() != null) {
            throw ModelBridgeException.networkPolicy("endpoint must not contain userinfo or fragment");
        }
        String scheme = lower(uri.getScheme());
        if (!"https".equals(scheme) && !"http".equals(scheme)) {
            throw ModelBridgeException.networkPolicy("only HTTP(S) model endpoints are allowed");
        }
        String host = lower(uri.getHost());
        if (host == null || host.isBlank()) {
            throw ModelBridgeException.networkPolicy("endpoint host is missing");
        }
        if (BLOCKED_HOST_NAMES.contains(host)) {
            throw ModelBridgeException.networkPolicy("metadata endpoint is blocked");
        }

        List<InetAddress> addresses;
        try {
            addresses = List.copyOf(Arrays.asList(dnsResolver.resolve(host)));
        } catch (UnknownHostException e) {
            throw new ModelBridgeException(ErrorCategory.UNAVAILABLE,
                    "model endpoint DNS resolution failed", null, 0, null, null, e);
        }
        if (addresses.isEmpty()) {
            throw ModelBridgeException.networkPolicy("model endpoint resolved to no address");
        }

        boolean loopbackRequest = isLoopbackHost(host, addresses);
        if (loopbackRequest) {
            if (!allowLoopback || !loopbackHosts.contains(host) || !loopbackPorts.isEmpty()
                    && !loopbackPorts.contains(effectivePort(uri))) {
                throw ModelBridgeException.networkPolicy("loopback endpoint is not whitelisted");
            }
        } else if ("http".equals(scheme)) {
            throw ModelBridgeException.networkPolicy("public model endpoints must use HTTPS");
        }

        List<InetAddress> pinned = new ArrayList<>();
        for (InetAddress address : addresses) {
            if (isBlocked(address) && !(loopbackRequest && address.isLoopbackAddress())) {
                throw ModelBridgeException.networkPolicy("private, link-local or metadata address is blocked");
            }
            if (loopbackRequest != address.isLoopbackAddress()) {
                throw ModelBridgeException.networkPolicy("mixed public and loopback DNS answers are blocked");
            }
            pinned.add(address);
        }
        return new PinnedEndpoint(uri, host, pinned, pinned.getFirst());
    }

    public PinnedEndpoint validate(String url) {
        try {
            return validate(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw ModelBridgeException.networkPolicy("invalid model endpoint URL");
        }
    }

    private static boolean isLoopbackHost(String host, List<InetAddress> addresses) {
        return "localhost".equals(host) || addresses.stream().allMatch(InetAddress::isLoopbackAddress);
    }

    private static boolean isBlocked(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isMulticastAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address.isLoopbackAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            if (first == 0 || first == 127) return true;
            if (first == 100 && second >= 64 && second <= 127) return true; // CGNAT
            if (first == 169 && second == 254) return true;
            if (first == 192 && second == 0) return true;
            if (first == 198 && (second == 18 || second == 19)) return true;
            if (first >= 224) return true;
        } else if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true; // unique-local fc00::/7
            if (bytes[0] == (byte) 0xff) return true;
            if (isIpv4Mapped(bytes)) {
                return true;
            }
        }
        return CLOUD_METADATA_ADDRESSES.contains(address.getHostAddress());
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    private static Set<String> normalizeHosts(Set<String> hosts) {
        if (hosts == null) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        hosts.forEach(host -> normalized.add(host == null ? "" : host.toLowerCase(Locale.ROOT)
                .replace("[", "").replace("]", "")));
        return Set.copyOf(normalized);
    }

    private static String lower(String value) {
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    @FunctionalInterface
    public interface DnsResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    public record PinnedEndpoint(URI uri, String host, List<InetAddress> addresses,
                                 InetAddress pinnedAddress) {
        public PinnedEndpoint {
            addresses = List.copyOf(addresses);
        }
    }
}
