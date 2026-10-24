package com.example.demo.client;

import com.example.demo.dto.mlcore.MlCoreApi;
import com.example.demo.exception.MlCoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;
import java.util.function.Supplier;

/**
 * HTTP client of the "Cashflow ML Core" server (forecast stub, scenario chat, table/receipt import).
 * Every call has connect and read timeouts and fails with {@link MlCoreException} - never hangs, never
 * throws anything else, so callers can degrade gracefully.
 * <p>
 * Two read timeouts: a short one for health/forecast and a long one for calls that run an LLM (imports).
 */
@Component
public class MlCoreClient {

    private static final Logger log = LoggerFactory.getLogger(MlCoreClient.class);

    private final boolean enabled;
    private final String baseUrl;
    private final RestClient fast;
    private final RestClient slow;

    @Autowired
    public MlCoreClient(@Value("${ml.core.enabled:true}") boolean enabled,
                        @Value("${ml.core.url:http://192.168.31.206:18081}") String baseUrl,
                        @Value("${ml.core.connect-timeout-ms:2000}") int connectTimeoutMs,
                        @Value("${ml.core.read-timeout-ms:15000}") int readTimeoutMs,
                        @Value("${ml.core.slow-read-timeout-ms:90000}") int slowReadTimeoutMs) {
        this(enabled, baseUrl,
                RestClient.builder().baseUrl(trimSlash(baseUrl))
                        .requestFactory(factory(connectTimeoutMs, readTimeoutMs)).build(),
                RestClient.builder().baseUrl(trimSlash(baseUrl))
                        .requestFactory(factory(connectTimeoutMs, slowReadTimeoutMs)).build());
    }

    /** For tests: pre-built clients (e.g. bound to MockRestServiceServer). */
    public MlCoreClient(boolean enabled, String baseUrl, RestClient fast, RestClient slow) {
        this.enabled = enabled;
        this.baseUrl = trimSlash(baseUrl);
        this.fast = fast;
        this.slow = slow;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public Map<String, String> live() {
        return get("/health/live");
    }

    public Map<String, String> ready() {
        return get("/health/ready");
    }

    public MlCoreApi.ForecastResult forecast(MlCoreApi.ForecastRequest request) {
        MlCoreApi.ForecastResult result = call("forecast", () -> fast.post().uri("/v1/forecasts")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MlCoreApi.ForecastResult.class));
        if (result == null || result.days() == null) {
            throw new MlCoreException("ML Core returned an empty forecast", 0, null, null);
        }
        return result;
    }

    /** Scenario chat. Runs an LLM on the server, so it uses the long read timeout. */
    public MlCoreApi.ChatResponse chat(MlCoreApi.ChatRequest request) {
        MlCoreApi.ChatResponse result = call("chat", () -> slow.post().uri("/v1/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MlCoreApi.ChatResponse.class));
        if (result == null || result.answer() == null || result.answer().isBlank()) {
            throw new MlCoreException("ML Core returned an empty chat answer", 0, null, null);
        }
        return result;
    }

    public MlCoreApi.TableImportResponse importTable(String filename, byte[] content, String contentType,
                                                     String companyCode, String sheetName) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", filePart(filename, content, contentType));
        if (companyCode != null && !companyCode.isBlank()) {
            form.add("company_id", companyCode);
        }
        if (sheetName != null && !sheetName.isBlank()) {
            form.add("sheet_name", sheetName);
        }
        MlCoreApi.TableImportResponse result = call("import table", () -> slow.post().uri("/v1/imports/table")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(MlCoreApi.TableImportResponse.class));
        if (result == null || result.rows() == null) {
            throw new MlCoreException("ML Core returned an empty import result", 0, null, null);
        }
        return result;
    }

    public MlCoreApi.ReceiptResponse importReceipt(String filename, byte[] content, String contentType,
                                                   String companyCode) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", filePart(filename, content, contentType));
        if (companyCode != null && !companyCode.isBlank()) {
            form.add("company_id", companyCode);
        }
        MlCoreApi.ReceiptResponse result = call("import receipt", () -> slow.post().uri("/v1/imports/receipt")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(form)
                .retrieve()
                .body(MlCoreApi.ReceiptResponse.class));
        if (result == null || result.row() == null) {
            throw new MlCoreException("ML Core returned an empty receipt result", 0, null, null);
        }
        return result;
    }

    // ------------------------------------------------------------------ internals

    @SuppressWarnings("unchecked")
    private Map<String, String> get(String path) {
        Map<String, String> body = call("GET " + path, () -> fast.get().uri(path).retrieve().body(Map.class));
        return body == null ? Map.of() : body;
    }

    private <T> T call(String what, Supplier<T> action) {
        if (!enabled) {
            throw new MlCoreException("ML Core integration is disabled (ml.core.enabled=false)", 0, null, null);
        }
        try {
            return action.get();
        } catch (RestClientResponseException e) {
            log.warn("ML Core {} failed: HTTP {}", what, e.getStatusCode().value());
            throw new MlCoreException("ML Core answered HTTP " + e.getStatusCode().value() + " on " + what,
                    e.getStatusCode().value(), e.getResponseBodyAsString(), e);
        } catch (RestClientException e) {
            log.warn("ML Core {} unavailable: {}", what, e.getMessage());
            throw new MlCoreException("ML Core is unavailable: " + e.getMessage(), 0, null, e);
        }
    }

    private static HttpEntity<ByteArrayResource> filePart(String filename, byte[] content, String contentType) {
        String name = filename == null || filename.isBlank() ? "upload" : filename;
        ByteArrayResource resource = new ByteArrayResource(content == null ? new byte[0] : content) {
            @Override
            public String getFilename() {
                return name;
            }
        };
        MediaType type = MediaType.APPLICATION_OCTET_STREAM;
        if (contentType != null && !contentType.isBlank()) {
            try {
                type = MediaType.parseMediaType(contentType);
            } catch (IllegalArgumentException ignored) {
                // keep octet-stream
            }
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        return new HttpEntity<>(resource, headers);
    }

    private static SimpleClientHttpRequestFactory factory(int connectTimeoutMs, int readTimeoutMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeoutMs);
        factory.setReadTimeout(readTimeoutMs);
        return factory;
    }

    private static String trimSlash(String url) {
        if (url == null) {
            return "";
        }
        String u = url.trim();
        return u.endsWith("/") ? u.substring(0, u.length() - 1) : u;
    }
}
