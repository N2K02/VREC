# App-specific keep rules go here. Default Android optimized rules are applied in Gradle.
# Keep reflection- or JNI-accessed APIs here after release instrumentation tests.

# Keep the no-arg constructors used reflectively by WorkManager/Room.
# Release R8 full mode can otherwise remove WorkDatabase_Impl.<init>(),
# causing androidx.startup.InitializationProvider to crash before Application.onCreate.
# Keep constructors, not entire packages, so release shrinking stays enabled.
-keep class * extends androidx.room.RoomDatabase {
    <init>();
}

# WorkManager also instantiates custom/default InputMergers by class name.
# Preserve constructors required during task deserialization/recovery.
-keep class * extends androidx.work.InputMerger {
    <init>();
}
