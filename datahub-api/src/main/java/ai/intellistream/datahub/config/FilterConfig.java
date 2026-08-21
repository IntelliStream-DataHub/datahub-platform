// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.config;

import ai.intellistream.datahub.api.filters.CachingBodyFilter;
import ai.intellistream.datahub.api.filters.RequestLogFilter;
import ai.intellistream.datahub.api.filters.RequestStateCleanupFilter;
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
    public FilterRegistrationBean<Filter> bodyCacheFilter(){
        final FilterRegistrationBean<Filter> cbf = new FilterRegistrationBean<>(new CachingBodyFilter());
        cbf.addUrlPatterns("/*");
        cbf.setOrder(0);
        return cbf;
    }

}
