package io.github.bangshou1st.modelbridge.core.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.bangshou1st.modelbridge.core.security.OutboundEndpointPolicy;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublicOnlyDnsResolverTest {

    @Test
    void rejectsADnsAnswerThatChangesToPrivateAtConnectTime() throws Exception {
        var calls = new AtomicInteger();
        var policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(), host ->
                new InetAddress[]{InetAddress.getByName(
                        calls.getAndIncrement() == 0 ? "93.184.216.34" : "10.0.0.7")});
        var resolver = new PublicOnlyDnsResolver(policy);

        assertThat(resolver.resolve("api.example.com"))
                .extracting(InetAddress::getHostAddress)
                .containsExactly("93.184.216.34");
        assertThatThrownBy(() -> resolver.resolve("api.example.com"))
                .isInstanceOf(UnknownHostException.class);
    }

    @Test
    void rejectsLoopbackAndMetadataAtConnectTime() {
        for (var address : new String[]{"127.0.0.1", "169.254.169.254", "10.1.2.3"}) {
            var policy = new OutboundEndpointPolicy(false, Set.of(), Set.of(), host -> {
                try {
                    return new InetAddress[]{InetAddress.getByName(address)};
                } catch (UnknownHostException failure) {
                    throw new AssertionError(failure);
                }
            });

            assertThatThrownBy(() -> new PublicOnlyDnsResolver(policy)
                    .resolve("provider.example"))
                    .as(address)
                    .isInstanceOf(UnknownHostException.class);
        }
    }
}
