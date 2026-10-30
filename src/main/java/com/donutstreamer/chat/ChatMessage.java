package com.donutstreamer.chat;

import net.minecraft.text.Text;

import java.util.List;

/** One line of stream chat. Layout fields are filled lazily on the render thread (text widths need the font). */
final class ChatMessage {
    static final int TWITCH = 0, YOUTUBE = 1;

    final int platform;
    final String name;
    final String text;
    final int color;          // ARGB name colour
    final long time;

    // render-thread cache (rebuilt when the panel width or any chat option changes)
    long laidKey = Long.MIN_VALUE;
    String nameShown = "";
    Text nameText = null;     // bold version of the name, or null when bold names are off
    int nameWidth;
    int colonWidth;
    String timeText = "";
    int timeWidth;
    List<String> lines = List.of("");   // the whole message, word-wrapped

    ChatMessage(int platform, String name, String text, int color) {
        this.platform = platform;
        this.name = name;
        this.text = text;
        this.color = color;
        this.time = System.currentTimeMillis();
    }
}
