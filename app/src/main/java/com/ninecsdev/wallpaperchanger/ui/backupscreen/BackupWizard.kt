package com.ninecsdev.wallpaperchanger.ui.backupscreen

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.ninecsdev.wallpaperchanger.R
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportFailure
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportPlan
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportProgress
import com.ninecsdev.wallpaperchanger.data.backup.BackupImportSummary
import com.ninecsdev.wallpaperchanger.data.backup.BackupManifest
import com.ninecsdev.wallpaperchanger.data.backup.BackupMode
import com.ninecsdev.wallpaperchanger.data.backup.FolderPrompt
import com.ninecsdev.wallpaperchanger.ui.components.NothingButton
import com.ninecsdev.wallpaperchanger.ui.components.NothingButtonVariant
import com.ninecsdev.wallpaperchanger.ui.components.NothingProgressBar
import com.ninecsdev.wallpaperchanger.ui.theme.NothingBlack
import com.ninecsdev.wallpaperchanger.ui.theme.NothingRed
import com.ninecsdev.wallpaperchanger.ui.theme.NothingType
import com.ninecsdev.wallpaperchanger.ui.theme.NothingWhite
import com.ninecsdev.wallpaperchanger.ui.theme.WallpaperChangerTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The restore wizard, one step at a time, rendered in the body of the Backup screen.
 *
 * Every step before [BackupImportStep.Working] is a question, and backing out at any of them
 * doesn't do anything. Each of those steps says so, and says which of them it is, so the folder
 * loop has a visible end.
 */
@Composable
internal fun BackupWizard(
    step: BackupImportStep,
    actions: BackupImportActions,
    onChooseArchive: () -> Unit,
    onPickFolder: (initialUri: String?) -> Unit,
    onStartService: () -> Unit,
    onFinish: () -> Unit
) {
    when (step) {
        BackupImportStep.Reading -> {
            Status(stringResource(R.string.backup_import_reading))
            SafeToLeave()
        }
        is BackupImportStep.Review -> ReviewStep(step.plan, actions::onReviewConfirmed, onChooseArchive)
        is BackupImportStep.Folder -> FolderStep(step, onPickFolder, actions::onFolderSkipped)
        is BackupImportStep.Working -> WorkingStep(step.progress)
        is BackupImportStep.Done -> DoneStep(step.summary, onStartService, onFinish)
        is BackupImportStep.Failed -> FailedStep(step.failure, onFinish)
    }
}

/** How many steps the restore has in total: reviewing, (then the folder prompts), then the restore */
private fun totalSteps(plan: BackupImportPlan) = if (plan.folders.isEmpty()) 2 else 3

@Composable
private fun ReviewStep(plan: BackupImportPlan, onConfirm: () -> Unit, onChooseArchive: () -> Unit) {
    Meta(stringResource(R.string.backup_import_step, 1, totalSteps(plan)))
    Title(stringResource(R.string.backup_import_review_title), NothingRed)
    Body(
        stringResource(
            R.string.backup_import_review_body,
            pluralStringResource(R.plurals.backup_import_collections, plan.collections, plan.collections),
            pluralStringResource(R.plurals.backup_import_images, plan.images, plan.images)
        )
    )
    MetaBlock {
        // Which file this is, because two backups of the same install differ in nothing else on screen.
        if (plan.archiveName != null) {
            Meta(stringResource(R.string.backup_import_review_file, plan.archiveName))
        }
        Meta(stringResource(R.string.backup_import_review_meta, formatDate(plan.manifest.exportedAt)))
        Meta(
            stringResource(
                if (plan.manifest.mode == BackupMode.PORTABLE) R.string.backup_import_review_portable
                else R.string.backup_import_review_device
            )
        )
    }
    SafeToLeave()
    NothingButton(
        text = stringResource(R.string.backup_import_review_continue),
        onClick = onConfirm
    )
    NothingButton(
        text = stringResource(R.string.backup_import_choose_different),
        onClick = onChooseArchive,
        variant = NothingButtonVariant.SECONDARY
    )
}

@Composable
private fun FolderStep(
    step: BackupImportStep.Folder,
    onPickFolder: (String?) -> Unit,
    onSkip: () -> Unit
) {
    Meta(stringResource(R.string.backup_import_step_folder, 2, 3, step.index, step.total))
    Title(stringResource(R.string.backup_import_folder_title), NothingWhite)
    Body(
        stringResource(
            R.string.backup_import_folder_body,
            step.prompt.name,
            pluralStringResource(R.plurals.backup_import_images, step.prompt.images, step.prompt.images)
        )
    )
    // The cost of skipping, named
    if (step.prompt.bundledImages > 0) {
        Text(
            text = stringResource(R.string.backup_import_folder_bundled_warning, step.prompt.bundledImages),
            style = NothingType.caption,
            color = NothingRed
        )
    }
    SafeToLeave()
    NothingButton(
        text = stringResource(R.string.backup_import_folder_pick),
        onClick = { onPickFolder(step.prompt.rootUri) }
    )
    NothingButton(
        text = stringResource(R.string.backup_import_folder_skip),
        onClick = onSkip,
        variant = NothingButtonVariant.SECONDARY
    )
}

