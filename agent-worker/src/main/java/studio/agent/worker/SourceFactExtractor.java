package studio.agent.worker;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts deterministic navigation and symbol facts before semantic model analysis. */
public final class SourceFactExtractor {
  private static final Pattern JAVA_IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_$][\\w$]*(?:\\.[A-Za-z_$][\\w$]*)*)");
  private static final Pattern MODULE_IMPORT = Pattern.compile("(?m)^\\s*import(?:\\s+[^'\\\"]+?\\s+from)?\\s*['\\\"]([^'\\\"]+)['\\\"]");
  private static final Pattern REQUIRE = Pattern.compile("\\brequire\\s*\\(\\s*['\\\"]([^'\\\"]+)['\\\"]\\s*\\)");
  private static final Pattern SYMBOL = Pattern.compile("\\b(?:class|interface|record|enum|function|type|const|def)\\s+([A-Za-z_$][\\w$]*)");
  private static final Pattern SPRING_ROUTE = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping\\s*\\(\\s*[\\\"]([^\\\"]+)[\\\"]");
  private static final Pattern JS_ROUTE = Pattern.compile("\\b(?:app|router)\\.(get|post|put|delete|patch)\\s*\\(\\s*['\\\"]([^'\\\"]+)");
  private static final Pattern OBJECT_ROUTE = Pattern.compile("\\bpath\\s*:\\s*['\\\"]([^'\\\"]+)");
  private static final Pattern TABLE = Pattern.compile("(?i)\\b(?:create\\s+table|from|join)\\s+[\\\"`]?([A-Za-z_][\\w$.-]*)");
  private static final Pattern CONFIG = Pattern.compile("(?m)^\\s*([A-Z][A-Z0-9_]{2,})\\s*[:=]");
  private static final Pattern ARIA = Pattern.compile("(?i)\\baria-label\\s*=\\s*['\\\"]([^'\\\"]+)");
  private static final Pattern TEST_ID = Pattern.compile("(?i)\\b(?:data-testid|data-test-id)\\s*=\\s*['\\\"]([^'\\\"]+)");

  private SourceFactExtractor() { }

  public static SourceFacts extract(SourceReadFile file) {
    if (file == null) throw new IllegalArgumentException("source file is required");
    String content = file.chunks().stream().map(SourceReadChunk::content).reduce("", String::concat);
    var symbols = new LinkedHashSet<String>();
    var dependencies = new LinkedHashSet<String>();
    var routes = new LinkedHashSet<String>();
    var dataStores = new LinkedHashSet<String>();
    var configKeys = new LinkedHashSet<String>();
    var uiTargets = new LinkedHashSet<String>();
    collect(SYMBOL, content, symbols);
    collect(JAVA_IMPORT, content, dependencies);
    collect(MODULE_IMPORT, content, dependencies);
    collect(REQUIRE, content, dependencies);
    collectRoutes(SPRING_ROUTE, content, routes, true);
    collectRoutes(JS_ROUTE, content, routes, false);
    collect(OBJECT_ROUTE, content, routes);
    collect(TABLE, content, dataStores);
    collect(CONFIG, content, configKeys);
    collect(ARIA, content, uiTargets);
    collect(TEST_ID, content, uiTargets);
    return new SourceFacts(file.path(), sorted(symbols), sorted(routes), sorted(dataStores),
        sorted(dependencies), sorted(configKeys), sorted(uiTargets));
  }

  private static void collect(Pattern pattern, String content, Set<String> values) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) values.add(matcher.group(1).strip());
  }

  private static void collectRoutes(Pattern pattern, String content, Set<String> values, boolean spring) {
    Matcher matcher = pattern.matcher(content);
    while (matcher.find()) {
      String method = spring ? matcher.group(1).toUpperCase(java.util.Locale.ROOT) : matcher.group(1).toUpperCase(java.util.Locale.ROOT);
      values.add(method + " " + matcher.group(2).strip());
    }
  }

  private static List<String> sorted(Set<String> values) {
    return values.stream().filter(value -> !value.isBlank()).sorted(Comparator.comparing(String::toLowerCase)).toList();
  }
}
record SourceFacts(String filePath, List<String> symbols, List<String> routes, List<String> dataStores,
                   List<String> dependencies, List<String> configKeys, List<String> uiTargets) {
  SourceFacts {
    symbols = List.copyOf(symbols == null ? List.of() : symbols);
    routes = List.copyOf(routes == null ? List.of() : routes);
    dataStores = List.copyOf(dataStores == null ? List.of() : dataStores);
    dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
    configKeys = List.copyOf(configKeys == null ? List.of() : configKeys);
    uiTargets = List.copyOf(uiTargets == null ? List.of() : uiTargets);
  }

  List<String> all() {
    var result = new ArrayList<String>();
    result.addAll(symbols);
    result.addAll(routes);
    result.addAll(dataStores);
    result.addAll(dependencies);
    result.addAll(configKeys);
    result.addAll(uiTargets);
    return List.copyOf(result);
  }
}
