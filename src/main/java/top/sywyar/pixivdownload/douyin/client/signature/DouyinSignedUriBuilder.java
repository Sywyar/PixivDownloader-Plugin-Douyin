package top.sywyar.pixivdownload.douyin.client.signature;

import top.sywyar.pixivdownload.douyin.client.api.DouyinApiUriBuilder;
import top.sywyar.pixivdownload.douyin.client.request.DouyinRequestHeaders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.LongSupplier;

/** 使用登录 Cookie 的 UIFID 为最终 Web API 查询生成 secsdk 签名。 */
public class DouyinSignedUriBuilder {

    private static final String WEB_SIGN_SALT = "A96D855A08C0A9707F8BEF0D9A527E4E";
    private final DouyinApiUriBuilder apiUriBuilder = new DouyinApiUriBuilder();
    private final LongSupplier epochSeconds;

    public DouyinSignedUriBuilder() {
        this(() -> Instant.now().getEpochSecond());
    }

    DouyinSignedUriBuilder(LongSupplier epochSeconds) {
        this.epochSeconds = epochSeconds;
    }

    public SignedRequest request(String path, Map<String, ?> endpointParams, String cookie) {
        String requestCookie = requestCookie(cookie);
        String uifid = DouyinRequestHeaders.uifid(requestCookie);
        if (uifid.isEmpty()) {
            // 不伪造浏览器凭据；公共端点仍可请求，受保护端点由服务端明确拒绝。
            return unsignedRequest(path, endpointParams, requestCookie);
        }
        Map<String, Object> parameters = new LinkedHashMap<>();
        if (endpointParams != null) {
            parameters.putAll(endpointParams);
        }
        parameters.remove("x-secsdk-web-signature");
        parameters.put("uifid", uifid);
        long timestamp = epochSeconds.getAsLong();
        parameters.put("timestamp", timestamp);
        URI unsigned = apiUriBuilder.api(path, parameters, requestCookie);
        String query = unsigned.getRawQuery();
        String signature;
        try {
            byte[] input = (uifid + "_" + timestamp + "_" + WEB_SIGN_SALT + "_" + query)
                    .getBytes(StandardCharsets.UTF_8);
            signature = HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(input));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 is required by the Douyin Web API protocol", e);
        }
        return new SignedRequest(URI.create(unsigned.toASCIIString()
                + "&x-secsdk-web-signature=" + signature), requestCookie);
    }

    public SignedRequest unsignedRequest(String path, Map<String, ?> endpointParams, String cookie) {
        String requestCookie = requestCookie(cookie);
        return new SignedRequest(apiUriBuilder.api(path, endpointParams, requestCookie), requestCookie);
    }

    private String requestCookie(String cookie) {
        String normalized = cookie == null ? "" : cookie.trim();
        var existing = DouyinMsToken.fromCookie(normalized);
        return existing.isPresent()
                ? DouyinMsToken.withToken(normalized, existing.get())
                : normalized;
    }

    public record SignedRequest(URI uri, String cookie) {
        public SignedRequest {
            cookie = cookie == null ? "" : cookie;
        }
    }
}