@Composable
private fun WorkingStep(progress: BackupImportProgress) {
    Status(stringResource(R.string.backup_import_working, progress.done, progress.total))
    NothingProgressBar(done = progress.done, total = progress.total)
}

@Composable
private fun DoneStep(summary: BackupImportSummary, onStartService: () -> Unit, onFinish: () -> Unit) {
    Title(stringResource(R.string.backup_import_done_title), NothingWhite)
    Body(
        stringResource(
            R.string.backup_import_done_body,
            pluralStringResource(R.plurals.backup_import_collections, summary.collections, summary.collections),
            pluralStringResource(R.plurals.backup_import_images, summary.images, summary.images)
        )
    )
    // Wrapped only when there is something to wrap: an empty block would still take a gap.
    if (summary.unavailable > 0 || summary.skippedCollections > 0) {
        MetaBlock {
            if (summary.unavailable > 0) {
                Meta(stringResource(R.string.backup_import_done_unavailable, summary.unavailable))
            }
            if (summary.skippedCollections > 0) {
                Meta(stringResource(R.string.backup_import_done_skipped, summary.skippedCollections))
            }
        }
    }
    NothingButton(
        text = stringResource(R.string.backup_import_start_service),
        onClick = {
            onStartService()
            onFinish()
        }
    )
    NothingButton(
        text = stringResource(R.string.backup_import_finish),
        onClick = onFinish,
        variant = NothingButtonVariant.SECONDARY
    )
}

@Composable
private fun FailedStep(failure: BackupImportFailure, onFinish: () -> Unit) {
    Title(stringResource(R.string.backup_import_failed_title), NothingRed)
    Body(
        when (failure) {
            BackupImportFailure.NotAnArchive -> stringResource(R.string.backup_import_failed_not_archive)
            is BackupImportFailure.NewerFormat -> stringResource(R.string.backup_import_failed_newer)
            is BackupImportFailure.Malformed -> stringResource(R.string.backup_import_failed_malformed)
            BackupImportFailure.Incomplete -> stringResource(R.string.backup_import_failed_incomplete)
            BackupImportFailure.Interrupted -> stringResource(R.string.backup_import_failed_interrupted)
            is BackupImportFailure.NotEnoughSpace -> stringResource(
                R.string.backup_import_failed_space,
                Formatter.formatFileSize(LocalContext.current, failure.neededBytes)
            )
            is BackupImportFailure.Unexpected -> stringResource(R.string.backup_import_failed_unexpected)
        }
    )

    if (failure != BackupImportFailure.Interrupted) {
        Meta(stringResource(R.string.backup_import_nothing_changed))
    }

    NothingButton(
        text = stringResource(R.string.backup_import_finish),
        onClick = onFinish,
        variant = NothingButtonVariant.SECONDARY
    )
}

/** True of every step that is still a question */
@Composable
private fun SafeToLeave() = Meta(stringResource(R.string.backup_import_safe_to_leave))

@Composable
private fun Title(text: String, color: Color) {
    Text(text = text, style = NothingType.titleCaps, color = color)
}

@Composable
private fun Body(text: String) {
    Text(text = text, style = NothingType.caption, color = NothingWhite.copy(alpha = 0.75f))
}

/** Consecutive [Meta] lines, close enough to read as one block instead of separate statements */
@Composable
private fun MetaBlock(content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
}

@Composable
private fun Meta(text: String) {
    Text(text = text, style = NothingType.caption, color = NothingWhite.copy(alpha = 0.4f))
}

@Composable
private fun Status(text: String) {
    Text(text = text, style = NothingType.overline, color = NothingWhite)
}

private fun formatDate(epochMillis: Long): String =
    DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
        .format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

// Previews

/**
 * Preview fixtures, shared with [BackupScreen]'s previews so the two can't drift.
 *
 * A plan's manifest only has to answer for its date and its mode here: the counts the review step
 * shows are the plan's own, not the lists'.
 */
private val PreviewManifest = BackupManifest(
    exportedAt = 1_757_000_000_000,
    collections = emptyList(),
    files = emptyList(),
    memberships = emptyList(),
    exclusions = emptyList()
)

