package dev.dispatch.ui.filemanager;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fxmisc.richtext.model.StyleSpans;
import org.fxmisc.richtext.model.StyleSpansBuilder;

/**
 * Computes syntax-highlighting {@link StyleSpans} for a block of text. Each implementation targets
 * one language family. The correct implementation is selected by file extension via {@link
 * #forFile}. All {@code highlight} calls are safe to invoke from any thread.
 */
public interface SyntaxHighlighter {

  /** Returns style spans covering the entire {@code text}. */
  StyleSpans<Collection<String>> highlight(String text);

  /** Selects the appropriate highlighter by file extension; falls back to {@link Plain}. */
  static SyntaxHighlighter forFile(String filename) {
    String lower = filename.toLowerCase();
    if (lower.endsWith(".json")) return new Json();
    if (lower.endsWith(".yaml") || lower.endsWith(".yml")) return new Yaml();
    if (lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".zsh"))
      return new Shell();
    if (lower.endsWith(".py")) return new Python();
    if (lower.endsWith(".xml") || lower.endsWith(".html") || lower.endsWith(".htm"))
      return new Xml();
    return new Plain();
  }

  // ── Shared helper ─────────────────────────────────────────────────────────────

  /**
   * Applies {@code pattern} to {@code text}, mapping each named capture group to a CSS style class.
   * {@code groups} lists the group names in priority order (first non-null group wins).
   */
  static StyleSpans<Collection<String>> applyPattern(
      Pattern pattern, List<String> groups, String text) {
    Matcher m = pattern.matcher(text);
    StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
    int last = 0;
    while (m.find()) {
      String cls = groups.stream().filter(g -> m.group(g) != null).findFirst().orElse("");
      b.add(Collections.emptyList(), m.start() - last);
      b.add(
          cls.isEmpty() ? Collections.emptyList() : Collections.singleton(cls),
          m.end() - m.start());
      last = m.end();
    }
    b.add(Collections.emptyList(), text.length() - last);
    return b.create();
  }

  // ── Language implementations ──────────────────────────────────────────────────

  /** No-op: returns a single unstyled span covering the entire text. */
  final class Plain implements SyntaxHighlighter {

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      StyleSpansBuilder<Collection<String>> b = new StyleSpansBuilder<>();
      b.add(Collections.emptyList(), text.length());
      return b.create();
    }
  }

  /** JSON: keys, strings, numbers, booleans/null. */
  final class Json implements SyntaxHighlighter {

    private static final List<String> GROUPS = List.of("key", "string", "number", "keyword");
    private static final Pattern PATTERN =
        Pattern.compile(
            "(?<key>\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\"(?=\\s*:))"
                + "|(?<string>\"[^\"\\\\]*(?:\\\\.[^\"\\\\]*)*\")"
                + "|(?<number>-?\\b(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?\\b)"
                + "|(?<keyword>\\b(?:true|false|null)\\b)");

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      return applyPattern(PATTERN, GROUPS, text);
    }
  }

  /** YAML: keys, strings, numbers, comments. */
  final class Yaml implements SyntaxHighlighter {

    private static final List<String> GROUPS = List.of("comment", "key", "string", "number");
    private static final Pattern PATTERN =
        Pattern.compile(
            "(?<comment>#[^\\n]*)"
                + "|(?<key>^[ \\t]*(?!#)[^#\\s:][^:]*(?=\\s*:))"
                + "|(?<string>['\"](?:[^'\"\\\\]|\\\\.)*['\"])"
                + "|(?<number>(?<![\\w/])[-+]?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?(?![\\w/]))",
            Pattern.MULTILINE);

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      return applyPattern(PATTERN, GROUPS, text);
    }
  }

  /** Shell/Bash: keywords, strings, comments, variable references. */
  final class Shell implements SyntaxHighlighter {

    private static final List<String> GROUPS = List.of("comment", "string", "keyword", "directive");
    private static final Pattern PATTERN =
        Pattern.compile(
            "(?<comment>#[^\\n]*)"
                + "|(?<string>\"(?:[^\"\\\\]|\\\\.)*\"|'[^']*')"
                + "|(?<keyword>\\b(?:if|then|else|elif|fi|for|in|do|done|while|until|case|esac"
                + "|function|return|exit|local|export|readonly|declare|shift|source)\\b)"
                + "|(?<directive>\\$(?:\\{[^}]*\\}|[A-Za-z_][A-Za-z0-9_]*))",
            Pattern.MULTILINE);

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      return applyPattern(PATTERN, GROUPS, text);
    }
  }

  /** Python: keywords, strings, comments, decorators, numbers. */
  final class Python implements SyntaxHighlighter {

    private static final List<String> GROUPS =
        List.of("comment", "string", "keyword", "directive", "number");
    private static final Pattern PATTERN =
        Pattern.compile(
            "(?<comment>#[^\\n]*)"
                + "|(?<string>\"\"\"[\\s\\S]*?\"\"\"|'''[\\s\\S]*?'''"
                + "|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*')"
                + "|(?<keyword>\\b(?:False|None|True|and|as|assert|async|await|break|class"
                + "|continue|def|del|elif|else|except|finally|for|from|global|if|import"
                + "|in|is|lambda|nonlocal|not|or|pass|raise|return|try|while|with|yield)\\b)"
                + "|(?<directive>@[A-Za-z_][A-Za-z0-9_.]*)"
                + "|(?<number>\\b(?:0[xXoObB][0-9A-Fa-f_]+|[0-9][0-9_]*(?:\\.[0-9_]+)?(?:[eE][+-]?[0-9_]+)?)\\b)");

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      return applyPattern(PATTERN, GROUPS, text);
    }
  }

  /** XML/HTML: tags, attributes, attribute values, comments. */
  final class Xml implements SyntaxHighlighter {

    private static final List<String> GROUPS = List.of("comment", "keyword", "key", "string");
    private static final Pattern PATTERN =
        Pattern.compile(
            "(?<comment><!--[\\s\\S]*?-->)"
                + "|(?<keyword></?[A-Za-z][A-Za-z0-9_:.-]*)"
                + "|(?<key>[A-Za-z][A-Za-z0-9_:.-]*(?=\\s*=))"
                + "|(?<string>\"[^\"]*\"|'[^']*')",
            Pattern.MULTILINE);

    @Override
    public StyleSpans<Collection<String>> highlight(String text) {
      return applyPattern(PATTERN, GROUPS, text);
    }
  }
}
