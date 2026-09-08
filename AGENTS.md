# Agent Notes

## Gradle troubleshooting

This project uses the Gradle wrapper and a repository-local Gradle user home. On Windows, if `GRADLE_USER_HOME` is unset, the wrapper may try to create its lock file under `C:\.gradle`, which can fail before Gradle starts.

Run Gradle from PowerShell with the local cache selected:

```powershell
$env:GRADLE_USER_HOME = (Resolve-Path .gradle-user).Path
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

The repository also contains generated Gradle daemon toolchain metadata in `gradle/gradle-daemon-jvm.properties`. It selects a downloaded JDK 25 under `.gradle-user/jdks`. In restricted environments, Gradle can then fail with `Error loading java.security file` or an `AccessDeniedException` while reading that JDK. This is an environment permission problem, not an application compilation error; rerun the same wrapper command with permission to read the configured local JDK/cache.

`.gradle-user/` and `gradle/gradle-daemon-jvm.properties` are local/generated Gradle state. Do not treat them as application source changes or edit the generated toolchain file as a first-line fix.

## Git sandbox troubleshooting

In the managed sandbox, Git may fail with `Unable to create '.git/index.lock': Permission denied` when staging or committing. First verify that `.git/index.lock` does not already exist; then rerun the same Git operation with permission to write the repository's `.git` directory. Do not remove an existing lock file unless you have confirmed that no Git process is still running and the lock is stale.
