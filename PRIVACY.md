# Privacy

Reson processes speech recordings on the device. It has no internet permission, analytics, telemetry or app-controlled upload service.

## Microphone and recordings

Reson requests Android's microphone permission only when you choose Record. Audio is captured as mono, 16-bit PCM and saved as a WAV file in Reson's private app storage. Completed recordings remain there until you delete them or uninstall Reson.

Recording stops when Reson moves to the background. If capture fails or Android reports that microphone input has been silenced, Reson reports the failure and does not publish that session. An unfinished `.pending` recording left by a process or device failure is removed the next time Reson starts; it cannot be recovered by the app.

## Import, export and sharing

When you import a supported PCM16 WAV, Reson reads the selected document and saves a mono analysis copy in its private recording library. Stereo channels are averaged. The selected source document is not modified. The TextGrid viewer reads a selected TextGrid on the device and does not edit or save over it.

Export uses Android's document picker to copy the selected WAV to a destination you choose. Sharing opens Android's share sheet and grants the chosen app temporary read access to that recording. A recipient may keep its own copy; deleting the Reson recording cannot remove copies made elsewhere.

## Local settings and deletion

Recording display names, the IPA transcription draft, recent IPA symbols and appearance preference are stored locally in Reson's private app data. Reson sets `android:allowBackup="false"` and does not implement its own cloud backup or sync. Android documents that some device manufacturers may still transfer app data during device-to-device migration even when this flag is false; see [Android Auto Backup guidance](https://developer.android.com/identity/data/autobackup).

Deleting a recording removes its WAV file from Reson's library and clears its saved display name. It does not securely overwrite storage and does not delete exported or shared copies.
