// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.datahub.helpers.spring.EnvUtils;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class AppConfig implements WebMvcConfigurer {

    public AppConfig(Environment environment) {
        EnvUtils.setEnvironment(environment);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addViewController("/error/no-organization").setViewName("error/no-organization");
        // The timeseries "Analyze" tab is a pure template-serve: the page (timeseries/analyze.html)
        // fetches the timeseries list and runs the relationship analysis client-side against the
        // datahub-api directly, so no backend-for-frontend controller logic is needed here.
        registry.addViewController("/timeseries/analyze").setViewName("timeseries/analyze");
    }

}
