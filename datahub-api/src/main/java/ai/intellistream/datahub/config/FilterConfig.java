// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.config.LimitsProperties;
import ai.intellistream.datahub.api.filters.CachingBodyFilter;
import ai.intellistream.datahub.api.filters.RequestBodySizeLimitFilter;
import ai.intellistream.datahub.api.filters.RequestLogFilter;
import ai.intellistream.datahub.api.filters.RequestStateCleanupFilter;
import ai.intellistream.datahub.api.services.IngestQuotaService;
import jakarta.servlet.Filter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

@Configuration
public class FilterConfig {

    @Bean
    public FilterRegistrationBean<Filter> requestStateCleanupFilter() {
        final FilterRegistrationBean<Filter> f = new FilterRegistrationBean<>(new RequestStateCleanupFilter());
        f.addUrlPatterns("/*");
        // Must be outermost so its finally runs after the security filter chain, which is where
        // OrganizationValidator sets TenantContext and the dataset permissions get memoised.
        f.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return f;
    }

    @Bean
    public FilterRegistrationBean<Filter> logFilter() {
        final FilterRegistrationBean<Filter> rlf = new FilterRegistrationBean<>(new RequestLogFilter());
        rlf.addUrlPatterns("/*");
        // Swap if you need to debug raw incoming http requests
        //rlf.setOrder(Ordered.HIGHEST_PRECEDENCE);
        rlf.setOrder(1);
        return rlf;
    }

    @Bean
    public FilterRegistrationBean<Filter> requestBodySizeLimitFilter(LimitsProperties limits,
                                                                     IngestQuotaService ingestQuota) {
        final FilterRegistrationBean<Filter> f =
                new FilterRegistrationBean<>(new RequestBodySizeLimitFilter(limits, ingestQuota));
        f.addUrlPatterns("/*");
        // Before the body cache (0), so an oversized body is refused rather than buffered; after the
        // security chain (-100), so an unauthenticated caller still gets 401 rather than a hint about
        // what the limits are.
        f.setOrder(-1);
        return f;
    }

    @Bean
    public FilterRegistrationBean<Filter> bodyCacheFilter(){
        final FilterRegistrationBean<Filter> cbf = new FilterRegistrationBean<>(new CachingBodyFilter());
        cbf.addUrlPatterns("/*");
        cbf.setOrder(0);
        return cbf;
    }

}
