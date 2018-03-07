package io.github.lonamiwebs.stringlate.classes.resources.tags;

import org.xml.sax.InputSource;

import java.io.StringReader;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

public abstract class ResTag implements Comparable<ResTag> {

    //region Static members

    // Escape the backslash for the user unless they are writing a escape sequence
    private static final char[] ESCAPE_SEQUENCES = {
            'u', 'b', 't', 'n', 'f', 'r', '\\',
            'U', 'B', 'T', 'N', 'F', 'R'
    };

    //endregion

    //region Members

    String mContent = "";

    // "metadata" used to keep track whether a string is the original or not. This
    // will be later used when downloading remote changes, to keep local if modified.
    boolean mModified;

    //endregion

    //region Getters

    abstract public String getId();

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    public String getContent() {
        return mContent;
    }

    public int getContentLength() {
        return mContent.length();
    }

    public boolean hasContent() {
        return !mContent.isEmpty();
    }

    public boolean wasModified() {
        return mModified;
    }

    public abstract ResTag clone(String newContent);

    //endregion

    //region Setters

    // Returns true if the content was successfully set
    public boolean setContent(String content) {
        content = content.trim();
        if (!mContent.equals(content)) {
            mContent = content;
            mModified = true;
            return true;
        } else {
            return false;
        }
    }

    //endregion

    //region Interfaces implementation

    @Override
    public int compareTo(final ResTag o) {
        if (o == null)
            throw new IllegalArgumentException();
        return getId().compareTo(o.getId());
    }

    //endregion

    //region

    // De-sanitizes the content, making it ready to modified outside a strings.xml file
    public static String desanitizeContent(String content) {
        char c;
        int length = content.length();
        StringBuilder sb = new StringBuilder(length + 16); // 16 seems to be the default capacity

        // Currently we handle the following sequences:
        // \", \', \\, \n, &lt;, &gt;, &amp;
        for (int i = 0; i < length; i++) {
            c = content.charAt(i);
            switch (c) {
                // Unescape these sequences iff we know them
                case '\\':
                    i++; // Handle the next character here
                    if (i >= length) {
                        sb.append('\\');
                        break;
                    }
                    c = content.charAt(i);
                    switch (c) {
                        case '"':
                        case '\'':
                        case '\\':
                            sb.append(c);
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        default:
                            // Keep the unescaped sequence
                            // TODO We'll have problems with this because then the \s will be \\s
                            // since we will escape the \ as \\
                            sb.append('\\').append(c);
                            break;
                    }
                    break;

                case '&':
                    int semicolon = content.indexOf(';', i);
                    if (semicolon < 0) {
                        // Invalid string actually
                        // TODO Handle invalid & on the middle, but escaped OK later?
                        // (i.e., check whitespace after &)
                        sb.append('&');
                    } else {
                        switch (content.substring(i, semicolon)) {
                            case "&lt":
                                sb.append('<');
                                break;
                            case "&gt":
                                sb.append('>');
                                break;
                            case "&amp":
                                sb.append('&');
                                break;
                            default:
                                // Keep the unescaped sequence, we'll also have trouble
                                sb.append(content.substring(i, semicolon)).append(';');
                                break;
                        }
                        i = semicolon;
                    }
                    break;

                // New lines are treated as spaces
                case '\n':
                    sb.append(' ');
                    break;

                // Normal character
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    private static boolean isEscapeSequence(char which) {
        for (int i = 0; i < ESCAPE_SEQUENCES.length; ++i) {
            if (ESCAPE_SEQUENCES[i] == which) {
                return true;
            }
        }
        return false;
    }

    // Sanitizes the content, making it ready to be written to a strings.xml file
    public static String sanitizeContent(String content) {
        char c;
        int length = content.length();
        StringBuilder sb = new StringBuilder(length + 16); // 16 seems to be the default capacity

        boolean replaceLtGt = false;
        if (content.contains("<")) {
            // The string contains (X|HT)ML tags, ensure it's valid by parsing the content
            // If it's not, then replace every <> with &lt; &gt; not to break the XML file
            try {
                DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
                factory.setValidating(false);
                factory.setNamespaceAware(true);

                DocumentBuilder builder = factory.newDocumentBuilder();
                // Every XML needs to have a root tag, however, this is not the case for
                // Android strings, and so we need to wrap it around some arbitrary tags
                // in order to check whether the inner content is right or not.
                builder.parse(new InputSource(new StringReader("<a>" + content + "</a>")));
            } catch (Exception ignored) {
                replaceLtGt = true;
            }
        }

        // Currently we handle the following characters:
        // \n, ', \, &, <, >, "
        //
        // Keep track of insideAngleBrackets to escape " only if we're outside.
        boolean insideAngleBrackets = false;
        for (int i = 0; i < length; i++) {
            c = content.charAt(i);
            if (insideAngleBrackets) {
                if (c == '>') {
                    insideAngleBrackets = false;
                }
                sb.append(c);
                continue;
            }

            switch (c) {
                // These should always be escaped
                case '\'':
                    sb.append("\\'");
                    break;
                // Keep an actual newline afterwards for more readability
                case '\n':
                    sb.append("\\n\n");
                    break;

                case '\\':
                    if (i + 1 < length && isEscapeSequence(content.charAt(i + 1))) {
                        // Don't escape the \ itself if the next
                        // character belongs to a escape sequence.
                        // Also skip the next character to avoid processing it.
                        i++;
                    } else {
                        sb.append("\\\\");
                    }
                    break;
                case '&':
                    sb.append("&amp;");
                    break;

                // We might or not need to replace <>
                case '<':
                    if (replaceLtGt) {
                        insideAngleBrackets = true;
                        sb.append("&lt;");
                    } else {
                        sb.append(c);
                    }
                    break;
                case '>':
                    sb.append(replaceLtGt ? "&gt;" : ">");
                    break;

                // Quotes are a bit special
                case '"':
                    if (i == 0 || i == length - 1)
                        sb.append('"');
                    else
                        sb.append("\\\"");
                    break;

                // Normal character
                default:
                    sb.append(c);
                    break;
            }
        }
        return sb.toString();
    }

    //endregion
}
