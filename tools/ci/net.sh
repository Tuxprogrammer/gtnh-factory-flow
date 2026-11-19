# Sourced by the CI job scripts that run Gradle. Sets GRADLE_NET_OPTS, the command-line options every Gradle call in CI takes:
# the daemon heap (Gradle's own default of 512 MiB dies thrashing in the mod's downgradeJar) and the HTTP settings that make
# a transient server failure cost seconds instead of the job.
#
# Gradle retries a failed dependency request itself, but by default 3 times with 125 ms between them, which a server that
# resets connections for a minute outlasts. Here: 6 attempts with a 2 s first back-off that doubles (2+4+8+16+32 s per
# request), the same for file downloads, and 60 s connect and read timeouts. A 404 is an answer, not a failure, and is never
# retried. The properties are passed as daemon JVM arguments so they are set before Gradle reads them.
GRADLE_NET_JVMARGS="-Xmx3g -XX:MaxMetaspaceSize=768m -Dfile.encoding=UTF-8"
GRADLE_NET_JVMARGS+=" -Dorg.gradle.internal.repository.max.tentatives=6 -Dorg.gradle.internal.repository.initial.backoff=2000"
GRADLE_NET_JVMARGS+=" -Dorg.gradle.internal.network.retry.max.attempts=6 -Dorg.gradle.internal.network.retry.initial.backOff=2000"
GRADLE_NET_JVMARGS+=" -Dorg.gradle.internal.http.connectionTimeout=60000 -Dorg.gradle.internal.http.socketTimeout=60000"
GRADLE_NET_OPTS=("-Dorg.gradle.jvmargs=$GRADLE_NET_JVMARGS")
