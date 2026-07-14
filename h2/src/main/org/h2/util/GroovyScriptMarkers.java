/*
 * Copyright 2004-2023 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.util;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;

import org.h2.message.DbException;

/**
 * Preprocessor for embedded Groovy blocks in SQL scripts (OSaaS fork, ADR-7).
 * <p>
 * Hand-written {@code .sql} files can wrap a block of Groovy between marker
 * lines so that it is not split on the semicolons it contains:
 *
 * <pre>
 * &lt;&lt;groovy start&gt;&gt;
 *     sql.eachRow('SELECT id FROM orders') { row -&gt;
 *         sql.executeUpdate('UPDATE orders SET seen = TRUE WHERE id = ?', [row.id])
 *     }
 * &lt;&lt;groovy end&gt;&gt;
 * </pre>
 *
 * Each such block is rewritten to a single
 * {@code EXECUTE GROOVY $$ ... $$;} statement before the script is handed to
 * the normal statement splitter. The markers are recognized case-insensitively
 * and must each stand alone on their own line. {@code <<groovy>>} is accepted
 * as an alias for {@code <<groovy start>>}.
 * <p>
 * The block body must not contain the dollar-quote delimiter {@code $$}; if it
 * does, a {@link DbException} is thrown (use {@code EXECUTE GROOVY} with a
 * quoted string literal for such sources).
 */
public final class GroovyScriptMarkers {

    private static final String START = "<<groovy start>>";
    private static final String START_ALIAS = "<<groovy>>";
    private static final String END = "<<groovy end>>";

    private GroovyScriptMarkers() {
        // utility class
    }

    /**
     * Whether the given text contains a Groovy start marker on its own line.
     *
     * @param text the script text
     * @return true if a marker block is present
     */
    public static boolean containsMarkers(String text) {
        if (text == null) {
            return false;
        }
        for (String line : text.split("\n", -1)) {
            String t = line.trim().toLowerCase();
            if (t.equals(START) || t.equals(START_ALIAS)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Wrap a reader so that Groovy marker blocks are rewritten to
     * {@code EXECUTE GROOVY} statements. The whole reader is consumed and
     * closed; if no markers are present the text is passed through unchanged.
     *
     * @param reader the source reader
     * @return a reader over the rewritten text
     * @throws IOException on read failure
     */
    public static Reader wrap(Reader reader) throws IOException {
        String text = IOUtils.readStringAndClose(reader, -1);
        return new StringReader(rewrite(text));
    }

    /**
     * Rewrite Groovy marker blocks in the given script text to
     * {@code EXECUTE GROOVY $$ ... $$;} statements.
     *
     * @param text the script text
     * @return the rewritten text (unchanged if there are no marker blocks)
     */
    public static String rewrite(String text) {
        if (text == null || !containsMarkers(text)) {
            return text;
        }
        // preserve the original line terminator style where practical: work in
        // '\n' units, splitting keeps any trailing '\r' on each line
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder(text.length() + 32);
        int i = 0;
        while (i < lines.length) {
            String raw = lines[i];
            String marker = raw.trim().toLowerCase();
            if (marker.equals(START) || marker.equals(START_ALIAS)) {
                StringBuilder body = new StringBuilder();
                int j = i + 1;
                boolean closed = false;
                for (; j < lines.length; j++) {
                    String bodyLine = lines[j];
                    if (bodyLine.trim().toLowerCase().equals(END)) {
                        closed = true;
                        break;
                    }
                    if (body.length() > 0) {
                        body.append('\n');
                    }
                    body.append(stripTrailingCr(bodyLine));
                }
                if (!closed) {
                    throw DbException.getSyntaxError(text, text.indexOf(raw),
                            "'<<groovy start>>' without matching '<<groovy end>>'");
                }
                if (body.indexOf("$$") >= 0) {
                    throw DbException.getSyntaxError(text, text.indexOf(raw),
                            "Groovy marker block must not contain '$$'; "
                                    + "use EXECUTE GROOVY with a quoted string instead");
                }
                out.append("EXECUTE GROOVY $$\n").append(body).append("\n$$;\n");
                i = j + 1;
            } else {
                out.append(raw);
                if (i < lines.length - 1) {
                    out.append('\n');
                }
                i++;
            }
        }
        return out.toString();
    }

    private static String stripTrailingCr(String s) {
        if (!s.isEmpty() && s.charAt(s.length() - 1) == '\r') {
            return s.substring(0, s.length() - 1);
        }
        return s;
    }

}
