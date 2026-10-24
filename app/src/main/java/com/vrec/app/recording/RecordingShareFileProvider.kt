package com.vrec.app.recording

import androidx.core.content.FileProvider

/**
 * Separate identity from the shared updater's FileProvider in the merged Android manifest.
 * Keeps the existing <applicationId>.files authority for sharing user recordings.
 */
class RecordingShareFileProvider : FileProvider()
