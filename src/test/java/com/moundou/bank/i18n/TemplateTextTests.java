package com.moundou.bank.i18n;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fast suite. The build check ADR-014 asks for, part of T-I18N-1a:
 *
 * 1. No template contains literal user-facing text (I18N-1). Text must come from
 *    #{key} via th:text / th:utext, an inline [[#{key}]], or a th:* attribute.
 * 2. Every #{key} a template uses exists in messages.properties.
 *
 * It is a heuristic: text with no letters in it (punctuation, symbols, digits) is
 * allowed, and elements whose text Thymeleaf replaces are skipped. A false positive
 * is an annoyance - tune the rules here. A false negative is caught later by the
 * pseudo-locale test, which renders every screen with marked messages.
 */
class TemplateTextTests {

    private static final Path TEMPLATES = Paths.get("src/main/resources/templates");

    /** Attributes a member reads or hears; each needs a th: counterpart when it has words. */
    private static final List<String> TEXT_ATTRIBUTES = List.of("placeholder", "title", "alt", "aria-label", "label");

    private static final Pattern LETTER = Pattern.compile("\\p{L}");
    private static final Pattern INLINE_EXPRESSION = Pattern.compile("\\[\\[.*?]]|\\[\\(.*?\\)]", Pattern.DOTALL);
    private static final Pattern MESSAGE_KEY = Pattern.compile("#\\{([A-Za-z0-9_.-]+)");

    // ---- The checks run against every template in the application ----

    @Test
    void noTemplateContainsLiteralText() throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path template : templates()) {
            violations.addAll(literalText(Files.readString(template, StandardCharsets.UTF_8),
                    TEMPLATES.relativize(template).toString()));
        }
        assertThat(violations).as("literal user-facing text in templates (use #{key})").isEmpty();
    }

    @Test
    void everyMessageKeyUsedInATemplateExists() throws IOException {
        Properties bundle = SrsMessageTests.loadBundle();
        List<String> missing = new ArrayList<>();
        for (Path template : templates()) {
            for (String key : messageKeys(Files.readString(template, StandardCharsets.UTF_8))) {
                if (!bundle.containsKey(key)) {
                    missing.add(TEMPLATES.relativize(template) + ": #{" + key + "}");
                }
            }
        }
        assertThat(missing).as("message keys missing from messages.properties").isEmpty();
    }

    // ---- The checker checks itself, so an empty templates folder still proves something ----

    @Test
    void checkerFlagsLiteralText() {
        assertThat(literalText("<p>Amount</p>", "t")).hasSize(1);
        assertThat(literalText("<input placeholder=\"Amount\">", "t")).hasSize(1);
        assertThat(literalText("<title>Home</title>", "t")).hasSize(1);
    }

    @Test
    void checkerAcceptsExternalizedText() {
        assertThat(literalText("<p th:text=\"#{app.name}\">Placeholder shown only in a browser</p>", "t")).isEmpty();
        assertThat(literalText("<p th:utext=\"#{app.name}\"><em>Nested placeholder</em></p>", "t")).isEmpty();
        assertThat(literalText("<p>[[#{app.name}]]</p>", "t")).isEmpty();
        assertThat(literalText("<input placeholder=\"Amount\" th:placeholder=\"#{entry.amount}\">", "t")).isEmpty();
        assertThat(literalText("<p>· 12 — $</p>", "t")).isEmpty();
        assertThat(literalText("<script>const label = 'not user text';</script>", "t")).isEmpty();
    }

    @Test
    void checkerFindsMessageKeys() {
        assertThat(messageKeys("<p th:text=\"#{a.b}\"></p><span>[[#{c.d(${x})}]]</span>"))
                .containsExactly("a.b", "c.d");
    }

    // ---- The checker ----

    static List<String> literalText(String html, String name) {
        Document doc = Jsoup.parse(html);
        List<String> violations = new ArrayList<>();
        for (Element element : doc.getAllElements()) {
            String tag = element.tagName();
            if (tag.equals("script") || tag.equals("style")) {
                continue;
            }
            if (!textIsReplaced(element)) {
                for (TextNode node : element.textNodes()) {
                    String text = INLINE_EXPRESSION.matcher(node.text()).replaceAll("").trim();
                    if (LETTER.matcher(text).find()) {
                        violations.add(name + ": <" + tag + "> \"" + text + "\"");
                    }
                }
            }
            for (String attribute : TEXT_ATTRIBUTES) {
                if (element.hasAttr(attribute) && !element.hasAttr("th:" + attribute)
                        && LETTER.matcher(element.attr(attribute)).find()) {
                    violations.add(name + ": <" + tag + " " + attribute + "> \"" + element.attr(attribute) + "\"");
                }
            }
        }
        return violations;
    }

    /** True when Thymeleaf replaces this element's content, directly or through an ancestor. */
    private static boolean textIsReplaced(Element element) {
        for (Element e = element; e != null; e = e.parent()) {
            if (e.hasAttr("th:text") || e.hasAttr("th:utext") || e.hasAttr("th:replace")
                    || e.hasAttr("th:insert") || e.attr("th:remove").equals("all")) {
                return true;
            }
        }
        return false;
    }

    static Set<String> messageKeys(String html) {
        Set<String> keys = new LinkedHashSet<>();
        Matcher m = MESSAGE_KEY.matcher(html);
        while (m.find()) {
            keys.add(m.group(1));
        }
        return keys;
    }

    private static List<Path> templates() throws IOException {
        if (!Files.isDirectory(TEMPLATES)) {
            return List.of();   // no screens yet; the self-tests above still run
        }
        try (Stream<Path> files = Files.walk(TEMPLATES)) {
            return files.filter(p -> p.toString().endsWith(".html")).sorted().toList();
        }
    }
}
