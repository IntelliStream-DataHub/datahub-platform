// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.controllers.api;

import ai.intellistream.dhconsole.chat.agent.ChatReply;
import ai.intellistream.dhconsole.chat.agent.ChatService;
import ai.intellistream.dhconsole.chat.agent.ConsoleView;
import ai.intellistream.dhconsole.chat.agent.ConsoleViews;
import ai.intellistream.dhconsole.chat.config.ChatProperties;
import ai.intellistream.dhconsole.chat.config.ChatSettings;
import ai.intellistream.dhconsole.chat.config.ChatSettingsResolver;
import ai.intellistream.dhconsole.chat.llm.ChatEffort;
import ai.intellistream.dhconsole.chat.llm.LlmBlock;
import ai.intellistream.dhconsole.chat.llm.LlmMessage;
import ai.intellistream.dhconsole.chat.mcp.McpException;
import ai.intellistream.dhconsole.chat.state.ChatConversation;
import ai.intellistream.dhconsole.security.AccessTokens;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The chat panel's backend.
 *
 * <p>Synchronous by design: the whole turn runs on the request thread, which is what lets
 * {@link AccessTokens} resolve the caller's token and lets the transcript live in the HTTP session.
 * A turn with a couple of tool calls takes several seconds, so the panel shows a spinner.
 *
 * <p>Inherited from the console's security config: this path requires the {@code DATAHUB_CONSOLE}
 * authority, and POSTs require the CSRF header.
 */
@Slf4j
@RestController
@RequestMapping("/api/chat")
@ConditionalOnProperty(prefix = "datahub.chat", name = "enabled", havingValue = "true")
// Deployment gate is the @ConditionalOnProperty above (endpoint doesn't exist when chat is off).
// Tenant + user gates are enforced per request: same rule the layout uses to hide the UI, so a user
// without the Vault tenant flag or the DATAHUB_CHAT authority gets 403 even if they call directly.
@PreAuthorize("@chatAccess.available()")
public class ChatApiController {

    /** Session attribute holding the transcript. Spring Session persists it to Valkey for us. */
    private static final String CONVERSATION_ATTRIBUTE = "datahub.chat.conversation";

    /** More buttons than this under one answer is noise; mirrors ChatService's live cap. */
    private static final int MAX_VIEWS = 3;

    private final ChatService chatService;
    private final ChatSettingsResolver settingsResolver;
    private final AccessTokens accessTokens;
    private final MessageSource messageSource;
    private final ConsoleViews consoleViews;
    private final ChatProperties properties;

    public ChatApiController(ChatService chatService, ChatSettingsResolver settingsResolver,
                            AccessTokens accessTokens, MessageSource messageSource,
                            ConsoleViews consoleViews, ChatProperties properties) {
        this.chatService = chatService;
        this.settingsResolver = settingsResolver;
        this.accessTokens = accessTokens;
        this.messageSource = messageSource;
        this.consoleViews = consoleViews;
        this.properties = properties;
    }

    /**
     * @param timeZone the browser's IANA zone, so "this weekend" is resolved where the user is
     *                 rather than where the server runs. Optional — falls back to the server zone.
     * @param effort   how hard to think about this message. Optional — an omitted or unrecognised
     *                 value falls back to the deployment default rather than failing the turn,
     *                 since a stale cached panel is a likelier source of a bad value than an
     *                 attack, and the level is bounded by the enum either way.
     */
    public record ChatRequest(@NotBlank @Size(max = 4000) String message,
                              @Size(max = 64) String timeZone,
                              @Size(max = 16) String effort) {
    }

    public record ChatResponse(String reply, List<String> toolsUsed, List<ConsoleView> views,
                               boolean truncated, String error) {
        static ChatResponse of(ChatReply reply) {
            return new ChatResponse(reply.reply(), reply.toolsUsed(), reply.views(), reply.truncated(), null);
        }

        static ChatResponse error(String message) {
            return new ChatResponse(null, List.of(), List.of(), false, message);
        }
    }

    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE, consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatResponse> message(@Valid @RequestBody ChatRequest request,
                                                HttpSession session,
                                                Locale locale) {
        // Resolved here, on the request thread, and handed to the loop as a value: every MCP call
        // is then made as this user, so datahub-api applies their ACLs and tenant.
        String bearer;
        try {
            bearer = accessTokens.token();
        } catch (RuntimeException e) {
            log.warn("Chat turn rejected: could not resolve the caller's access token", e);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ChatResponse.error(message("chat.error.session.expired", locale)));
        }

