package ro.bitboy.mrr.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.time.Duration;

/**
 * Cache headers for the Next export, which the defaults get backwards.
 *
 * <p>Everything under {@code /_next/static/} carries a content hash in its filename: a changed
 * file is a changed name, so those may be cached forever and {@code immutable} spares the
 * browser even a revalidation. {@code index.html} is the opposite — it is the map from stable
 * URLs to those hashed names, so caching it pins a browser to the previous deploy's chunks.
 * Everything but the hashed assets is therefore {@code no-cache}, which means revalidate, not
 * "do not store": the browser still keeps the file and still gets a 304 when it has not moved.
 *
 * <p>Served with no directive at all — which is what Spring does by default — a browser applies
 * heuristic freshness instead and may reuse {@code index.html} for hours without asking. A
 * cached map pointing at cached chunks is a whole stale application, and a stale UI against an
 * upgraded API fails in ways that look like bugs in the API.
 *
 * <p>{@code no-cache} on the map is also what makes an explicit origin directive worth setting
 * rather than leaving to the CDN in front: Cloudflare honours it, so a deploy is visible on the
 * next load without anyone knowing to hard-reload.
 */
@Configuration
public class StaticCacheConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // More specific than the "/**" mapping Spring Boot registers, so it wins the match.
        registry.addResourceHandler("/_next/static/**")
                .addResourceLocations("classpath:/static/_next/static/")
                .setCacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePublic().immutable());
    }
}
