package studio.agent.platform.product;

import java.util.List;
import java.util.TreeSet;
import tools.jackson.databind.ObjectMapper;

final class ProviderResponseParser {
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final int MAX_MODELS = 500;

  private ProviderResponseParser() { }

  static List<String> parseModels(byte[] body) {
    try {
      var root = JSON.readTree(body);
      var data = root == null ? null : root.get("data");
      if (data == null || !data.isArray()) throw new IllegalArgumentException("provider model response is invalid");
      if (data.size() > MAX_MODELS) throw new IllegalArgumentException("provider returned too many models");
      var models = new TreeSet<String>();
      for (var item : data) {
        var id = item.get("id");
        if (id == null || !id.isString()) continue;
        String value = id.asString().trim();
        if (!value.isEmpty() && value.length() <= 255 && value.chars().noneMatch(Character::isISOControl)) models.add(value);
      }
      return List.copyOf(models);
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalArgumentException("provider model response is invalid");
    }
  }
}
