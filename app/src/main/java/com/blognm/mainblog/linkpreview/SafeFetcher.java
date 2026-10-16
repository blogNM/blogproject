package com.blognm.mainblog.linkpreview;

import java.io.IOException;
import java.io.InputStream;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Optional;

import org.springframework.stereotype.Component;

/**
 * 바깥 사이트에서 미리보기 정보를 받는다 (D-92).
 * 서버가 대신 요청하는 기능이라 내부망을 엿보는 데 쓰이지 않게(SSRF) 막는다:
 * http·https와 기본 포트만, 주소를 미리 풀어 내부·루프백·링크 로컬·사설 IP면 거절, 넘겨주기(redirect)도 매번 다시 확인,
 * 받는 크기 제한. 쿠키·로그인 정보는 보내지 않는다.
 */
@Component
public class SafeFetcher {

    private static final int MAX_REDIRECTS = 3;
    private static final String USER_AGENT = "Mozilla/5.0 (compatible; CreatLinkPreview/1.0)";

    private final LinkPreviewProperties props;
    private final HttpClient client;

    public SafeFetcher(LinkPreviewProperties props) {
        this.props = props;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(props.timeout())
                .build();
    }

    /** 성공(2xx)이면 본문 앞부분 maxBytes까지. 막힌 주소·실패·너무 큰 응답은 빈 값. */
    public Optional<Fetched> get(String url, int maxBytes) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
        for (int i = 0; i <= MAX_REDIRECTS; i++) {
            if (!allowed(uri)) {
                return Optional.empty();
            }
            try {
                HttpRequest req = HttpRequest.newBuilder(uri).timeout(props.timeout()).header("User-Agent", USER_AGENT)
                        .header("Accept", "text/html,application/json,image/*;q=0.8")
                        .header("Accept-Language", "ko,en;q=0.8").GET().build();
                HttpResponse<InputStream> res = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                int status = res.statusCode();
                if (status >= 300 && status < 400) {
                    res.body().close();
                    Optional<String> location = res.headers().firstValue("Location");
                    if (location.isEmpty()) {
                        return Optional.empty();
                    }
                    uri = uri.resolve(location.get());
                    continue;
                }
                try (InputStream body = res.body()) {
                    if (status < 200 || status >= 300) {
                        return Optional.empty();
                    }
                    byte[] bytes = body.readNBytes(maxBytes + 1);
                    String type = res.headers().firstValue("Content-Type").orElse("");
                    return Optional.of(new Fetched(uri.toString(), type, bytes, bytes.length > maxBytes));
                }
            } catch (IOException | IllegalArgumentException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    boolean allowed(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }
        if (uri.getHost() == null || uri.getUserInfo() != null) {
            return false;
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443 && !props.allowPrivateHosts()) {
            return false;
        }
        if (props.allowPrivateHosts()) {
            return true;
        }
        try {
            for (InetAddress address : InetAddress.getAllByName(uri.getHost())) {
                if (isPrivate(address)) {
                    return false;
                }
            }
            return true;
        } catch (UnknownHostException e) {
            return false;
        }
    }

    static boolean isPrivate(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress()
                || a.isMulticastAddress()) {
            return true;
        }
        byte[] b = a.getAddress();
        if (a instanceof Inet6Address) {
            // fc00::/7 고유 로컬, IPv4를 품은 주소(::ffff:10.0.0.1 등)는 거절
            // NAT64(64:ff9b::/96)·6to4(2002::/16)는 안에 든 IPv4 주소로 다시 판단한다 (D-127)
            if ((b[0] & 0xFE) == 0xFC || isMappedV4(b)) {
                return true;
            }
            if (isNat64(b)) {
                return isPrivate(v4(b, 12));
            }
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
                return isPrivate(v4(b, 2));
            }
            return false;
        }
        int first = b[0] & 0xFF;
        int second = b[1] & 0xFF;
        return first == 0 || first >= 224 || (first == 100 && second >= 64 && second <= 127) // 100.64/10
                || (first == 192 && second == 0 && (b[2] & 0xFF) == 0) || (first == 198 && (second == 18 || second == 19));
    }

    private static boolean isNat64(byte[] b) {
        if ((b[0] & 0xFF) != 0x00 || (b[1] & 0xFF) != 0x64 || (b[2] & 0xFF) != 0xFF || (b[3] & 0xFF) != 0x9B) {
            return false;
        }
        for (int i = 4; i < 12; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static InetAddress v4(byte[] b, int from) {
        try {
            return InetAddress.getByAddress(new byte[] {b[from], b[from + 1], b[from + 2], b[from + 3]});
        } catch (UnknownHostException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean isMappedV4(byte[] b) {
        for (int i = 0; i < 10; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    /** truncated면 maxBytes보다 커서 잘린 것. */
    public record Fetched(String finalUrl, String contentType, byte[] body, boolean truncated) {
    }
}
