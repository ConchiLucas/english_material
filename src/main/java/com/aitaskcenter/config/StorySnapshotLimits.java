package com.aitaskcenter.config;

public final class StorySnapshotLimits {
    public static final int MAX_WORDS = 50;
    public static final int MAX_WORD_LENGTH = 120;
    public static final int MAX_MEANING_LENGTH = 500;
    public static final int MAX_WORD_SNAPSHOT_CHARS = 64 * 1024;
    public static final int MAX_FINAL_STORY_CHARS = 20_000;

    private StorySnapshotLimits() {
    }
}
