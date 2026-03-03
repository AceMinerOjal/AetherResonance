package tile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class SimpleJsonParser {
  private final String text;
  private int index;

  private SimpleJsonParser(String text) {
    this.text = text;
  }

  public static Object parse(String text) {
    SimpleJsonParser parser = new SimpleJsonParser(text);
    Object value = parser.parseValue();
    parser.skipWhitespace();
    if (!parser.isEnd()) {
      throw parser.error("Unexpected trailing content");
    }
    return value;
  }

  private Object parseValue() {
    skipWhitespace();
    if (isEnd()) {
      throw error("Unexpected end of input");
    }

    char c = current();
    return switch (c) {
      case '{' -> parseObject();
      case '[' -> parseArray();
      case '"' -> parseString();
      case 't' -> parseLiteral("true", Boolean.TRUE);
      case 'f' -> parseLiteral("false", Boolean.FALSE);
      case 'n' -> parseLiteral("null", null);
      default -> {
        if (c == '-' || Character.isDigit(c)) {
          yield parseNumber();
        }
        throw error("Unexpected character: " + c);
      }
    };
  }

  private Map<String, Object> parseObject() {
    expect('{');
    skipWhitespace();
    Map<String, Object> object = new LinkedHashMap<>();
    if (peek('}')) {
      expect('}');
      return object;
    }

    while (true) {
      skipWhitespace();
      String key = parseString();
      skipWhitespace();
      expect(':');
      Object value = parseValue();
      object.put(key, value);
      skipWhitespace();
      if (peek(',')) {
        expect(',');
        continue;
      }
      expect('}');
      break;
    }
    return object;
  }

  private List<Object> parseArray() {
    expect('[');
    skipWhitespace();
    List<Object> array = new ArrayList<>();
    if (peek(']')) {
      expect(']');
      return array;
    }

    while (true) {
      array.add(parseValue());
      skipWhitespace();
      if (peek(',')) {
        expect(',');
        continue;
      }
      expect(']');
      break;
    }
    return array;
  }

  private String parseString() {
    expect('"');
    StringBuilder sb = new StringBuilder();
    while (!isEnd()) {
      char c = current();
      index++;
      if (c == '"') {
        return sb.toString();
      }
      if (c == '\\') {
        if (isEnd()) {
          throw error("Unterminated escape sequence");
        }
        char esc = current();
        index++;
        switch (esc) {
          case '"', '\\', '/' -> sb.append(esc);
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> sb.append(parseUnicodeEscape());
          default -> throw error("Invalid escape: \\" + esc);
        }
      } else {
        sb.append(c);
      }
    }
    throw error("Unterminated string");
  }

  private char parseUnicodeEscape() {
    if (index + 4 > text.length()) {
      throw error("Incomplete unicode escape");
    }
    int codePoint = 0;
    for (int i = 0; i < 4; i++) {
      char c = text.charAt(index++);
      int digit = Character.digit(c, 16);
      if (digit < 0) {
        throw error("Invalid unicode escape digit: " + c);
      }
      codePoint = (codePoint << 4) | digit;
    }
    return (char) codePoint;
  }

  private Object parseLiteral(String literal, Object value) {
    if (text.regionMatches(index, literal, 0, literal.length())) {
      index += literal.length();
      return value;
    }
    throw error("Expected literal: " + literal);
  }

  private Number parseNumber() {
    int start = index;
    if (peek('-')) {
      index++;
    }
    parseDigits();

    boolean isDouble = false;
    if (peek('.')) {
      isDouble = true;
      index++;
      parseDigits();
    }
    if (peek('e') || peek('E')) {
      isDouble = true;
      index++;
      if (peek('+') || peek('-')) {
        index++;
      }
      parseDigits();
    }

    String raw = text.substring(start, index);
    try {
      if (isDouble) {
        return Double.parseDouble(raw);
      }
      return Long.parseLong(raw);
    } catch (NumberFormatException ex) {
      throw error("Invalid number: " + raw);
    }
  }

  private void parseDigits() {
    if (isEnd() || !Character.isDigit(current())) {
      throw error("Expected a digit");
    }
    while (!isEnd() && Character.isDigit(current())) {
      index++;
    }
  }

  private void skipWhitespace() {
    while (!isEnd()) {
      char c = current();
      if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
        index++;
      } else {
        break;
      }
    }
  }

  private boolean isEnd() {
    return index >= text.length();
  }

  private char current() {
    return text.charAt(index);
  }

  private boolean peek(char c) {
    return !isEnd() && current() == c;
  }

  private void expect(char c) {
    if (isEnd() || current() != c) {
      throw error("Expected '" + c + "'");
    }
    index++;
  }

  private IllegalArgumentException error(String message) {
    return new IllegalArgumentException(message + " at index " + index);
  }
}
