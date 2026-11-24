package de.tstieh.stoneintelligence.platform.files;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DownloadContentTypesTest {

    @Test
    void should_keepSafeTypes() {
        assertThat(DownloadContentTypes.safe("application/pdf")).isEqualTo("application/pdf");
        assertThat(DownloadContentTypes.safe("image/png")).isEqualTo("image/png");
        assertThat(DownloadContentTypes.safe("IMAGE/JPEG")).isEqualTo("image/jpeg");
    }

    @Test
    void should_fallBackToOctetStream_forActiveOrUnknownTypes() {
        for (var type : new String[] {"text/html", "image/svg+xml", "application/xhtml+xml", "text/xml", "application/javascript",
                "application/x-shockwave-flash", "", null, "foo/bar"}) {
            assertThat(DownloadContentTypes.safe(type)).as(String.valueOf(type)).isEqualTo("application/octet-stream");
        }
    }
}
