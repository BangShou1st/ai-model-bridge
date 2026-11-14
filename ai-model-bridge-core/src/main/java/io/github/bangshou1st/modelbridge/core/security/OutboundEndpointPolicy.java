package io.github.bangshou1st.modelbridge.core.security;

import java.net.Inet4Address;
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
 * SSRF boundary for model endpoints. DNS is resolved completely before a request and redirects are
 * disabled by the transport. Cloud metadata destinations can never be enabled by a policy mode.
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

    private final NetworkPolicyMode mode;
    private final boolean allowLoopback;
    private final Set<String> loopbackHosts;
    private final Set<Integer> loopbackPorts;
    private final DnsResolver dnsResolver;

    public OutboundEndpointPolicy() {
        this(NetworkPolicyMode.PUBLIC_HTTPS_ONLY, false, Set.of(), Set.of(),
                InetAddress::getAllByName);
    }

    public OutboundEndpointPolicy(boolean allowLoopback, Set<String> loopbackHosts,
                                  Set<Integer> loopbackPorts) {
        this(allowLoopback ? NetworkPolicyMode.ALLOW_LOOPBACK
                        : NetworkPolicyMode.PUBLIC_HTTPS_ONLY,
                allowLoopback, loopbackHosts, loopbackPorts, InetAddress::getAllByName);
    }

    public OutboundEndpointPolicy(boolean allowLoopback, Set<String> loopbackHosts,
                                  Set<Integer> loopbackPorts, DnsResolver dnsResolver) {
        this(allowLoopback ? NetworkPolicyMode.ALLOW_LOOPBACK
                        : NetworkPolicyMode.PUBLIC_HTTPS_ONLY,
                allowLoopback, loopbackHosts, loopbackPorts, dnsResolver);
    }

    public OutboundEndpointPolicy(NetworkPolicyMode mode, boolean allowLoopback,
                                  Set<String> loopbackHosts, Set<Integer> loopbackPorts) {
        this(mode, allowLoopback, loopbackHosts, loopbackPorts, InetAddress::getAllByName);
    }

    public OutboundEndpointPolicy(NetworkPolicyMode mode, boolean allowLoopback,
                                  Set<String> loopbackHosts, Set<Integer> loopbackPorts,
                                  DnsResolver dnsResolver) {
        this.mode = mode == null ? NetworkPolicyMode.PUBLIC_HTTPS_ONLY : mode;
        this.allowLoopback = allowLoopback;
        this.loopbackHosts = normalizeHosts(loopbackHosts);
        this.loopbackPorts = loopbackPorts == null ? Set.of() : Set.copyOf(loopbackPorts);
        this.dnsResolver = Objects.requireNonNull(dnsResolver, "dnsResolver");
    }

    public static OutboundEndpointPolicy httpsOnly() {
        return new OutboundEndpointPolicy();
    }

    public static OutboundEndpointPolicy localDevelopment(Set<Integer> ports) {
        return new OutboundEndpointPolicy(NetworkPolicyMode.ALLOW_LOOPBACK, true,
                Set.of("127.0.0.1", "::1", "localhost"), ports);
    }

    public static OutboundEndpointPolicy privateNetwork(boolean allowLoopback,
                                                        Set<String> loopbackHosts,
                                                        Set<Integer> loopbackPorts) {
        return new OutboundEndpointPolicy(NetworkPolicyMode.ALLOW_PRIVATE_NETWORK, allowLoopback,
                loopbackHosts, loopbackPorts);
    }

    public NetworkPolicyMode mode() {
        return mode;
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
        String host = normalizeHost(lower(uri.getHost()));
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

        // Metadata protection is checked before any mode-specific exemption.
        for (InetAddress address : addresses) {
            if (isCloudMetadata(address)) {
                throw ModelBridgeException.networkPolicy("cloud metadata endpoint is blocked");
            }
        }

        boolean anyLoopback = addresses.stream().anyMatch(InetAddress::isLoopbackAddress);
        boolean allLoopback = addresses.stream().allMatch(InetAddress::isLoopbackAddress);
        boolean loopbackRequest = "localhost".equals(host) || allLoopback;
        if (anyLoopback && !allLoopback) {
            throw ModelBridgeException.networkPolicy("mixed public and loopback DNS answers are blocked");
        }

        if (loopbackRequest) {
            if (!allLoopback) {
                throw ModelBridgeException.networkPolicy(
                        "loopback host did not resolve exclusively to loopback addresses");
            }
            if (mode == NetworkPolicyMode.PUBLIC_HTTPS_ONLY || !allowLoopback
                    || !loopbackHosts.contains(host)
                    || (!loopbackPorts.isEmpty() && !loopbackPorts.contains(effectivePort(uri)))) {
                throw ModelBridgeException.networkPolicy("loopback endpoint is not whitelisted");
            }
        } else {
            boolean anyPrivate = addresses.stream().anyMatch(OutboundEndpointPolicy::isPrivate);
            if (mode == NetworkPolicyMode.PUBLIC_HTTPS_ONLY && anyPrivate) {
                throw ModelBridgeException.networkPolicy(
                        "private, link-local or metadata address is blocked");
            }
            if (mode == NetworkPolicyMode.ALLOW_LOOPBACK && anyPrivate) {
                throw ModelBridgeException.networkPolicy(
                        "private network endpoints require ALLOW_PRIVATE_NETWORK mode");
            }
            if ("http".equals(scheme) && mode != NetworkPolicyMode.ALLOW_PRIVATE_NETWORK) {
                throw ModelBridgeException.networkPolicy("public model endpoints must use HTTPS");
            }
            if ("http".equals(scheme) && addresses.stream().anyMatch(
                    address -> !isPrivate(address))) {
                throw ModelBridgeException.networkPolicy(
                        "insecure HTTP is only allowed for private network endpoints");
            }
        }

        List<InetAddress> pinned = new ArrayList<>(addresses);
        return new PinnedEndpoint(uri, host, pinned, pinned.getFirst());
    }

    public PinnedEndpoint validate(String url) {
        try {
            return validate(URI.create(url));
        } catch (IllegalArgumentException e) {
            throw ModelBridgeException.networkPolicy("invalid model endpoint URL");
        }
    }

    private static boolean isPrivate(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
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
            return false;
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xff;
            if ((first & 0xfe) == 0xfc) return true; // unique-local fc00::/7
            if (bytes[0] == (byte) 0xff) return true;
            if (isIpv4Mapped(bytes)) {
                try {
                    return isPrivate(InetAddress.getByAddress(
                            Arrays.copyOfRange(bytes, 12, 16)));
                } catch (UnknownHostException ignored) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isCloudMetadata(InetAddress address) {
        String value = address.getHostAddress().toLowerCase(Locale.ROOT);
        if (CLOUD_METADATA_ADDRESSES.contains(value)) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 16 && isIpv4Mapped(bytes)) {
            try {
                return CLOUD_METADATA_ADDRESSES.contains(
                        InetAddress.getByAddress(Arrays.copyOfRange(bytes, 12, 16))
                                .getHostAddress());
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        return false;
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
        hosts.forEach(host -> normalized.add(normalizeHost(host)));
        return Set.copyOf(normalized);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return "";
        }
        String value = host.toLowerCase(Locale.ROOT).replace("[", "").replace("]", "");
        return value.endsWith(".") ? value.substring(0, value.length() - 1) : value;
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
