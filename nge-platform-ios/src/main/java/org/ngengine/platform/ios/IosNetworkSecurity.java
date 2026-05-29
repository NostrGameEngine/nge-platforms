/**
 * BSD 3-Clause License
 *
 * Copyright (c) 2025, Riccardo Balbo
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.ngengine.platform.ios;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.ngengine.platform.NGEUtils;

final class IosNetworkSecurity {

    private static final List<String> HTTP_SCHEMES = List.of("http", "https");
    private static final List<String> WEBSOCKET_SCHEMES = List.of("ws", "wss");
    private static final boolean ALLOW_LOOPBACKS = Boolean.getBoolean("nge-platforms.allowLoopbackInURIs");
    private static final boolean ALLOW_PRIVATE_NETWORKS = Boolean.getBoolean("nge-platforms.allowPrivateNetworkInURIs");

    private IosNetworkSecurity() {}

    static URI safeHttpUri(String url) {
        return validateUri(url, HTTP_SCHEMES, "HTTP");
    }

    static URI safeWebSocketUri(String url) {
        return validateUri(url, WEBSOCKET_SCHEMES, "WebSocket");
    }

    static URI safeRedirectUri(URI previousUri, String location) {
        URI redirectUri = previousUri.resolve(location);
        return validateUri(redirectUri, HTTP_SCHEMES, "HTTP redirect");
    }

    private static URI validateUri(Object input, List<String> allowedSchemes, String purpose) {
        URI uri = NGEUtils.safeURI(input);
        String scheme = uri.getScheme().toLowerCase();
        if (!allowedSchemes.contains(scheme)) {
            throw new IllegalArgumentException(purpose + " URI scheme is not allowed: " + uri.getScheme());
        }
        validateHost(uri, purpose);
        return uri;
    }

    private static void validateHost(URI uri, String purpose) {
        if (ALLOW_PRIVATE_NETWORKS) {
            return;
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(uri.getHost());
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Unable to resolve " + purpose + " URI host: " + uri.getHost(), e);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException(
                    purpose + " URI host resolves to a private or local address: " + uri.getHost()
                );
            }
        }
    }

    static boolean isPrivateOrLocalAddress(InetAddress address) {
        return isLoopbackOrAnyLocal(address) || isPrivateNetworkAddress(address);
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (isLoopbackOrAnyLocal(address)) {
            return !ALLOW_LOOPBACKS;
        }
        return isPrivateNetworkAddress(address) && !ALLOW_PRIVATE_NETWORKS;
    }

    private static boolean isLoopbackOrAnyLocal(InetAddress address) {
        return address.isAnyLocalAddress() || address.isLoopbackAddress();
    }

    private static boolean isPrivateNetworkAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            int first = bytes[0] & 0xff;
            int second = bytes[1] & 0xff;
            return (
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isMulticastAddress() ||
                (first == 100 && second >= 64 && second <= 127)
            );
        }
        if (bytes.length == 16) {
            int first = bytes[0] & 0xff;
            return (
                address.isLinkLocalAddress() ||
                address.isSiteLocalAddress() ||
                address.isMulticastAddress() ||
                (first & 0xfe) == 0xfc
            );
        }
        return address.isLinkLocalAddress() || address.isSiteLocalAddress() || address.isMulticastAddress();
    }
}
