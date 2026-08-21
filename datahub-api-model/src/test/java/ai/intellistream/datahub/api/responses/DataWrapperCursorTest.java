// SPDX-License-Identifier: Apache-2.0
package ai.intellistream.datahub.api.responses;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The paging loop's terminating condition, from the client's side: keep going while
 * {@code nextCursor} is present.
 *
 * <p>That only works if an exhausted response omits the field rather than sending it as null — a
 * client checking presence would otherwise never stop. Worth pinning because the {@code NON_EMPTY}
 * that makes it true sits on the field while the getter is explicit, which is exactly the
 * arrangement that silently stops applying if someone tidies one of them.
 */
class DataWrapperCursorTest {

    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test
    void aPageWithMoreToComeCarriesTheCursor() {
        DataWrapper<String> page = new DataWrapper<>();
        page.setNextCursor("djF8bmFtZXxhc2N8MXx2QQ");

        assertTrue(mapper.writeValueAsString(page).contains("nextCursor"));
    }

    @Test
    void theLastPageOmitsTheFieldEntirely() {
        DataWrapper<String> page = new DataWrapper<>();

        String json = mapper.writeValueAsString(page);

        assertFalse(json.contains("nextCursor"),
                "an exhausted response must omit nextCursor, not send it as null: clients page "
                        + "while the field is present, and a null would never end the loop — " + json);
    }
}