        ChatConversation conversation = conversation(session);
        long startedAt = System.nanoTime();
        try {
            // Resolved per turn, on the request thread: a tenant may have been reconfigured since
            // the last one, and TenantConfigService refreshes from Vault on its own schedule.
            ChatSettings settings = settingsResolver.forCurrentTenant();
            ChatEffort effort = ChatEffort.parse(request.effort(), properties.getEffort());
            ChatReply reply = chatService.send(conversation, settings, bearer, request.message(),
                    zoneOf(request), effort);
            session.setAttribute(CONVERSATION_ATTRIBUTE, conversation); // re-set so Spring Session saves it
            // The only record that a turn finished. Everything else on this path logs on failure
            // only, which leaves the case that matters - the server answered but the browser never
            // received it - looking exactly like a turn that was never answered at all.
            log.info("Chat turn answered in {} ms on {}: {} chars, tools {}{}", elapsedMs(startedAt),
                    settings.model(),
                    reply.reply() == null ? 0 : reply.reply().length(), reply.toolsUsed(),
                    reply.truncated() ? ", truncated" : "");
            return ResponseEntity.ok(ChatResponse.of(reply));
        } catch (McpException e) {
            if (e.isUnauthorized()) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(ChatResponse.error(message("chat.error.session.expired", locale)));
            }
            log.warn("Chat turn failed talking to the api after {} ms", elapsedMs(startedAt), e);
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                    .body(ChatResponse.error(message("chat.error.api.unreachable", locale)));
        } catch (RuntimeException e) {
            log.error("Chat turn failed after {} ms", elapsedMs(startedAt), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(ChatResponse.error(message("chat.error.generic", locale)));
        }
    }

    /**
     * The transcript so far, so the panel can restore itself after the user navigates to another
     * page. Returns the plain prose — the user's turns and the assistant's answers — plus, on each
     * answer, any "open in the console" navigation buttons.
     *
     * <p>The buttons are <strong>re-derived</strong> from the stored tool calls rather than stored
     * separately: the {@code event_filter}/{@code open_events_view} calls that produced them are
     * already in the transcript, so {@link ConsoleViews} rebuilds the same offers a live turn made.
     * They are attached to the last assistant answer of each exchange, matching where the live turn
     * renders them.
     */
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ChatHistory history(HttpSession session) {
        List<LlmMessage> messages = conversation(session).messages();

        // A view needs the call's arguments and (for event_filter) the result's count, so index the
        // results by the call they answer.
        Map<String, LlmBlock.ToolResult> resultsById = new HashMap<>();
        for (LlmMessage message : messages) {
            for (LlmBlock block : message.blocks()) {
                if (block instanceof LlmBlock.ToolResult result) {
                    resultsById.put(result.toolUseId(), result);
                }
            }
        }

        List<ChatHistory.Turn> turns = new ArrayList<>();
        Set<ConsoleView> exchangeViews = new LinkedHashSet<>();
        int lastAnswer = -1; // index in turns of this exchange's last assistant answer

        for (LlmMessage message : messages) {
            if (message.isPlainUserTurn()) {
                attachViews(turns, lastAnswer, exchangeViews);
                exchangeViews = new LinkedHashSet<>();
                lastAnswer = -1;
                turns.add(new ChatHistory.Turn("user", message.text(), List.of()));
            } else if (message.role() == LlmMessage.Role.ASSISTANT) {
                boolean hasToolUse = false;
                for (LlmBlock block : message.blocks()) {
                    if (block instanceof LlmBlock.ToolUse call) {
                        hasToolUse = true;
                        if (consoleViews.isLocalTool(call.name())) {
                            consoleViews.fromLocalCall(call).ifPresent(exchangeViews::add);
                        } else {
                            LlmBlock.ToolResult result = resultsById.get(call.id());
                            if (result != null) {
                                consoleViews.from(call, result).ifPresent(exchangeViews::add);
                            }
                        }
                    }
                }
                // Only the final answer (a text-only turn) is shown, matching the live panel, which
                // never renders the interim narration that rides along with tool calls.
                if (!hasToolUse && !message.text().isBlank()) {
                    turns.add(new ChatHistory.Turn("assistant", message.text(), List.of()));
                    lastAnswer = turns.size() - 1;
                }
            }
        }
        attachViews(turns, lastAnswer, exchangeViews);
        // The turn timeout is per tenant, so the panel has to be told this tenant's — a tenant on
        // a slow self-hosted model raises it, and a panel still aborting at the deployment default
        // would show a network error while the server was still working. Effort is not per tenant,
        // so it keeps coming from the deployment configuration.
        return new ChatHistory(turns, properties.getEffort().wireName(),
                settingsResolver.forCurrentTenant().turnTimeout().toMillis());
    }

    private static void attachViews(List<ChatHistory.Turn> turns, int index, Set<ConsoleView> views) {
        if (index < 0 || views.isEmpty()) {
            return;
        }
        ChatHistory.Turn turn = turns.get(index);
        turns.set(index, new ChatHistory.Turn(turn.role(), turn.text(),
                views.stream().limit(MAX_VIEWS).toList()));
    }

    /**
     * @param defaultEffort the level the picker starts on for a user who has never chosen one. Sent
     *                      with the history rather than from its own endpoint because the panel
     *                      already fetches this on open and has nothing to show before it lands.
     * @param turnTimeoutMs how long the panel should wait for a turn, for this tenant. A
     *                      self-hosted model needs minutes where a hosted one needs seconds, so the
     *                      panel cannot hardcode it.
     */
    public record ChatHistory(List<Turn> messages, String defaultEffort, long turnTimeoutMs) {
        public record Turn(String role, String text, List<ConsoleView> views) {
        }
    }

    @PostMapping(path = "/reset")
    public ResponseEntity<Void> reset(HttpSession session) {
        session.removeAttribute(CONVERSATION_ATTRIBUTE);
        return ResponseEntity.noContent().build();
    }

    private static long elapsedMs(long startedAt) {
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    /** A zone the browser reported, or the server's own if it sent nothing usable. */
    private static ZoneId zoneOf(ChatRequest request) {
        if (request.timeZone() != null && !request.timeZone().isBlank()) {
            try {
                return ZoneId.of(request.timeZone());
            } catch (DateTimeException e) {
                log.debug("Ignoring unusable time zone from the browser: {}", request.timeZone());
            }
        }
        return ZoneId.systemDefault();
    }

    private static ChatConversation conversation(HttpSession session) {
        Object existing = session.getAttribute(CONVERSATION_ATTRIBUTE);
        return existing instanceof ChatConversation conversation ? conversation : new ChatConversation();
    }

    private String message(String key, Locale locale) {
        return messageSource.getMessage(key, null, locale);
    }
}
