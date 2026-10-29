package top.sywyar.pixivdownload.douyin.client.request;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import top.sywyar.pixivdownload.douyin.client.DouyinClientException;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Douyin 请求头凭证边界")
class DouyinRequestHeadersTest {

    @Test
    @DisplayName("普通请求头不携带 Cookie")
    void standardHeadersNeverContainCookie() {
        var headers = DouyinRequestHeaders.standard();

        assertThat(headers).doesNotContainKeys("Cookie", "uifid");
    }

    @Test
    @DisplayName("浏览器身份只进入抖音 Web API，不进入公开页面或媒体 origin")
    void browserIdentityStaysOnWebApi() throws Exception {
        String cookie = "sessionid=fixture; UIFID=browser-id";
        var headers = DouyinRequestHeaders.credentials(
                URI.create("https://www.douyin.com/aweme/v1/web/aweme/post/"), cookie);
        assertThat(headers.get("uifid")).containsExactly("browser-id");
        assertThat(headers.get("Cookie")).containsExactly(cookie);
        assertThat(DouyinRequestHeaders.credentials(URI.create("https://www.douyin.com/video/1"), cookie))
                .doesNotContainKey("uifid");
        assertThatThrownBy(() -> DouyinRequestHeaders.credentials(
                URI.create("https://cdn.douyin.com/media"), cookie)).isInstanceOf(DouyinClientException.class);
        assertThat(DouyinRequestHeaders.uifid(null)).isEmpty();
        assertThat(DouyinRequestHeaders.uifid("UIFID=; sessionid=fixture")).isEmpty();
    }

    @Test
    @DisplayName("完整 Cookie 仅发送到明确的 HTTPS Douyin 凭证 origin")
    void credentialsRequireExplicitHttpsOrigin() throws Exception {
        var headers = DouyinRequestHeaders.credentials(URI.create("https://www.douyin.com/aweme/v1/web/"),
                "sessionid=fixture-credential-7f4c2a91");
        assertThat(headers.get("Cookie")).containsExactly("sessionid=fixture-credential-7f4c2a91");

        assertThatThrownBy(() -> DouyinRequestHeaders.credentials(
                URI.create("https://cdn.douyin.com/media"), "sessionid=fixture-credential-7f4c2a91"))
                .isInstanceOf(DouyinClientException.class);
        assertThatThrownBy(() -> DouyinRequestHeaders.credentials(
                URI.create("http://www.douyin.com/aweme/v1/web/"), "sessionid=fixture-credential-7f4c2a91"))
                .isInstanceOf(DouyinClientException.class);
    }
}
