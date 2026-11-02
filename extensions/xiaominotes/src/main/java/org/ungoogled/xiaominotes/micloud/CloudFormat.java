package org.ungoogled.xiaominotes.micloud;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Notes' Markdown back to what Xiaomi Cloud stores, for sync's uploads. Accounts in the website's new format keep
 * Markdown (only the picture names change back to the cloud's file ids). The original format is MIUI rich text: the
 * subset Notes' own converter reads (`<new-format/>`, `<text>`, `<size>`, `<b>`, checklists, bullets, numbered items,
 * quotes, pictures and recordings by file id); anything else goes up as plain text. Pictures and recordings that came
 * from the cloud go back as their file ids; ones added on this phone cannot be uploaded and are left out of the
 * cloud copy (they stay on the phone).
 */
final class CloudFormat {
    private CloudFormat() {
    }

    private static final Pattern IMAGE_LINE = Pattern.compile("^!\\[([^\\]]*)]\\(https://com\\.miui\\.notes/image/([^?)\\s]+)[^)]*\\)\\s*$");
    private static final Pattern AUDIO_LINE = Pattern.compile("^\\[[^\\]]*]\\(https://com\\.miui\\.notes/audio/([^?)\\s]+)[^)]*\\)\\s*$");
    private static final Pattern IMAGE = Pattern.compile("(https://com\\.miui\\.notes/image/)([^?)\\s]+)");
    private static final Pattern AUDIO = Pattern.compile("(https://com\\.miui\\.notes/audio/)([^?)\\s]+)");
    private static final Pattern HEADING = Pattern.compile("^(#{1,3})\\s+(.*)$");
    private static final Pattern CHECK = Pattern.compile("^\\s*[-*+]\\s+\\[([ xX])]\\s?(.*)$");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*+]\\s+(.*)$");
    private static final Pattern ORDER = Pattern.compile("^\\s*(\\d+)[.)]\\s+(.*)$");
    private static final Pattern QUOTE = Pattern.compile("^>\\s?(.*)$");

    /** Counts what could not go up. */
    static final class Report {
        int localOnlyFiles;
    }

    /**
     * The cloud file id for a picture or recording saved by the importer, or null for one added on this phone. The
     * importer saves cloud file X as X or X.<image extension>.
     */
    static String cloudFile(String localName, Set<String> cloudFiles) {
        if (cloudFiles.contains(localName)) return localName;
        int dot = localName.lastIndexOf('.');
        if (dot > 0 && CloudImporter.extension(localName) != null) {
            String id = localName.substring(0, dot);
            if (cloudFiles.contains(id)) return id;
        }
        return null;
    }

    /** New-format accounts: Markdown, with the picture and recording links pointing at cloud file ids again. */
    static String toMarkdown(String markdown, Set<String> cloudFiles, Report report) {
        StringBuilder out = new StringBuilder();
        for (String line : markdown.split("\n", -1)) {
            Matcher img = IMAGE_LINE.matcher(line), audio = AUDIO_LINE.matcher(line);
            String id = img.matches() ? img.group(2) : audio.matches() ? audio.group(1) : null;
            if (id != null && cloudFile(id, cloudFiles) == null) {
                report.localOnlyFiles++;
                continue;
            }
            out.append(line).append('\n');
        }
        String s = out.length() > 0 ? out.substring(0, out.length() - 1) : "";
        s = relink(IMAGE, s, cloudFiles);
        return relink(AUDIO, s, cloudFiles);
    }

    private static String relink(Pattern p, String s, Set<String> cloudFiles) {
        Matcher m = p.matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String id = cloudFile(m.group(2), cloudFiles);
            m.appendReplacement(sb, Matcher.quoteReplacement(m.group(1) + (id != null ? id : m.group(2))));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Original-format accounts: MIUI rich text. */
    static String toMiui(String markdown, Set<String> cloudFiles, Report report) {
        StringBuilder out = new StringBuilder("<new-format/>");
        int order = 0;
        for (String raw : markdown.split("\n")) {
            String line = raw.replace("⁢", "").replaceAll("\\s+$", "");
            if (line.trim().isEmpty()) continue;
            Matcher m;
            if (!ORDER.matcher(line).matches()) order = 0;
            if ((m = IMAGE_LINE.matcher(line)).matches()) {
                String id = cloudFile(m.group(2), cloudFiles);
                if (id == null) {
                    report.localOnlyFiles++;
                    continue;
                }
                out.append("\n<img fileid=\"").append(attr(id)).append("\" imgshow=\"0\" imgdes=\"").append(attr(m.group(1))).append("\" />");
            } else if ((m = AUDIO_LINE.matcher(line)).matches()) {
                String id = cloudFile(m.group(1), cloudFiles);
                if (id == null) {
                    report.localOnlyFiles++;
                    continue;
                }
                out.append("\n<sound fileid=\"").append(attr(id)).append("\" />");
            } else if ((m = HEADING.matcher(line)).matches()) {
                String size = m.group(1).length() == 1 ? "size" : m.group(1).length() == 2 ? "mid-size" : "h3-size";
                out.append("\n<text indent=\"1\"><").append(size).append('>').append(inline(m.group(2)))
                    .append("</").append(size).append("></text>");
            } else if ((m = CHECK.matcher(line)).matches()) {
                out.append("\n<input type=\"checkbox\" indent=\"1\" level=\"3\"")
                    .append(m.group(1).equals(" ") ? "" : " checked=\"true\"").append(" />").append(inline(m.group(2)));
            } else if ((m = BULLET.matcher(line)).matches()) {
                out.append("\n<bullet indent=\"1\" />").append(inline(m.group(1)));
            } else if ((m = ORDER.matcher(line)).matches()) {
                out.append("\n<order indent=\"1\" inputNumber=\"").append(order++).append("\" />").append(inline(m.group(2)));
            } else if ((m = QUOTE.matcher(line)).matches()) {
                out.append("\n<quote><text indent=\"1\">").append(inline(m.group(1))).append("</text></quote>");
            } else if (line.trim().matches("-{3,}|\\*{3,}")) {
                out.append("\n<hr />");
            } else {
                out.append("\n<text indent=\"1\">").append(inline(line)).append("</text>");
            }
        }
        return out.toString();
    }

    /** Inline Markdown (bold, italic, strike, underline, Notes' escapes) as MIUI tags, text escaped. */
    static String inline(String s) {
        String t = escape(s);
        t = t.replaceAll("\\*\\*(.+?)\\*\\*", "<b>$1</b>");
        t = t.replaceAll("(?<![\\\\*])\\*(?!\\s)(.+?)(?<![\\\\\\s])\\*", "<i>$1</i>");
        t = t.replaceAll("~~(.+?)~~", "<delete>$1</delete>");
        t = t.replaceAll("&lt;u&gt;(.+?)&lt;/u&gt;", "<u>$1</u>");
        t = t.replaceAll("==(.+?)==", "$1");
        return t.replaceAll("\\\\([\\\\`*\\[\\]~_<=])", "$1");
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private static String attr(String s) {
        return escape(s).replace("\"", "&quot;");
    }

    /** Plain text of Markdown for a new-format note's snippet. */
    static String snippet(String markdown) {
        String s = markdown.replaceAll("!?\\[[^\\]]*]\\([^)]*\\)", "").replaceAll("(?m)^\\s*(#+|[-*+]( \\[[ xX]])?|\\d+[.)]|>)\\s+", "")
            .replaceAll("[*~=`]", "").trim();
        return s.length() > 512 ? s.substring(0, 512) : s;
    }
}
