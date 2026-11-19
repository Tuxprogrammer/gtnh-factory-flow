package com.gtnhplanner.core.view.note;

import javax.annotation.Nullable;

/** A sticky note's colour tag (one of the sixteen dyes or the app's own inks); none, or one nobody knows, reads as yellow. */
public record NoteLookInput(@Nullable String tag) {}
