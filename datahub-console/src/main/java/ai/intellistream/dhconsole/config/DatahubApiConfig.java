// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.config;

import ai.intellistream.dhconsole.api.DatahubApi;
import ai.intellistream.dhconsole.api.error.DataHubErrorDecoder;
import ai.intellistream.dhconsole.helpers.Jackson3Decoder;
import ai.intellistream.dhconsole.helpers.Jackson3Encoder;
import ai.intellistream.dhconsole.security.AccessTokens;
import feign.Feign;
import feign.Request;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

@Configuration
@Slf4j
public class DatahubApiConfig {

    @Value("${datahub.url:http://localhost:8081}")
    private String baseUrl;

    @Bean(name = "datahubApi")
    DatahubApi createDatahubApiService(
            AccessTokens accessTokens,
            JsonMapper jsonMapper
    ){

        Supplier<String> keySupplier = accessTokens::bearer;

        Request.Options connectionConfig =
                new Request.Options(90L, TimeUnit.SECONDS, 90L, TimeUnit.SECONDS, false);

        log.debug("Setup Datahub Client bean...");
        log.debug("Datahub url : " + baseUrl);

        try {
            return Feign.builder()
                    .encoder(new Jackson3Encoder(jsonMapper))
                    .decoder(new Jackson3Decoder(jsonMapper))
                    .errorDecoder(new DataHubErrorDecoder())
                    .options(connectionConfig)
                    .requestInterceptor(template -> template.header("Authorization", keySupplier.get()))
                    .target(DatahubApi.class, baseUrl);
        }
        catch(Exception e){
            log.error(e.getMessage(), e);
        }
        return null;
    }
}
