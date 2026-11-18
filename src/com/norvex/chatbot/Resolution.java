package com.norvex.chatbot;

import com.google.appinventor.components.common.OptionList;
import java.util.HashMap;
import java.util.Map;

/**
 * Helper dropdown untuk resolusi gambar (CallGenerateImage).
 * Nilai underlying = nama helper yang dimengerti halaman backend:
 * square (512x512), portrait (512x768), landscape (768x512), square_hd (768x768).
 */
public enum Resolution implements OptionList<String> {
  Square("square"),
  Portrait("portrait"),
  Landscape("landscape"),
  SquareHd("square_hd");

  private final String value;

  Resolution(String value) {
    this.value = value;
  }

  @Override
  public String toUnderlyingValue() {
    return value;
  }

  private static final Map<String, Resolution> lookup = new HashMap<String, Resolution>();

  static {
    for (Resolution r : Resolution.values()) {
      lookup.put(r.toUnderlyingValue(), r);
    }
  }

  public static Resolution fromUnderlyingValue(String value) {
    return lookup.get(value);
  }
}
