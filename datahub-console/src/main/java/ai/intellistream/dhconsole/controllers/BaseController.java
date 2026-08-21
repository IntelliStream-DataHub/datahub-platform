// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers;

import ai.intellistream.dhconsole.security.AccessTokens;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.io.FileNotFoundException;
import java.time.LocalTime;

@Controller
@Slf4j
public class BaseController {

    private final AccessTokens accessTokens;
    private final ClientRegistrationRepository clientRegistrationRepository;
    private final OAuth2AuthorizedClientService authorizedClientService;
    private final ResourceLoader resourceLoader;

    public BaseController(
            AccessTokens accessTokens,
            ClientRegistrationRepository clientRegistrationRepository,
            OAuth2AuthorizedClientService authorizedClientService,
            ResourceLoader resourceLoader
    ) {
        this.accessTokens = accessTokens;
        this.clientRegistrationRepository = clientRegistrationRepository;
        this.authorizedClientService = authorizedClientService;
        this.resourceLoader = resourceLoader;
    }

    @RequestMapping(value = {"/", ""}, method = {RequestMethod.HEAD, RequestMethod.GET})
    public String index(Model model) {
        // The dashboard renders a shell; home-dashboard.js fetches its data from the API directly
        // (bearer token via /token), so the only thing the server contributes here is the greeting.
        int hour = LocalTime.now().getHour();
        model.addAttribute("greetingKey",
                hour < 12 ? "home.greeting.morning" : hour < 18 ? "home.greeting.afternoon" : "home.greeting.evening");
        return "base/index";
    }

    @RequestMapping(value = {"/is-logged-in"}, method = {RequestMethod.HEAD, RequestMethod.GET})
    public ResponseEntity<?> isLoggedIn(Authentication authentication) {
        if(authentication != null && authentication.isAuthenticated()){
            return new ResponseEntity<>(HttpStatus.NO_CONTENT);
        }
        return new ResponseEntity<>(HttpStatus.UNAUTHORIZED);
    }

    @GetMapping("/datahub-logout")
    public String logout() {
        return "base/datahub-logout";
    }

    @RequestMapping(value = {"/etl", "/etl/", "/etl/index"}, method = {RequestMethod.GET})
    public String etl() {
        return "base/etl";
    }

    @RequestMapping(value = {"/token"}, method = {RequestMethod.GET}, produces = {"text/plain"})
    @ResponseBody
    public ResponseEntity<?> token() {
        try{
            String token = accessTokens.token();
            return new ResponseEntity<>(token, HttpStatus.OK);
        } catch (Exception e){
            return new ResponseEntity<>("", HttpStatus.UNAUTHORIZED);
        }
    }

    @GetMapping("/modal-content/{content}")
    public String modalContent(@PathVariable String content, HttpServletResponse response) {
        String location = "classpath:/templates/modal-content/" + content + ".html";
        try {
            Resource resource = resourceLoader.getResource(location);
            if(resource.exists() && resource.isReadable()){
                return "modal-content/" + content;
            } else {
                throw new FileNotFoundException(location);
            }
        } catch (Exception e) {
            log.error(e.getMessage());
            response.setStatus(HttpStatus.NOT_FOUND.value());
            return "base/404.html";
        }
    }
}
