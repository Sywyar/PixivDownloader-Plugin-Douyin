package top.sywyar.pixivdownload.douyin.client.request;

import top.sywyar.pixivdownload.douyin.client.DouyinClientErrorCode;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class DouyinRequestHeaders {

    public static final String REFERER = "https://www.douyin.com/?recommend=1";
    public static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/139.0.0.0 Safari/537.36";

    private static final Set<String> CREDENTIAL_HOSTS = Set.of(
            "douyin.com", "www.douyin.com", "v.douyin.com",
            "iesdouyin.com", "www.iesdouyin.com", "v.iesdouyin.com");

    private DouyinRequestHeaders() {
    }

    public static Map<String, List<String>> standard() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("User-Agent", List.of(USER_AGENT));
        headers.put("Referer", List.of(REFERER));
        headers.put("Accept", List.of("*/*"));
        headers.put("Accept-Encoding", List.of("gzip, deflate"));
        headers.put("Accept-Language", List.of("zh-CN,zh;q=0.9,en-US;q=0.8,en;q=0.7"));
        return Map.copyOf(headers);
    }

    public static Map<String, List<String>> credentials(URI target, String cookie)
            throws DouyinClientException {
        Map<String, List<String>> headers = new LinkedHashMap<>(standard());
        if (cookie == null || cookie.isBlank()) {
            return Map.copyOf(headers);
        }
        if (!isCredentialOrigin(target)) {
            throw new DouyinClientException(DouyinClientErrorCode.INVALID_URL,
                    "Douyin credentials are not allowed for target origin: host=" + safeHost(target));
        }
        headers.put("Cookie", List.of(cookie));
        String uifid = uifid(cookie);
        if (!uifid.isEmpty() && "www.douyin.com".equalsIgnoreCase(target.getHost())
                && target.getPath().startsWith("/aweme/v1/web/")) {
            headers.put("uifid", List.of(uifid));
        }
        return Map.copyOf(headers);
    }

    /** 读取浏览器已签发的身份值；缺失时保持为空，不生成替代值。 */
    public static String uifid(String cookie) {
        if (cookie != null) {
            for (String part : cookie.split(";")) {
                int equals = part.indexOf('=');
                if (equals > 0 && "UIFID".equalsIgnoreCase(part.substring(0, equals).trim())) {
                    return part.substring(equals + 1).trim();
                }
            }
        }
        return "";
    }

    public static boolean isCredentialOrigin(URI target) {
        if (target == null || !"https".equalsIgnoreCase(target.getScheme()) || target.getUserInfo() != null) {
            return false;
        }
        int port = target.getPort();
        String host = target.getHost() == null ? "" : target.getHost().toLowerCase(Locale.ROOT);
        return (port == -1 || port == 443) && CREDENTIAL_HOSTS.contains(host);
    }

    private static String safeHost(URI target) {
        return target == null || target.getHost() == null ? "<none>" : target.getHost().toLowerCase(Locale.ROOT);
    }
}
