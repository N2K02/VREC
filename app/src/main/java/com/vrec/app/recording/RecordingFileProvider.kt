package com.vrec.app.recording

import androidx.core.content.FileProvider

/**
 * Independent provider for sharing saved recordings. The shared updater uses its
 * own AndroidX FileProvider at ${applicationId}.n2k02updater.files, so using the
 * same provider class here would merge the two manifest entries and replace the
 * updater's required authority and FILE_PROVIDER_PATHS metadata.
 */
class RecordingFileProvider : FileProvider()
