package io.github.bangshou1st.modelbridge.core.http;

import io.github.bangshou1st.modelbridge.core.security.ModelBridgeException;
import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Objects;
import org.apache.hc.client5.http.DnsResolver;

/**
 * Connection-time DNS guard used by the pinned Apache transport.
 *
 * <p>The pre-flight policy check and this resolver are intentionally separate:
 * the resolver runs when Apache is about to open the socket, so a DNS answer
 * that changes from public to private between validation and connect is still
 * rejected before a payload is sent.
 */
final class PublicOnlyDnsResolver implements DnsResolver {

    private final OutboundEndpointPolicy policy;

    PublicOnlyDnsResolver(OutboundEndpointPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public InetAddress[] resolve(String host) throws UnknownHostException {
        try {
            return policy.validate(uriFor(host)).addresses().toArray(InetAddress[]::new);
        } catch (ModelBridgeException failure) {
            var rejected = new UnknownHostException("Provider DNS answer was rejected");
            rejected.initCause(failure);
            throw rejected;
        }
    }

    @Override
    public String resolveCanonicalHostname(String host) throws UnknownHostException {
        var addresses = resolve(host);
        return addresses.length == 0 ? host : addresses[0].getCanonicalHostName();
    }

    private static String uriFor(String host) {
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("host is missing");
        }
        var literal = host.contains(":") && !host.startsWith("[") ? "[" + host + "]" : host;
        return "https://" + literal + "/";
    }
}
