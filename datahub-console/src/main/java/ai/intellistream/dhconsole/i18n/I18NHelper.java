// SPDX-License-Identifier: AGPL-3.0-or-later
package ai.intellistream.dhconsole.i18n;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public class I18NHelper {

    /**
     * Returns the raw .properties text for a locale, falling back to the English
     * base for any locale we don't ship. The browser parses the key=value lines
     * itself — the files are flat key=value, so there is nothing to do server-side.
     */
    public static void loadMessages(HttpServletResponse response, Locale locale) {
        response.setContentType("text/plain; charset=UTF-8");
        try (InputStream in = openBundle(locale)) {
            in.transferTo(response.getOutputStream());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // Localized bundle when we ship one, otherwise the English base (must exist).
    private static InputStream openBundle(Locale locale) {
        ClassLoader cl = I18NHelper.class.getClassLoader();
        String lang = (locale != null) ? locale.getLanguage() : "";

        if (!lang.isBlank() && !"en".equals(lang)) {
            InputStream localized = cl.getResourceAsStream("i18n/messages_" + lang + ".properties");
            if (localized != null) {
                return localized;
            }
        }
        InputStream base = cl.getResourceAsStream("i18n/messages.properties");
        if (base == null) {
            throw new IllegalStateException("Missing i18n resource: i18n/messages.properties");
        }
        return base;
    }

    public static String[] getFileMessages(){
        return new String[]{
                "name",
                "delete",
                "datahub.save",
                "cancel",
                "confirm",
                "update",
                "upload",
                "close",
                "external.id",
                "i18ncode",
                "main.units",
                "symbol",
                "write.description.here",
                "write.name.here",
                "description",
                "stabilize.network",
                "are.you.sure.delete.entity",
                "upload.file",
                "write.the.upload.path.here",
                "main.path",
                "write.external.id.here",
                "source",
                "main.metadata",
                "main.key",
                "main.value",
                "add.metadata",
                "metadata.key.here",
                "metadata.value.here",
                "no.file.selected",
                "browse.file",
                "no.file.selected",
                "please.select.a.file.to.upload.first",
                "file.name",
                "file.size.exceeded",
        };
    }

}
