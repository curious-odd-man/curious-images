package com.github.curiousoddman.curious_images.domain.imports;

/**
 * One file worth calling out from an import run — either it was skipped for a specific reason
 * (unsupported codec/extension — NOT routine "unchanged" skips, see {@link ImportFileIssueType})
 * or it failed outright. Persisted one row per instance in {@code IMPORT_JOB_FILE_ISSUE}, keyed to
 * its run via {@code run_id} (see {@code ImportJobStatsRepository}), and shown in the Last Import
 * view as two sections — Skipped and Failed — each grouped by {@link #reason()}.
 *
 * @param absolutePath the file this issue is about
 * @param type         SKIPPED or FAILED
 * @param reason       human-readable reason: an exception message for FAILED, or e.g.
 *                     "Unsupported codec" / "Unsupported extension: .heic" for SKIPPED
 */
public record ImportFileIssue(String absolutePath, ImportFileIssueType type, String reason) {}
