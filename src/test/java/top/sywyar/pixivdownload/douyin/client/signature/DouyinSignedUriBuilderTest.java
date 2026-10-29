package top.sywyar.pixivdownload.douyin.client.signature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("抖音 Web 请求签名")
class DouyinSignedUriBuilderTest {
    private final DouyinSignedUriBuilder builder = new DouyinSignedUriBuilder(() -> 1700000000L);

    @Test
    @DisplayName("签名覆盖最终查询并与固定协议向量一致")
    void signsFinalQuery() {
        var request = builder.request("/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"),
                "UIFID=browser-id; msToken=real-token");
        assertThat(request.uri().getRawQuery()).isEqualTo(
                "device_platform=webapp&aid=6383&version_code=290100&version_name=29.1.0"
                + "&msToken=real-token&aweme_id=7351&uifid=browser-id&timestamp=1700000000"
                + "&x-secsdk-web-signature=458dec6601672c9a2b1a28840862c988");
        assertThat(request.cookie()).isEqualTo("UIFID=browser-id; msToken=real-token");
    }

    @Test
    @DisplayName("中文、空格与加号只编码一次，签名与线上查询保持一致")
    void signsEncodedUnicode() {
        var request = builder.request("/aweme/v1/web/aweme/post/", Map.of("keyword", "猫 图+花"),
                "UIFID=browser-id; msToken=real-token");
        assertThat(request.uri().getRawQuery()).contains("keyword=%E7%8C%AB+%E5%9B%BE%2B%E8%8A%B1")
                .endsWith("x-secsdk-web-signature=46a745c478f9c5f2b6423dec1ee1cc3b");
    }

    @Test
    @DisplayName("每次请求重新取得时间，账号切换不复用旧签名")
    void requestIdentityAndTimestampAreNotCached() {
        AtomicLong time = new AtomicLong(1700000000L);
        var signer = new DouyinSignedUriBuilder(time::get);
        var first = signer.request("/aweme/v1/web/aweme/post/", Map.of(), "UIFID=first");
        time.incrementAndGet();
        var second = signer.request("/aweme/v1/web/aweme/post/", Map.of(), "UIFID=second");
        assertThat(second.uri().getRawQuery()).contains("uifid=second&timestamp=1700000001")
                .doesNotContain("uifid=first").isNotEqualTo(first.uri().getRawQuery());
    }

    @Test
    @DisplayName("缺失浏览器身份时不伪造任何签名或凭据")
    void missingBrowserIdentityIsNotFabricated() {
        var request = builder.request("/aweme/v1/web/aweme/detail/", Map.of("aweme_id", "7351"),
                "sessionid=fixture; msToken=");
        assertThat(request.uri().getRawQuery()).contains("aweme_id=7351")
                .doesNotContain("uifid=", "timestamp=", "signature=", "msToken=", "a_bogus=", "X-Bogus=");
        assertThat(request.cookie()).isEqualTo("sessionid=fixture; msToken=");
    }

    @Test
    @DisplayName("无需签名的端点保持原查询与凭据")
    void unsignedEndpointRemainsUnsigned() {
        var request = builder.unsignedRequest("/aweme/v1/web/general/search/single/",
                Map.of("keyword", "猫 图"), "UIFID=browser-id; msToken=real-token");
        assertThat(request.uri().getRawQuery()).contains("keyword=%E7%8C%AB+%E5%9B%BE", "msToken=real-token")
                .doesNotContain("uifid=", "timestamp=", "signature=");
    }

    @Test
    @DisplayName("签名身份和时间以当前凭据为准，不接收调用方旧值")
    void replacesStaleSigningParameters() {
        var request = builder.request("/aweme/v1/web/aweme/post/",
                Map.of("uifid", "stale", "timestamp", 1, "x-secsdk-web-signature", "stale"),
                " UIFID=browser-id ");
        assertThat(request.uri().getRawQuery()).contains("uifid=browser-id", "timestamp=1700000000")
                .doesNotContain("stale");
        assertThat(request.uri().getRawQuery().split("x-secsdk-web-signature=", -1)).hasSize(2);
    }
}