internal val PreviewImportPlan = BackupImportPlan(
    manifest = PreviewManifest,
    archiveName = "NWC-backup-2026-09-12.nwcbak",
    collections = 4,
    images = 312,
    folders = emptyList()
)

private val PreviewFolderPrompt = FolderPrompt(
    collectionIndex = 0,
    name = "Cyberpunk",
    rootUri = "content://com.android.externalstorage.documents/tree/primary%3APictures",
    images = 84,
    bundledImages = 12,
    folderKey = "cyberpunk"
)

/** Portable, and with folder prompts waiting, so the review reads "step 1 of 3" */
private val PreviewPortablePlan = PreviewImportPlan.copy(
    manifest = PreviewManifest.copy(mode = BackupMode.PORTABLE),
    folders = List(2) { PreviewFolderPrompt }
)

internal object PreviewImportActions : BackupImportActions {
    override fun onArchivePicked(uri: String) {}
    override fun onReviewConfirmed() {}
    override fun onMediaAccessSettled() {}
    override fun onFolderPicked(treeUri: String) {}
    override fun onFolderSkipped() {}
    override fun onDismissed() {}
}

/** One step, spaced the way [BackupScreen] spaces it */
@Composable
private fun PreviewStep(step: BackupImportStep) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BackupWizard(
            step = step,
            actions = PreviewImportActions,
            onChooseArchive = {},
            onPickFolder = {},
            onStartService = {},
            onFinish = {}
        )
    }
}

/** Several steps at once, far enough apart to tell where one ends */
@Composable
private fun PreviewStack(content: @Composable ColumnScope.() -> Unit) {
    WallpaperChangerTheme {
        Column(
            modifier = Modifier
                .background(NothingBlack)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(32.dp),
            content = content
        )
    }
}
@Preview(name = "Wizard · questions", backgroundColor = 0xFF000000)
@Preview(name = "Wizard · questions (es)", backgroundColor = 0xFF000000, locale = "es")
@Composable
private fun BackupWizardQuestionsPreview() = PreviewStack {
    PreviewStep(BackupImportStep.Reading)
    // Device archive, no folders to ask about: the shortest restore there is, 2 steps.
    PreviewStep(BackupImportStep.Review(PreviewImportPlan))
    // Portable, with folders: the same step now counts to 3.
    PreviewStep(BackupImportStep.Review(PreviewPortablePlan))
}

/*@Preview(name = "Wizard · folders", backgroundColor = 0xFF000000)
@Preview(name = "Wizard · questions (es)", backgroundColor = 0xFF000000, locale = "es")
@Composable
private fun BackupWizardFolderPreview() = PreviewStack {
    // Bundled images make skipping cost something, and the warning appears.
    PreviewStep(BackupImportStep.Folder(PreviewFolderPrompt, index = 1, total = 2))
    // Nothing bundled: skipping loses only the references, and there's no warning.
    PreviewStep(
        BackupImportStep.Folder(
            PreviewFolderPrompt.copy(name = "Monochrome", images = 31, bundledImages = 0),
            index = 2,
            total = 2
        )
    )
}

/*@Preview(name = "Wizard · outcomes", backgroundColor = 0xFF000000)
@Preview(name = "Wizard · questions (es)", backgroundColor = 0xFF000000, locale = "es")
@Composable
private fun BackupWizardOutcomePreview() = PreviewStack {
    PreviewStep(BackupImportStep.Working(BackupImportProgress(done = 128, total = 312)))
    // Indeterminate: the archive is open but its image count isn't known yet.
    PreviewStep(BackupImportStep.Working(BackupImportProgress(done = 0, total = 0)))
    PreviewStep(
        BackupImportStep.Done(
            BackupImportSummary(collections = 4, images = 312, unavailable = 0, skippedCollections = 0)
        )
    )
    // Both caveats at once, which is the tallest this step gets.
    PreviewStep(
        BackupImportStep.Done(
            BackupImportSummary(collections = 3, images = 228, unavailable = 17, skippedCollections = 1)
        )
    )
}

@Preview(name = "Wizard · failures", backgroundColor = 0xFF000000, heightDp = 1400)
@Composable
private fun BackupWizardFailurePreview() = PreviewStack {
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.NotAnArchive))
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.NewerFormat(formatVersion = 2)))
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.Malformed(detail = "manifest.json")))
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.Incomplete))
    // The only failure that doesn't get to say nothing was changed.
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.Interrupted))
    PreviewStep(
        BackupImportStep.Failed(
            BackupImportFailure.NotEnoughSpace(
                neededBytes = 412L * 1024 * 1024,
                freeBytes = 90L * 1024 * 1024
            )
        )
    )
    PreviewStep(BackupImportStep.Failed(BackupImportFailure.Unexpected(detail = "IOException")))
}
*/