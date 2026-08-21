// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.api.error;

import feign.Response;
import feign.codec.ErrorDecoder;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Scanner;

public class DataHubErrorDecoder implements ErrorDecoder {

    private final ErrorDecoder defaultErrorDecoder = new Default();

    @Override
    public Exception decode(String methodKey, Response response) {
        try {
            if(response.body() == null) return defaultErrorDecoder.decode(methodKey, response);

            // Assuming the response body contains the error details in JSON format
            String json = convert(response.body().asInputStream());
            return new ConflictException(json, response.status());
        } catch (IOException e) {
            return new RuntimeException("Failed to process response body", e);
        }
        //return defaultErrorDecoder.decode(methodKey, response);
    }

    public static String convert(InputStream inputStream) {
        try (Scanner scanner = new Scanner(inputStream, StandardCharsets.UTF_8)) {
            return scanner.useDelimiter("\\A").next();
        }
    }
}
