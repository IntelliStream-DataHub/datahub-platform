// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.datahub.helpers.utils;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

@Component
public class HttpHelper {

    public String getRequestPath(HttpServletRequest req, String splitOn){
        String requestURL = req.getRequestURL().toString();
        String foundPath = null;
        if( !requestURL.equals(splitOn) && !requestURL.equals(splitOn +"/") ){
            String[] parts = requestURL.split(splitOn);
            if( parts.length > 1 ){
                foundPath = requestURL.split(splitOn)[1];
            } else {
                foundPath = "/";
            }
        }
        if( foundPath.length() > 1 && foundPath.endsWith("/") ){
            // Remove the trailing slash from the found path
            foundPath = foundPath.substring(0, foundPath.length() - 1);
        }
        return foundPath;
    }
}
