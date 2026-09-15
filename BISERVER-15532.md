# BISERVER-15532 — Import/Export restore reports "Restore complete" after a Tomcat session timeout

| Field | Value |
| --- | --- |
| Issue | BISERVER-15532 |
| Component | Pentaho Server — Import/Export utility (`import-export.sh` / `import-export.bat`), `--restore` mode |
| Affected version | 11.1.0.0-SNAPSHOT (master) and earlier |
| Repositories touched | `pentaho-platform`, `pentaho-scheduler-plugin` |
| Severity | Medium — data loss risk (silent partial restore) |

---

## 1. Summary

When a full system restore runs for longer than the servlet container session timeout, the
HTTP session backing the request is invalidated **while the restore is still in progress**.
Every repository, security and scheduler call issued after that point fails, but:

* the `systemRestore` endpoint still returns **HTTP 200**;
* the restore log ends with **`Restore complete`**;
* the individual failures are logged with misleading, unrelated messages such as
  `Could not import: General Error : exception while getting file with path "/home/admin/TransformsAndJobs"`.

The user is therefore told the restore succeeded when in reality a large part of the backup was
never restored, and the log gives no clue about the actual cause (the session timeout) or the
remedy (increase `<session-timeout>`).

---

## 2. Root cause

`import-export.sh --restore` POSTs the backup bundle to `POST /pentaho/api/repo/files/systemRestore`
and blocks until the server finishes. The whole restore therefore executes **synchronously on the
Tomcat request thread**.

Tomcat only refreshes `StandardSession.thisAccessedTime` when a request *starts*. A request that
runs for hours never refreshes it, so Tomcat's background session reaper eventually expires the
session mid-request:

```
java.lang.IllegalStateException: getAttribute: Session already invalidated
    at org.apache.catalina.session.StandardSession.getAttribute(StandardSession.java:876)
    at org.apache.catalina.session.StandardSessionFacade.getAttribute(StandardSessionFacade.java:89)
    at org.pentaho.platform.web.http.session.PentahoHttpSession.getAttribute(PentahoHttpSession.java:64)
    at org.pentaho.platform.repository2.unified.jcr.JcrTenantUtils.getCurrentTenant(JcrTenantUtils.java:148)
    ...
    at org.pentaho.platform.plugin.services.importer.SolutionImportHandler.importFile(SolutionImportHandler.java:185)
    at org.pentaho.platform.plugin.services.importer.PentahoPlatformImporter.importFile(PentahoPlatformImporter.java:120)
    at org.pentaho.platform.web.http.api.resources.services.FileService.systemRestore(FileService.java:255)
```

From there the failure is masked three times over:

1. `ExceptionLoggingDecorator.callLogThrow()` (`pentaho-platform/repository/.../ExceptionLoggingDecorator.java`)
   swallows the root cause and rethrows a generic `UnifiedRepositoryException`
   (`ExceptionLoggingDecorator.generalException` → *"General Error : exception while getting file with path …"*).
2. `SolutionImportHandler.importRepositoryFilesAndFolders()` and `ScheduleImportUtil.doImport()`
   intentionally catch per-item exceptions and keep going, so a single failure never aborts the job.
3. `FileService.systemRestore()` returned normally, `FileResource.systemRestore()` returned
   `Response.ok()`, and `CommandLineProcessor.logResponseMessage()` printed
   `CommandLineProcessor.INFO_RESTORE_SUCCESSFUL` = `Restore complete`.

The timeout is configured in the deployed webapp descriptor
`pentaho-server/tomcat/webapps/pentaho/WEB-INF/web.xml` (**not** in `tomcat/conf/web.xml`, which is
overridden by the webapp value):

```xml
<session-config>
    <tracking-mode>COOKIE</tracking-mode>
    <session-timeout>120</session-timeout>   <!-- minutes -->
</session-config>
```

The `localhost_access.<date>.log` entry with the `%T` valve confirms the request outlived it:

```
127.0.0.1 - - [23/Feb/2026:14:09:04 -0600] "POST /pentaho/api/repo/files/systemRestore HTTP/1.1" 200 - [7245]
```

`7245 s > 7200 s` (120 min).

---

## 3. How to reproduce

> A 1-minute timeout is used instead of 120 minutes so the failure reproduces in ~90 seconds.

1. Stop the server.
2. Set a very small session timeout in the **deployed** descriptor
   `pentaho-server/tomcat/webapps/pentaho/WEB-INF/web.xml`:
   ```xml
   <session-timeout>1</session-timeout>
   ```
3. (Optional) add the `%T` field to the access log valve in `pentaho-server/tomcat/conf/server.xml`
   so the request duration is recorded:
   ```xml
   <Valve className="org.pentaho.tomcat.logvalve.FilteredAccessLogValve" directory="logs"
          prefix="localhost_access" suffix=".log"
          pattern="%h %l %u %t &quot;%r&quot; %s %b [%T]" />
   ```
4. Start the server and take a backup that takes longer than the timeout to restore
   (the out-of-the-box sample content is enough — the restore takes ~100 s).
5. Run the restore and observe the misleading result.

### Expected before the fix
* Console: `Restore complete`
* `restore.log`: dozens of `Could not import: General Error : exception while getting file with path …`
  followed by `End of Log` / `Restore complete`
* `catalina.out`: many `IllegalStateException: getAttribute: Session already invalidated`

### Expected after the fix
* Console:
  ```
  CommandLineProcessor.ERROR_0012 - The restore did not complete successfully. The server reported the following error:
  The restore did not complete: the HTTP session expired while the restore was still running, so the server
  rejected the remaining repository, security and scheduler operations. Any errors logged above this line are a
  consequence of the expired session and not of the backup content. Increase the session timeout on the Pentaho
  Server (the <session-timeout> element of tomcat/webapps/pentaho/WEB-INF/web.xml, expressed in minutes) so that
  it is longer than the time the restore needs, restart the server and run the restore again.
  ```
* `restore.log` ends with the same actionable `ERROR` line instead of `Restore complete`.
* HTTP status of `POST /api/repo/files/systemRestore` is `500` instead of `200`.

---

## 4. The fix

### 4.1 `pentaho-platform`

#### New — `extensions/src/main/java/org/pentaho/platform/plugin/services/importexport/SessionExpirationDetector.java`
A small, injectable (non-static) collaborator that answers two questions:

* `isSessionExpired( IPentahoSession )` — probes the session by reading a throw-away attribute;
  any `IllegalStateException` from that private attribute read means the container already
  invalidated the session. This avoids depending on Tomcat's locale-specific exception text.
* `isCausedBySessionExpiration( Throwable )` — walks the cause chain (loop-safe against circular
  causes) looking for either the English marker or the locale-independent Tomcat
  `StandardSession.getAttribute` stack frame, for the case where the exception escapes.

#### `extensions/.../web/http/api/resources/services/FileService.java`
`systemRestore(...)` now:

* catches `PlatformImportException` and `RuntimeException` raised by `importer.importFile(...)`,
  replacing only session-expiration failures and re-throwing unrelated failures unchanged;
* probes the current `PentahoSessionHolder` session in the `finally` block;
* when the session expired, writes an actionable `ERROR` entry into the restore log **before**
  `importLogger.endJob()` so it lands inside the generated log file;
* throws a `PlatformImportException` tagged `SESSION_EXPIRED` afterwards so the failure is no longer
  silent and the REST layer can tell a curated message from an internal one, chaining the failure
  that revealed the expiration (when there was one) so the server log keeps its stack;
* closes the restore-log output stream on both success and failure;
* replaces the stray `e.printStackTrace()` with proper SLF4J/commons-logging output.

#### `extensions/.../plugin/services/importer/PlatformImportException.java`
New status code `SESSION_EXPIRED = 14`. It is the machine-readable marker that says *this exception's
message is a curated, user-facing explanation and is therefore safe to return to the client*. Every
other status may carry internal detail (repository paths, JCR errors) and must not be echoed. This
keeps the decision in the exception contract instead of duplicating detection logic in the REST layer.

#### `extensions/.../web/http/api/resources/FileResource.java`
`systemRestore(...)` delegates to a new `buildRestoreFailureResponse(...)`, which returns the server
explanation **only** for `SESSION_EXPIRED`:

```java
  protected Response buildRestoreFailureResponse( PlatformImportException e ) {
    if ( e.getErrorStatus() != PlatformImportException.SESSION_EXPIRED ) {
      return Response.status( Response.Status.INTERNAL_SERVER_ERROR ).build();
    }

    return Response.status( Response.Status.INTERNAL_SERVER_ERROR )
      .type( MediaType.TEXT_PLAIN )
      .entity( e.getLocalizedMessage() )
      .build();
  }
```

Every other import failure keeps the pre-existing empty-bodied 500, so the only response this ticket
changes is the one it exists to fix. The explicit media type is required because the command line
requests with `Accept: multipart/form-data`; it also matches the `text/plain` rendering that
`com.pentaho.problemdetails.mappers.ServiceExceptionHandler.handlePlainTextErrorResponse(...)` applies
to HTTP 500, so the endpoint is already shaped like the platform's eventual Problem Details contract.

#### `extensions/.../plugin/services/importexport/CommandLineProcessor.java`
* `logResponseMessage(...)` handles **HTTP 500** explicitly. For `--restore` it appends the server
  explanation only when the body is `text/plain` (`CommandLineProcessor.ERROR_0012_SERVER_ERROR`); a
  container-rendered error page or an absent body would make that message announce an explanation
  that is not there, so the message is replaced by `CommandLineProcessor.ERROR_0014_SERVER_ERROR_NO_DETAIL`,
  which points the operator at the server logs instead. For `--import` the datasource branch is
  evaluated first and still appends whatever body arrives, which is deliberate — that is where the
  datasource diagnosis lives (see §7.6/R3). On master, any 500 body — including the whole Tomcat HTML
  error page — was pasted into the console and the restore log through `INFO_REST_RESPONSE_RECEIVED`.
* `performRestore()` catches `jakarta.ws.rs.ProcessingException` separately
  (`CommandLineProcessor.ERROR_0013_RESTORE_CONNECTION_LOST`) so a dropped connection / proxy read
  timeout also produces an actionable message instead of a bare `null`.
* New `describeThrowable( Throwable )` helper renders the full cause chain, so exceptions without a
  message no longer print `null`.

#### Message bundles
* `extensions/src/main/resources/org/pentaho/platform/web/http/messages/messages.properties`
  * `FileService.ERROR_RESTORE_SESSION_EXPIRED`
  * `FileService.ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION`
  * `FileService.ERROR_UNABLE_TO_PROBE_SESSION`
  * `FileService.ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG`
* `extensions/src/main/resources/org/pentaho/platform/plugin/services/messages/messages.properties`
  * `CommandLineProcessor.ERROR_0012_SERVER_ERROR`
  * `CommandLineProcessor.ERROR_0013_RESTORE_CONNECTION_LOST`
  * `CommandLineProcessor.ERROR_0014_SERVER_ERROR_NO_DETAIL`
  * `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` — consumed by `ScheduleImportUtil` in
    `pentaho-scheduler-plugin`, because `org.pentaho.platform.plugin.services.messages.Messages` is
    backed by this jar. See the merge-order constraint in §7.11.

#### Tests
* New `extensions/src/test/java/org/pentaho/platform/plugin/services/importexport/SessionExpirationDetectorTest.java`
  (8 tests), including localized Tomcat messages and circular cause chains.
* `FileServiceTest`: ten restore session-expiry tests cover the final session probe, a checked
  import failure caused by expiry, unchanged propagation of unrelated checked and runtime failures,
  and the totality of the `finally` block — a failing restore log must not mask the expiry, and a
  failing session probe or `endJob()` must not mask an unrelated failure or skip the flush to disk.
  Three of them also assert the thrown exception carries `SESSION_EXPIRED` and the correct cause —
  the originating failure when there was one, `null` when only the probe detected it. A further three
  assert, one message at a time, that each restore-log failure is explained in the server log
  (§7.13).
* `FileResourceTest`: two tests for `buildRestoreFailureResponse(...)` — `SESSION_EXPIRED` yields a
  `text/plain` explanation, any other status yields an empty-bodied 500 with no media type.
* `CommandLineProcessorTest`: `describeThrowableStopsAtCircularCauseChain` for the cause-chain
  renderer, plus four tests covering the HTTP 500 branch (curated `text/plain` body appended, HTML
  error page dropped, absent body reported without promising a server explanation, and datasource
  import detail preserved on a 500).

### 4.2 `pentaho-scheduler-plugin`

`core/src/main/java/org/pentaho/platform/plugin/services/importer/ScheduleImportUtil.java` — when a
schedule could not be created and the space-retry was not attempted, the `else` branch logged only
the schedule name (`Exception occurred when creating schedule: [ UpdateAuditData ]`) and discarded
the exception. (The space-retry `catch` above it already logged the cause and passed the throwable;
this is the sibling below it.)
It now includes the cause and passes the throwable to the logger, matching the sibling branch a few
lines above. The scheduler restart is also protected by a `finally` block, so an exception while
listing or replacing schedules cannot leave the server scheduler paused. If restarting fails, that
failure is logged and either propagated or attached to the original import failure.

`core/src/test/java/.../ScheduleImportUtilTest.java` adds `testDoImport_restartsSchedulerWhenImportFails`,
`testDoImport_restartFailureDoesNotMaskImportFailure`, `testDoImport_restartErrorDoesNotMaskImportFailure`
`testDoImport_restartFailureLoggingFailureDoesNotMaskImportFailure`,
`testDoImport_successLoggingFailureDoesNotFailTheImport` and `restartFailedMessageResolves`,
and tightens the existing schedule-creation-failure test to assert the throwable reaches the logger.

> **Review note.** The behavioural change is ~30 lines, but wrapping the loop in `try/finally`
> re-indented the whole method, so the diff reports a much larger line count. Reviewers should use
> `git diff -w` on this file to see the actual change.

### 4.3 Operational remedy for users

The code change makes the failure *visible and explainable*; the actual remedy is a configuration
change on the server:

1. Increase `<session-timeout>` (minutes) in
   `pentaho-server/tomcat/webapps/pentaho/WEB-INF/web.xml` beyond the expected restore duration.
2. If a reverse proxy / load balancer sits in front of Tomcat, raise its read timeout accordingly.
3. Restart the Pentaho Server and re-run the restore.

Optionally, adding `-Dorg.apache.catalina.session.StandardSession.ACTIVITY_CHECK=true` to
`CATALINA_OPTS` makes Tomcat treat a session with an in-flight request as active, which prevents the
expiry altogether. This is a global Tomcat behaviour change and is therefore *not* applied by
default.

### 4.4 API compatibility of `POST /api/repo/files/systemRestore`

The fix is deliberately scoped so that the only response that changes is the one the bug produces.

| Scenario | Before the fix | After the fix |
| --- | --- | --- |
| Restore succeeds | `200`, empty body | `200`, empty body *(unchanged)* |
| **HTTP session expires mid-restore** | **`200` + `Restore complete`** 🐛 | `500`, `Content-Type: text/plain`, actionable explanation |
| Corrupt bundle / any other import failure | `500`, container error page | `500`, container error page *(unchanged)* |
| Invalid log file name | `400` | `400` *(unchanged)* |
| Caller is not an administrator | `403` | `403` *(unchanged)* |

An earlier iteration returned `e.getLocalizedMessage()` as a `text/plain` body for *every*
`PlatformImportException`. That flipped the 500 `Content-Type` from `text/html` to `text/plain` for
unrelated failures and echoed internal messages to the client (review finding **R7**). Gating the
body on `PlatformImportException.SESSION_EXPIRED` removes both effects.

The `text/plain` shape chosen for the session-expiry 500 is not arbitrary: it is exactly what
`com.pentaho.problemdetails.mappers.ServiceExceptionHandler` emits for HTTP 500 in the repositories
that have already adopted Pentaho's RFC 9457 tooling (`pentaho-app-shell`). Structured
`application/json` `Problem` bodies there are reserved for client-fault failures — `InputValidationProblem`
(400), `ResourceNotFoundProblem` (404), `ResourceConflictProblem` (409). Adopting
`pentaho-problem-details` in this endpoint alone would therefore produce the same bytes on the wire
while requiring `FileService` to be rewritten against the `com.pentaho.service.exceptions.ServiceException`
hierarchy. See §9 for the platform-wide follow-up.

---

## 5. AI reproduction & verification runbook

Copy/paste-able commands. Assumes macOS, `JAVA_HOME` pointing at a JDK 17 build, and the
repositories cloned under `~/github`.

```bash
# ---------------------------------------------------------------------------
# 0. Environment
# ---------------------------------------------------------------------------
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export SERVER=~/pentaho/11.1.0.0-SNAPSHOT/pentaho-server-ee-11.1.0.0-20260819.221927-351/pentaho-server
export PLATFORM=~/github/pentaho-platform
export WEBXML=$SERVER/tomcat/webapps/pentaho/WEB-INF/web.xml

# ---------------------------------------------------------------------------
# 1. Build the platform and produce the patched jar
# ---------------------------------------------------------------------------
cd $PLATFORM
mvn -q -pl extensions -am -DskipTests install     # first run needs network access
mvn -o -pl extensions test \
    -Dtest='SessionExpirationDetectorTest,FileServiceTest,FileResourceTest,CommandLineProcessorTest,CommandLineProcessorDatasourceImportTest' \
    -DfailIfNoTests=false                          # expect: Tests run: 231, Failures: 0, Errors: 0
mvn -q -o -pl extensions -DskipTests package

# ---------------------------------------------------------------------------
# 2. Shrink the session timeout to 1 minute and deploy the patched jar
# ---------------------------------------------------------------------------
cd $SERVER && ./stop-pentaho.sh; sleep 20
cp $SERVER/tomcat/webapps/pentaho/WEB-INF/lib/pentaho-platform-extensions-11.1.0.0-SNAPSHOT.jar /tmp/extensions.orig.jar
cp $PLATFORM/extensions/target/pentaho-platform-extensions-11.1.0.0-SNAPSHOT.jar \
   $SERVER/tomcat/webapps/pentaho/WEB-INF/lib/
cp $WEBXML $WEBXML.bak
sed -i '' 's|<session-timeout>120</session-timeout>|<session-timeout>1</session-timeout>|' $WEBXML
grep -n 'session-timeout' $WEBXML                  # expect: <session-timeout>1</session-timeout>

# ---------------------------------------------------------------------------
# 3. Start the server (wait ~2 minutes)
# ---------------------------------------------------------------------------
cd $SERVER && nohup ./start-pentaho.sh >/dev/null 2>&1 &
sleep 125 && curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/pentaho/Login   # expect 302

# ---------------------------------------------------------------------------
# 4. Take a backup (~1 minute)
# ---------------------------------------------------------------------------
cd $SERVER && ./import-export.sh --backup \
  --url=http://localhost:8080/pentaho --username=admin --password=password \
  --logfile=/tmp/bi15532_backup.log --file-path=/tmp/bi15532_backup.zip

# ---------------------------------------------------------------------------
# 5. Restore -> must now FAIL with an actionable message (~100 s)
# ---------------------------------------------------------------------------
cd $SERVER && rm -f /tmp/bi15532_restore_expired.log && ./import-export.sh --restore \
  --url=http://localhost:8080/pentaho --username=admin --password=password \
  --overwrite=true --file-path=/tmp/bi15532_backup.zip \
  --logLevel=Debug --logfile=/tmp/bi15532_restore_expired.log

# Assertions
grep -q 'HTTP session expired while the restore was still running' /tmp/bi15532_restore_expired.log && echo 'PASS: actionable error present'
grep -q 'Restore complete' /tmp/bi15532_restore_expired.log && echo 'FAIL: still reports success' || echo 'PASS: no false success'
grep -c 'Session already invalidated' $SERVER/tomcat/logs/catalina.out   # > 0 confirms the timeout occurred

# ---------------------------------------------------------------------------
# 6. Regression check: restore the normal timeout, restore must SUCCEED
# ---------------------------------------------------------------------------
cd $SERVER && ./stop-pentaho.sh; sleep 20
sed -i '' 's|<session-timeout>1</session-timeout>|<session-timeout>120</session-timeout>|' $WEBXML
nohup ./start-pentaho.sh >/dev/null 2>&1 &
sleep 125 && curl -s -o /dev/null -w '%{http_code}\n' http://localhost:8080/pentaho/Login

cd $SERVER && rm -f /tmp/bi15532_restore_ok.log && ./import-export.sh --restore \
  --url=http://localhost:8080/pentaho --username=admin --password=password \
  --overwrite=true --file-path=/tmp/bi15532_backup.zip \
  --logLevel=Debug --logfile=/tmp/bi15532_restore_ok.log
tail -3 /tmp/bi15532_restore_ok.log                # expect: Restore complete

# ---------------------------------------------------------------------------
# 7. Clean up (restore pristine server)
# ---------------------------------------------------------------------------
cd $SERVER && ./stop-pentaho.sh; sleep 20
cp /tmp/extensions.orig.jar $SERVER/tomcat/webapps/pentaho/WEB-INF/lib/pentaho-platform-extensions-11.1.0.0-SNAPSHOT.jar
mv $WEBXML.bak $WEBXML
```

### Recorded results (2026-08-25, 11.1.0.0-SNAPSHOT build 351)

| Scenario | `session-timeout` | Restore duration | HTTP status | Console / log outcome |
| --- | --- | --- | --- | --- |
| Baseline, before fix | 120 min | 1 m 55 s | 200 | `Restore complete` (correct) |
| Bug, before fix | 1 min | 1 m 46 s | 200 | `Restore complete` — **wrong**, 54 masked `ERROR`s, `Session already invalidated` in `catalina.out` |
| Bug, after fix | 1 min | 1 m 39 s | 500 | `ERROR_0012` + session-expired explanation, **no** `Restore complete` |
| Regression, after fix | 120 min | 1 m 38 s | 200 | `Restore complete` (correct) |

### 5.1 Re-run against the final change set (2026-09-04)

The runbook was executed again end to end after §7.6/§7.7, on the same server build, with **both**
patched jars deployed (`pentaho-platform-extensions` and `pentaho-scheduler-core`) so the scheduler
lifecycle fix was exercised too. The backup bundle was 160 MB.

| Run | `session-timeout` | Duration | Restore log tail | Schedules | `ERROR` lines | `Restore complete` |
| --- | --- | --- | --- | --- | --- | --- |
| Session expires mid-restore | 1 min | 1 m 42 s | session-expired `ERROR`, then `End of Log` | 0 / 55 | 970 | **absent** ✅ |
| Normal restore | 120 min | 1 m 42 s | `End of Log` | 55 / 55 | 0 | present ✅ |

Console output of the failing run, reproducing §3 exactly:

```
CommandLineProcessor.ERROR_0012 - The restore did not complete successfully. The server reported the following error:
The restore did not complete: the HTTP session expired while the restore was still running, so the server rejected the
remaining repository, security and scheduler operations. ...
```

Two things worth recording beyond the pass/fail:

* The actionable `ERROR` lands **inside** the generated log file, immediately before `End of Log`, and
  is then repeated on the console by `CommandLineProcessor` — so an operator who only keeps the log
  file still gets the explanation. This is the ordering §4.1 requires (`importLogger.error(...)`
  before `importLogger.endJob()`).
* `Successfully started the scheduler` appears in **both** runs, which confirms the restart path is
  unbroken by this change. It is **not** evidence for the §4.2 fix: per-schedule creation failures are
  caught inside the loop and never escape it — `Successfully restored [ 0 ] out of [ 55 ]` is logged
  *after* the loop, which proves nothing escaped — so the unpatched code would have reached
  `schedulerResource.start()` on this path too. The `try/finally` only changes behaviour when a
  throwable escapes the loop body (for example `schedulerResource.getJobsList()`), which this run did
  not exercise. That path is covered by the `ScheduleImportUtilTest` regression tests listed in §4.2.

---

## 6. Files changed

### `pentaho-platform`
| File | Change |
| --- | --- |
| `extensions/src/main/java/org/pentaho/platform/plugin/services/importexport/SessionExpirationDetector.java` | **new** — detects invalidated HTTP sessions |
| `extensions/src/main/java/org/pentaho/platform/plugin/services/importer/PlatformImportException.java` | new `SESSION_EXPIRED` status marking a message as safe to return to the client |
| `extensions/src/main/java/org/pentaho/platform/web/http/api/resources/services/FileService.java` | detect + log + fail the restore on session expiry, chaining the originating failure; every statement in the `finally` is total, so neither the session probe, the restore-log write nor `endJob()` can skip the flush to disk or mask the in-flight failure |
| `extensions/src/main/java/org/pentaho/platform/web/http/api/resources/FileResource.java` | `buildRestoreFailureResponse(...)` — return the explanation in the 500 body **only** for session expiry |
| `extensions/src/main/java/org/pentaho/platform/plugin/services/importexport/CommandLineProcessor.java` | HTTP 500 handling, `ProcessingException` handling, `describeThrowable()` |
| `extensions/src/main/resources/org/pentaho/platform/web/http/messages/messages.properties` | `FileService.ERROR_RESTORE_SESSION_EXPIRED` plus the three `FileService.ERROR_UNABLE_TO_*` messages reporting a failure of the restore-log machinery itself |
| `extensions/src/main/resources/org/pentaho/platform/plugin/services/messages/messages.properties` | `CommandLineProcessor.ERROR_0012_SERVER_ERROR`, `CommandLineProcessor.ERROR_0013_RESTORE_CONNECTION_LOST`, `CommandLineProcessor.ERROR_0014_SERVER_ERROR_NO_DETAIL`, `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` (consumed by `pentaho-scheduler-plugin`) |
| `…/web/http/messages/messages_{cn,de,en,es,fr,it,ja,nl,pt,zh_CN}.properties` | translations of the four `FileService.*` keys (§7.14) |
| `…/plugin/services/messages/messages_{cn,de,es,fr,it,ja,nl,pt,zh_CN}.properties` | translations of the three `CommandLineProcessor.*` keys and `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` (§7.14) |
| `extensions/src/test/java/org/pentaho/platform/plugin/services/importexport/SessionExpirationDetectorTest.java` | **new** — 8 unit tests |
| `extensions/src/test/java/org/pentaho/platform/web/http/api/resources/services/FileServiceTest.java` | 10 new tests for expiry detection, `SESSION_EXPIRED` tagging, cause chaining, unrelated exception propagation, the totality of the `finally` — a failing restore log, session probe or `endJob()` must neither mask the failure nor skip the flush — and one test per restore-log failure message asserting it reaches the server log |
| `extensions/src/test/java/org/pentaho/platform/web/http/api/resources/FileResourceTest.java` | 2 new tests for `buildRestoreFailureResponse(...)` |
| `extensions/src/test/java/org/pentaho/platform/plugin/services/importexport/CommandLineProcessorTest.java` | 1 test for `describeThrowable()`, 4 for the HTTP 500 branch |

### `pentaho-scheduler-plugin`
| File | Change |
| --- | --- |
| `core/src/main/java/org/pentaho/platform/plugin/services/importer/ScheduleImportUtil.java` | include schedule creation causes and always attempt to restart the scheduler without masking the in-flight failure, whether it is an exception, an `Error`, or a failure of the import logger itself |
| `core/src/test/java/org/pentaho/platform/plugin/services/importer/ScheduleImportUtilTest.java` | verify logging preserves the throwable, interrupted imports restart the scheduler, a restart failure — exception, `Error`, or a throwing logger — is suppressed rather than masking, a logger failure *after* a successful restart neither fails the import nor is reported as a restart failure, and the cross-repository restart-failure message actually resolves |

---

## 7. Code review (2026-08-28 → 2026-09-04)

Seven passes over the uncommitted working-tree changes in `pentaho-platform` (11 files) and
`pentaho-scheduler-plugin` (2 files): the initial pass (§7.3–§7.4), the R7 pass (§7.5), the
regression pass (§7.6–§7.8), the independent pass (§7.9), a fourth pass over those fixes plus this
document (§7.10), a fifth over those (§7.11), a sixth (§7.12) and a localization sweep (§7.13).
Baseline verification, before any review fix was applied:

```
pentaho-platform     mvn -o -pl extensions test -Dtest='SessionExpirationDetectorTest,FileServiceTest,
                     CommandLineProcessorTest,CommandLineProcessorDatasourceImportTest'
                     -> Tests run: 124, Failures: 0, Errors: 0   BUILD SUCCESS
pentaho-scheduler-plugin  mvn -o -pl core test -Dtest='ScheduleImportUtilTest'
                     -> Tests run: 22, Failures: 0, Errors: 0    BUILD SUCCESS
```

**Verdict: the change is sound.** The design is good — the detector is injectable rather than
static, cause-chain walking is loop-safe, unrelated failures are propagated untouched, and the
scheduler restart is now guaranteed. All §7.1 should-fix items and nits 2–3 have since been
resolved (§7.4, §7.5, §7.6, §7.9, §7.10, §7.11, §7.12), and R4/R6 are now documented in code (§7.7).
R5, R10, R11 and R12 remain open; §7.8 analyses R5 and §7.2 records the other three.

### 7.1 Should fix — ✅ all resolved

| # | File | Finding | Status |
| --- | --- | --- | --- |
| R1 | `ScheduleImportUtil.doImport()` | The guard was `catch ( RuntimeException e ) { importFailure = e; throw e; }`, but `doImport` declares `throws ImportException`. A checked `ImportException` (or an `Error`) raised inside the guarded block left `importFailure == null`, so a failing `schedulerResource.start()` in the `finally` executed `throw e` and **silently discarded the in-flight exception** — exactly the class of masking this ticket exists to remove. | **Fixed** — see §7.4 |
| R2 | `CommandLineProcessor.logResponseMessage()` | `requestType.toString().toLowerCase()` used the default locale. Under a Turkish locale `IMPORT` renders as `ımport`. | **Fixed** — see §7.4 |
| R7 | `FileResource.systemRestore()` | `e.getLocalizedMessage()` was echoed verbatim as the 500 `text/plain` body for **every** `PlatformImportException`, not just session expiry. Messages may contain repository paths, and the 500 `Content-Type` flipped from `text/html` to `text/plain` for unrelated failures. | **Fixed** — see §7.5 |
| R3 | `CommandLineProcessor.logResponseMessage()` | Originally logged as a minor observation, this turned into a **regression** once §7.5 added the `ERROR_0014` fallback: on `--import`, the datasource branch runs first and never set the "detail appended" flag, so the fallback wiped the datasource diagnosis with `message.setLength( 0 )`. | **Fixed** — see §7.6 |
| R8 | `ScheduleImportUtil.doImport()` | The restart guard inside the new `finally` was `catch ( RuntimeException e )` while the import guard above it is `catch ( Throwable t )`. An `Error` from `schedulerResource.start()` therefore escaped the `finally` and masked the in-flight import failure — the exact defect R1 fixed, one block lower. | **Fixed** — see §7.6 |
| R4 | `FileService.systemRestore()` | The `finally` block probes the session unconditionally, so when an unrelated failure is propagating *and* the session also expired, the restore log and the console disagree. Behaviour is correct; the intent was undocumented. | **Documented** — see §7.7 |
| R6 | `SessionExpirationDetector` | Detection is best-effort and one-sided, and silently degrades when handed a detached session or a cause chain that swallowed the container error. The contract was undocumented. | **Documented** — see §7.7 |
| R9 | `FileService.systemRestore()` | When expiration was detected from the caught failure, that failure was silently discarded: not logged, and not chained into the `PlatformImportException` thrown afterwards. The only exception reaching the server log had a `null` cause and no stack, so the operator lost the evidence of *which* layer rejected the invalidated session — in the one scenario where that evidence matters most. | **Fixed** — see §7.9 |
| R13 | `ScheduleImportUtil.doImport()` | In the `finally`'s restart guard, `getLogger().error(...)` ran **before** `importFailure.addSuppressed( restartFailure )`. The import logger writes to the restore log, which can itself be unusable in exactly these sequences; a throwing logger therefore escaped the `finally` and replaced the propagating import failure — the same masking R1 and R8 fixed, one statement earlier. | **Fixed** — see §7.9 |
| R14 | `ScheduleImportUtil.doImport()` | The restart guard also covered the success `debug(...)` call. A throwing import logger after a **successful** restart was therefore reported as `ERROR_0002_SCHEDULER_RESTART_FAILED`, or rethrown and turned an otherwise successful restore into a failed one — telling the operator the scheduler was down while it was running. | **Fixed** — see §7.10 |
| R15 | `ScheduleImportUtil.logRestartFailure()` | The restart-failure text was a hardcoded English literal, breaking the file's convention that every `error`/`info` message resolves through `Messages.getInstance().getString(...)`. | **Fixed** — see §7.10 |
| R16 | `ScheduleImportUtil.logRestartFailure()` / `ScheduleImportUtilTest` | The key R15 introduced is defined in **pentaho-platform**, so against any `pentaho-platform-extensions` jar predating this change `MessageUtil` degraded it to `!SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED!` — the literal key written into the restore log, in the one line whose purpose is to be actionable. Nothing failed: the test constant resolved from the same bundle, so the `never().error( eq( ... ) )` verification compared `"!key!"` against `"!key!"` and passed either way. | **Fixed** — see §7.11 |
| R17 | `FileService.systemRestore()` | In the `finally`, `importLogger.error(...)` ran before `endJob()` and before the log was flushed to disk. A failing import logger — plausible precisely when the restore log is the thing that broke — skipped both and replaced the in-flight failure with the logging failure. The same masking class as R13/R14, in the block this fix introduced. | **Fixed** — see §7.11 |
| R18 | `FileService.systemRestore()` | R17 made only the *middle* statement of the `finally` total. The session probe and `endJob()` were still unguarded, so either could skip the flush — leaking the log descriptor and losing the log — and replace the in-flight failure. `Log4JRepositoryImportLogger.error(String)` and `endJob()` share a failure mode, so the very scenario R17 guards against is the one where `endJob()` also fails. | **Fixed** — see §7.12 |

### 7.2 Worth considering — still open

| # | File | Finding |
| --- | --- | --- |
| R5 | `FileService.systemRestore()` | `fileOutputStream` is opened before `PentahoSystem.get( IPlatformImporter.class )`. If that returns `null` (NPE on `getRepositoryImportLogger()`) or `startJob(...)` throws, the stream is never closed — the new try-with-resources only covers the path after `startJob`. Narrow, largely pre-existing, but the fix touches exactly this region. **Analysis and proposed fix in §7.8.** |
| R10 | `SessionExpirationDetector.originatesFromTomcatSessionAttributeAccess(...)` | The locale-independent stack-frame fallback only matches `StandardSession.getAttribute`. `StandardSession` throws the same `IllegalStateException` from `setAttribute`, `removeAttribute`, `getAttributeNames`, `getId` and `invalidate`. On a Tomcat running a non-English locale — where the message check cannot help — those cases are missed even though the class-name evidence is identical. During a restore `setAttribute` is at least as likely as `getAttribute`, because Spring Security writes the security context back to the session. **Proposed fix:** drop the `"getAttribute".equals( getMethodName() )` condition and treat any `IllegalStateException` originating inside `org.apache.catalina.session.StandardSession` as an invalidated-session error. Deferred because it widens detection and therefore wants its own regression coverage; the current heuristic matches the stack in §2, which is the reported failure. |
| R11 | `CommandLineProcessor.logResponseMessage()` | A 500 whose body is neither `text/plain` nor a datasource import response is dropped from the **log file** as well as the console. That is correct for a container-rendered HTML error page, but a future `application/json` RFC 9457 `Problem` body (§9.2) would be a real, actionable payload being discarded. **Proposed fix:** keep the console output as-is, but when `serverError && !detailAppended && StringUtils.isNotBlank( responseBody )`, still write the raw body to `logFile` or to `log.debug`. Deferred because no platform endpoint emits such a body today, so the change cannot be tested end to end until §9.2 lands. |
| R12 | `FileServiceTest` | The R4 combination documented in §7.7 — an unrelated failure propagating to the caller **while** the restore log records the session expiration — has no test. `shouldPropagateUnrelatedImportException` stubs `isSessionExpired` to `false`, so it asserts the opposite half of the matrix. A refactor that moved the probe out of the `finally` would silently break the contract stated in the `systemRestore(...)` javadoc without a red test. **Proposed fix:** add a test stubbing `isCausedBySessionExpiration → false` and `isSessionExpired → true`, asserting both `assertSame( importException, expected )` and `verify( importLogger ).error( EXPECTED_SESSION_EXPIRED_MESSAGE )`. |

### 7.3 Nits

* ⬜ Two added lines in `FileService.java` (the `SESSION_EXPIRED` throw and the `recordSessionExpirationFailure`
  signature) are 123 columns; the file's newer code sticks to 120. Left as-is: the file already
  contains many longer lines, so this is not a regression, and the `maven-checkstyle-plugin` is
  configured as a report (`pom.xml:340`), not as a build-failing check.
* ✅ `FileServiceTest`: `assertTrue( expected == importException )` replaced with `assertSame(...)`,
  and `verify( importLogger, times( 0 ) )` replaced with `verify( importLogger, never() )`.
* ✅ `FileServiceTest` no longer asserts on the substring `"session-timeout"`; it resolves the bundle
  key once into `EXPECTED_SESSION_EXPIRED_MESSAGE` and asserts equality, so rewording the message
  cannot break the tests for a non-functional reason.

### 7.4 Fixes applied (2026-08-28)

#### R1 — `ScheduleImportUtil.doImport()` no longer masks non-`RuntimeException` failures

```java
      Throwable importFailure = null;          // was: RuntimeException importFailure
      try {
        ...
      } catch ( Throwable t ) {                // was: catch ( RuntimeException e )
        // Recorded so that the finally block can attach a restart failure instead of masking this one. Throwable is
        // required because a checked ImportException or an Error must not be discarded either.
        importFailure = t;
        throw t;                               // precise rethrow: compiles against `throws ImportException`
      } finally {
        ...
      }
```

Java's precise-rethrow analysis narrows `throw t` to the types the guarded block can actually raise,
so the `throws ImportException` signature is unchanged and the module still compiles.

New regression test `ScheduleImportUtilTest.testDoImport_restartFailureDoesNotMaskImportFailure`
makes `getJobsList()` throw an `Error` **and** `start()` throw a `RuntimeException`, then asserts the
original `Error` propagates with the restart failure attached via `getSuppressed()`. Under the old
`catch ( RuntimeException )` this test fails, because the restart failure replaced the `Error`.

#### R2 — locale-independent request-type rendering

```java
      errorMessage =
        Messages.getInstance().getErrorString( "CommandLineProcessor.ERROR_0012_SERVER_ERROR",
          requestType.toString().toLowerCase( Locale.ROOT ) );   // was: toLowerCase()
```

`java.util.Locale` added to the imports. This matches the `Locale.ROOT` already used by
`SessionExpirationDetector.isCausedBySessionExpiration()`.

#### Verification after the fixes

```
pentaho-platform          -> Tests run: 124, Failures: 0, Errors: 0   BUILD SUCCESS
pentaho-scheduler-plugin  -> Tests run: 23,  Failures: 0, Errors: 0   BUILD SUCCESS  (22 + the R1 test)
```

### 7.5 R7 fixed (2026-09-04) — the 500 body is limited to the session-expiry explanation

The staged version returned the exception message for every `PlatformImportException`, which widened
what the client sees and flipped the 500 `Content-Type` for unrelated failures. Rather than have the
REST layer re-run session detection, the decision now travels with the exception:

```java
  /**
   * The operation could not complete because the HTTP session backing it expired while it was still running.
   * ...
   * Exceptions carrying this status hold a curated, user-facing explanation as their message, so that message is safe
   * to return to the client. Every other status may carry internal detail and must not be echoed verbatim.
   */
  public static final int SESSION_EXPIRED = 14;
```

`FileService.systemRestore()` tags its throw with it, and `FileResource.buildRestoreFailureResponse(...)`
reads it (see §4.1). The response matrix is in §4.4 — only the session-expiry row changes relative to
master.

Two knock-on improvements fell out of this:

* `CommandLineProcessor` now appends a 500 body only when it is `text/plain`. Container-rendered HTML
  error pages are dropped instead of being pasted into `restore.log`, which is what master did.
* When there is no usable server explanation, `ERROR_0012_SERVER_ERROR` ("…reported the following
  error:") would announce detail that is not there, so `ERROR_0014_SERVER_ERROR_NO_DETAIL` is used
  instead and points the operator at `catalina.out` / `pentaho.log`.

#### Verification after R7

```
pentaho-platform  mvn -o -pl extensions test -Dtest='SessionExpirationDetectorTest,FileServiceTest,
                  FileResourceTest,CommandLineProcessorTest,CommandLineProcessorDatasourceImportTest'
                  -> Tests run: 225, Failures: 0, Errors: 0   BUILD SUCCESS

  FileResourceTest ............................ 98
  FileServiceTest ............................. 82
  CommandLineProcessorDatasourceImportTest .... 25
  CommandLineProcessorTest .................... 12
  SessionExpirationDetectorTest ................ 8
```

### 7.6 Final review pass (2026-09-04)

A full re-read of the working tree in `pentaho-platform/extensions/` and `pentaho-scheduler-plugin/core/`
found one regression introduced by §7.5 and two cleanups.

#### R3 — the `ERROR_0014` fallback destroyed datasource import diagnostics

§7.5 added `if ( serverError && !serverDetailAppended ) { message.setLength( 0 ); … }`. The flag was
only set by the `serverError` branch, but for `RequestType.IMPORT` the **datasource branch is
evaluated first**, so it appended its diagnosis and left the flag `false`. The fallback then wiped it.

Reproduced with a throwaway test before the fix — body `"6"` (`PUBLISH_DATASOURCE_ERROR`) on
`--import`:

```
CommandLineProcessor.ERROR_0014 - The import did not complete successfully. The server did not report a
reason; check the Pentaho Server logs (tomcat/logs/catalina.out and the pentaho.log file) ...
```

The `Import status: Connection error - Unable to connect to the data source…` text was gone. This is
**not hypothetical**: `data-access` returns HTTP 500 on these paths — `ResourceUtil.java:153`
(`Response.status( INTERNAL_SERVER_ERROR ).entity( error ).type( "text/plain" )`),
`MetadataResource.buildServerError001Response()` and `buildServerErrorResponse( PentahoAccessControlException )`.
Nothing caught it because `CommandLineProcessorDatasourceImportTest` only exercises 200/403/404/406.

The flag now means "any usable detail was appended", and is set by all three append paths:

```java
    boolean detailAppended = false;             // was: serverDetailAppended
        if ( requestType == RequestType.IMPORT && StringUtils.isNotBlank( responseBody ) ) {
          ...
          detailAppended = true;
        } else if ( serverError ) {
          if ( isPlainText( response ) && StringUtils.isNotBlank( responseBody ) ) {
            message.append( StringUtils.trimToEmpty( responseBody ) );
            detailAppended = true;
          }
        } else {
          ...
          detailAppended = true;
        }
```

`ERROR_0014` is therefore reached only when nothing usable was appended. New regression test
`CommandLineProcessorTest.logResponseMessageKeepsDatasourceDetailOnImportServerError` asserts the
resolved `DATASOURCE_IMPORT_CONNECTION_ERROR` bundle value survives a 500 on `--import`; it fails
against the §7.5 code and passes now. This also closes the original R3 observation: the `serverError`
branch is still unreachable for `IMPORT`, but that is now the *intended* precedence rather than an
accident, and it no longer loses information.

#### R8 — the scheduler restart guard now catches `Throwable`

```java
        } catch ( Throwable restartFailure ) {          // was: catch ( RuntimeException e )
          // Mirrors the guard above: an Error raised while restarting must not replace the in-flight import failure.
          solutionImportHandler.getLogger()
            .error( "Unable to restart the scheduler after importing schedules", restartFailure );
          if ( importFailure != null ) {
            importFailure.addSuppressed( restartFailure );
          } else {
            throw restartFailure;
          }
        }
```

New test `ScheduleImportUtilTest.testDoImport_restartErrorDoesNotMaskImportFailure` is the mirror of
the R1 test: `getJobsList()` throws a `RuntimeException` and `start()` throws an `Error`; the original
`RuntimeException` must propagate with the `Error` suppressed. It errors against the previous code.

#### Cleanups

* **`SessionExpirationDetector`** — two consecutive `if ( current instanceof IllegalStateException && … )`
  guards merged into one, with the message check extracted to `hasInvalidatedSessionMessage(...)`.
  The **R6** contract is now stated in the `isSessionExpired(...)` javadoc: callers must pass the
  session bound to the request thread, because a detached `StandaloneSession` never throws and always
  reports `false`.
* **`ScheduleImportUtil`** — javadoc that had been added to seven methods unrelated to the fix
  (`registerAsHelper`, `createSchedulerJob`, `getScheduleList`, `convertFromPreTimeZoneTrigger`,
  `triggerIsPreTimeZoneSupport`, `getName`, the constructor) was removed to keep the diff reviewable.
  The class-level and `doImport(...)` javadoc is retained, because it documents the new
  pause/restart lifecycle contract that *is* the fix.

#### Verification after §7.6

```
pentaho-platform  mvn -o -pl extensions test -Dtest='SessionExpirationDetectorTest,FileServiceTest,
                  FileResourceTest,CommandLineProcessorTest,CommandLineProcessorDatasourceImportTest'
                  -> Tests run: 226, Failures: 0, Errors: 0   BUILD SUCCESS

pentaho-scheduler-plugin  mvn -o -pl core test -Dtest=ScheduleImportUtilTest
                  -> Tests run: 24,  Failures: 0, Errors: 0   BUILD SUCCESS
```

Both new tests were confirmed to fail when their fix is reverted.

#### Also checked, no action needed

* No unused imports in any changed file (`Collections`, `IdentityHashMap`, `Locale`, `Set`,
  `ProcessingException`, `MediaType` are all referenced).
* `buildRestoreFailureResponse(...)` is correctly `protected`, matching the existing
  `buildOkResponse` / `buildServerErrorResponse` / `buildSafeHtmlServerErrorResponse` convention in
  `FileResource`.
* `FileService` exposes only the detector *setter* needed for injection — no redundant getter was
  added, because the REST layer discriminates on `PlatformImportException.SESSION_EXPIRED` instead.
* No dead code or leftover scaffolding; the throwaway reproduction test was deleted after use.

### 7.7 R4 and R6 documented (2026-09-04)

Neither is a defect — both are correct behaviours whose *intent* was invisible to the next reader,
which is exactly how a future "cleanup" reintroduces the bug this ticket fixes. Both are now stated
where someone changing the code will see them.

#### R4 — why the session is probed even while another failure is propagating

`FileService.systemRestore()`, in the `finally`:

```java
        // The probe runs unconditionally, including while an unrelated failure is propagating. That is deliberate: if
        // the session really did expire, every import step after that point was rejected for that reason, so the
        // restore log must say so even though the caller will be told about the unrelated failure instead. The log and
        // the console can therefore disagree, and the log is the one that reflects why the content is incomplete.
        sessionExpired = sessionExpired || sessionExpirationDetector.isSessionExpired( PentahoSessionHolder.getSession() );
```

The division of responsibility is: **the console reports what interrupted the operation, the restore
log explains why the restored content is incomplete.** When both happen at once those are genuinely
different answers, and suppressing either one would lose information. The same point is now in the
method javadoc so it is visible from the API surface, not only at the call site.

#### R6 — the detector's contract is best-effort and one-sided

`SessionExpirationDetector`'s class javadoc now states the asymmetry explicitly:

> **Detection is best-effort and deliberately one-sided.** A `true` result is reliable: something
> really did touch an invalidated session. A `false` result only means no evidence was found, and
> must never be read as proof that the session is alive.

and enumerates both ways detection degrades to a false negative:

* `isSessionExpired(...)` needs the container-backed session for the request thread; a detached
  `StandaloneSession` answers attribute reads normally and always reports `false`.
* `isCausedBySessionExpiration(...)` needs the container error to still be reachable in the cause
  chain, which `ExceptionLoggingDecorator` (rewrapping into a generic `UnifiedRepositoryException`)
  and `SolutionImportHandler` (continuing past per-item failures) can prevent — the very masking
  described in §2.

This is why `systemRestore(...)` deliberately uses **both** signals rather than either alone, and the
`@return` javadoc on `isCausedBySessionExpiration(...)` now spells out that `false` is not proof.
`FileService.systemRestore(...)` repeats the caveat, since a caller invoking it outside a servlet
request would silently get the pre-fix false success back.

### 7.8 R5 — analysis and proposed fix (still open)

#### What the defect actually is

`systemRestore(...)` opens the restore-log file very early, then does substantial work before the
`try` that guarantees closing it:

```java
      FileOutputStream fileOutputStream = null;
      try {
        validateFilePath( logFile );
        fileOutputStream = new FileOutputStream( logFile );      // (1) opened here
      } catch ( FileNotFoundException e ) {
        try {
          fileOutputStream = retrieveFallbackLogFileLocation( "restore" );
        } catch ( FileNotFoundException fileNotFoundException ) {
          throw new PlatformImportException( fileNotFoundException.getLocalizedMessage() );
        }
      }
      ...
      IPlatformImporter importer = PentahoSystem.get( IPlatformImporter.class );
      importLogger = importer.getRepositoryImportLogger();       // (2) NPE if the bean is missing
      RepositoryTextLayout stringLayout = new RepositoryTextLayout( level );
      importLogger.setPerformingRestore( true );
      importLogger.startJob( ... );                              // (3) may throw
      boolean sessionExpired = false;
      try {
        importer.importFile( bundleBuilder.build() );
      } catch ( ... ) {
      } finally {
        ...
        try ( FileOutputStream outputStream = fileOutputStream ) { // (4) only from here is closing guaranteed
```

Anything thrown between **(1)** and **(4)** leaks the descriptor:

* `PentahoSystem.get( IPlatformImporter.class )` returning `null` → NPE at (2). This is a real
  configuration failure mode, not a theoretical one: the importer is a Spring bean from
  `pentahoObjects.spring.xml`, so a broken or partially started plugin context produces exactly this.
* `importLogger.startJob(...)` throwing at (3).
* `RepositoryTextLayout`/`Level` construction failing.

Severity is genuinely low — the endpoint is admin-only, a restore is rare, and the JVM releases the
descriptor on GC — but it is a resource leak inside the exact region this ticket rewrites, so it will
be attributed to this change at review time.

#### Why it was not fixed as part of §7.5/§7.6

The existing `try ( FileOutputStream outputStream = fileOutputStream )` is not a normal
try-with-resources: it *adopts* an already-open stream purely to guarantee closing. Widening it means
restructuring the method's whole body, which would bury the actual behavioural change in a large
re-indentation diff — the same complaint §4.2 already makes about `ScheduleImportUtil`.

#### Proposed fix

Move the stream acquisition into a real try-with-resources that spans everything that can fail, by
extracting the opening into a small helper and wrapping the body:

```java
  /**
   * Opens the restore log destination, falling back to the default location when the requested path is unusable.
   *
   * @param logFile the requested restore-log path
   * @return an open stream for the restore log
   * @throws PlatformImportException if neither the requested path nor the fallback can be opened
   */
  private FileOutputStream openRestoreLog( String logFile ) throws PlatformImportException {
    try {
      validateFilePath( logFile );
      return new FileOutputStream( logFile );
    } catch ( FileNotFoundException e ) {
      try {
        return retrieveFallbackLogFileLocation( "restore" );
      } catch ( FileNotFoundException fallbackFailure ) {
        throw new PlatformImportException( fallbackFailure.getLocalizedMessage() );
      }
    }
  }
```

then, in `systemRestore(...)`:

```java
      try ( FileOutputStream fileOutputStream = openRestoreLog( logFile ) ) {
        ...                                    // bundle building, importer lookup, startJob, import, finally block
        // the inner finally keeps writing the buffered log, but no longer owns closing the stream:
        } finally {
          ...
          importLogger.endJob();
          try {
            importLoggerStream.writeTo( fileOutputStream );
          } catch ( IOException e ) {
            logger.error( e.getLocalizedMessage(), e );
          }
        }
      } catch ( IOException e ) {
        logger.error( e.getLocalizedMessage(), e );   // close() failure must not mask an import failure
      }
```

Points worth noting for whoever implements it:

1. `validateFilePath(...)` throws `IllegalArgumentException`, which is part of the declared contract
   and must keep propagating — the helper must not swallow it.
2. The added `catch ( IOException )` is for the implicit `close()`. It must only log: a failure to
   close the log file must never replace a propagating `PlatformImportException`.
3. Ownership becomes unambiguous — the `try` header opens and closes, the inner `finally` only
   writes. Today the inner `finally` silently takes over closing, which is what made the gap easy to
   miss.
4. Review with `git diff -w`; the change is ~15 lines of logic plus a re-indent of the method body.
5. Coverage: `FileServiceTest` should gain one test where `PentahoSystem.get( IPlatformImporter.class )`
   returns `null` and assert the restore log file is still closed and written. The existing tests
   already mock `PentahoSystem` statically, so the scaffolding exists.

**Recommendation:** fix it, but in a follow-up commit on this ticket rather than folded into the
session-expiry change, so the resource-management diff can be reviewed on its own.

### 7.9 Independent review pass (2026-09-04)

A second, independent read of the staged change set in both repositories, run alongside the live
end-to-end verification in §5.1. Two findings that lose failure information were fixed; three
narrower ones were recorded in §7.2 rather than fixed.

#### R9 — the failure that revealed the expiration is no longer discarded

`FileService.systemRestore()` swallowed the caught `PlatformImportException` / `RuntimeException`
once `isCausedBySessionExpiration(...)` confirmed the expiry, then threw a brand-new exception with
no cause. The original stack — the only record of which layer rejected the invalidated session — was
lost, and nothing logged it. `PlatformImportException` has offered a
`( String, int, Throwable )` constructor all along, so the cause was being dropped by omission:

```java
      } catch ( PlatformImportException e ) {
        sessionExpired = sessionExpirationDetector.isCausedBySessionExpiration( e );
        if ( !sessionExpired ) {
          throw e;
        }
        expirationFailure = recordSessionExpirationFailure( e );      // NEW: log it and keep it
      }
      ...
      if ( sessionExpired ) {
        throw new PlatformImportException( Messages.getInstance().getString( "FileService.ERROR_RESTORE_SESSION_EXPIRED" ),
          PlatformImportException.SESSION_EXPIRED, expirationFailure );   // NEW: chained
      }
```

`expirationFailure` is `null` when only the final session probe detected the expiry, which is the
correct cause for that path — there is no originating failure to attribute it to. The client-facing
response is unchanged: `FileResource.buildRestoreFailureResponse(...)` echoes only
`getLocalizedMessage()`, so the chained cause reaches the **server** log and never the wire.

`shouldReportSessionExpirationFromImportExceptionCause` now asserts
`assertSame( importException, expected.getCause() )`, and
`shouldReportSessionExpirationWhenHttpSessionExpiresDuringRestore` asserts
`assertNull( expected.getCause() )` so both halves are pinned.

#### R13 — the restart guard can no longer be masked by its own logger

The guard added in §7.6 logged before deciding what to do with the failure:

```java
        } catch ( Throwable restartFailure ) {
          if ( importFailure != null ) {
            importFailure.addSuppressed( restartFailure );    // decision first ...
          }
          logRestartFailure( solutionImportHandler, restartFailure );   // ... reporting second
          if ( importFailure == null ) {
            throw restartFailure;
          }
        }
```

and `logRestartFailure(...)` is itself total — a logging failure is attached to `restartFailure`
rather than allowed to escape:

```java
  private void logRestartFailure( IImportHelper.ImportContext solutionImportHandler, Throwable restartFailure ) {
    try {
      solutionImportHandler.getLogger().error(
        Messages.getInstance().getString( "SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED" ),
        restartFailure );
    } catch ( Throwable loggingFailure ) {
      if ( loggingFailure != restartFailure ) {
        restartFailure.addSuppressed( loggingFailure );
      }
    }
  }
```

(The message key shown here was introduced later, by R15 in §7.10.)

This matters because the logger in question writes to the restore log, which is exactly what is
likely to be broken when a restore is failing. New test
`testDoImport_restartFailureLoggingFailureDoesNotMaskImportFailure` makes `getJobsList()`,
`start()` **and** the logger all throw, and asserts the original import failure still propagates with
the restart failure suppressed under it and the logging failure suppressed under that.

#### Confirmed by reverting

| Reverted | Result |
| --- | --- |
| `expirationFailure` dropped from the `PlatformImportException` constructor | `shouldReportSessionExpirationFromImportExceptionCause` **FAILURE** — `expected same:<PlatformImportException: General Error> was not:<null>` |
| logging moved back before `addSuppressed` | `testDoImport_restartFailureLoggingFailureDoesNotMaskImportFailure` **FAILURE** — `expected same:<RuntimeException: session expired> was not:<RuntimeException: restore log is closed>` |

#### Deferred, recorded in §7.2

**R10** (detector matches only `StandardSession.getAttribute`, missing `setAttribute` and friends on
a localized Tomcat), **R11** (a non-`text/plain` 500 body is dropped from the log file as well as the
console, which will matter once §9.2 introduces `application/json` problem bodies) and **R12** (the
R4 combination documented in §7.7 has no test).

#### Also checked, no action needed

* `logResponseMessage(...)` is only ever reached with `RequestType.IMPORT` and `RequestType.RESTORE`;
  for `IMPORT` with a non-blank body the datasource branch always sets `detailAppended`, so
  `message.setLength( 0 )` is unreachable for the datasource path. R3 stays closed.
* `IPlatformImporter.importFile` declares only `PlatformImportException`, so no checked exception
  escapes the two catches, `Error` correctly bypasses both, and the restore log is written on every
  path that reaches `startJob`.
* `ScheduleImportUtil`'s precise rethrow is legal: `t` is effectively final and
  `ISchedulerResource.start()` declares no checked exceptions. Both repositories compile clean.
* No unused imports and no dead code in any changed file.

---

### 7.10 Fourth review pass (2026-09-04)

A review of the §7.9 fixes themselves, plus a documentation pass over this file. Two code findings,
both in the `ScheduleImportUtil` restart guard that R13 had just rewritten.

#### R14 — the restart guard covered more than the restart

The `try` introduced by R1/R8/R13 exists to make one thing total: `schedulerResource.start()`. The
success `debug` call had been left inside it:

```java
        try {
          schedulerResource.start();
          if ( solutionImportHandler.isPerformingRestore() ) {
            solutionImportHandler.getLogger().debug( "Successfully started the scheduler" );
          }
        } catch ( Throwable restartFailure ) {
          ...
        }
```

That inverts the guard's purpose. When the restart *succeeded* but the import logger then threw —
the same broken-restore-log scenario R13 exists for — the failure was caught by the restart handler
and reported as `ERROR_0002_SCHEDULER_RESTART_FAILED`, or, on an otherwise successful import,
rethrown, turning a completed restore into a failed one. The scheduler was running; the operator was
told it was not.

The guard now covers only `start()`. A `boolean schedulerRestarted` records the outcome and the
logging happens after the guard, through a total `logSchedulerRestarted(...)` helper that mirrors
`logRestartFailure(...)`. Locked in by `testDoImport_successLoggingFailureDoesNotFailTheImport`,
which stubs a throwing logger and asserts the import still returns and that
`ERROR_0002_SCHEDULER_RESTART_FAILED` is never logged. Verified to fail when the fix is reverted.

#### R15 — the restart failure message was a hardcoded English literal

Every `error(...)` and `info(...)` call in `ScheduleImportUtil` resolves its text through
`Messages.getInstance().getString( ... )`; only `debug(...)` calls use English literals. R13's new
`logRestartFailure(...)` broke that convention. The message now resolves
`SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED`, added to the platform bundle at
`extensions/src/main/resources/org/pentaho/platform/plugin/services/messages/messages.properties`
— the bundle that backs `SolutionImportHandler.*` keys, even though the caller lives in
`pentaho-scheduler-plugin`.

#### Documentation corrections

* §5.1 claimed `Successfully started the scheduler` in the failing run was end-to-end evidence for
  the §4.2 fix. It is not: per-schedule failures are caught inside the loop, so the unpatched code
  reached `start()` on that path too. Corrected.
* §4.1 stated the `text/plain`-only rule for HTTP 500 without qualification; it applies to
  `RequestType.RESTORE`, while `IMPORT` still appends the datasource body first (R3). Qualified.
* §7's heading, §7.3's stale line numbers and §5's runbook log filename were corrected to match the
  final change set.



---

### 7.11 Fifth review pass (2026-09-04)

A review of the §7.10 fixes and of this document. Two code findings, one of which was silently
defeating the test written in the previous pass.

#### R16 — the message key R15 introduced did not resolve, and the test could not tell

`ScheduleImportUtil` resolves `SolutionImportHandler.*` through
`org.pentaho.platform.plugin.services.messages.Messages`, which is backed by the
**pentaho-platform-extensions** jar — not by anything in `pentaho-scheduler-plugin`. R15 added the
key to the platform *source*, so until that jar is rebuilt and published the lookup misses, and
`MessageUtil.getString(...)` returns `'!' + key + '!'` rather than throwing:

```java
  public static String getString( final ResourceBundle bundle, final String key ) {
    try {
      return bundle.getString( key );
    } catch ( Exception e ) {
      return '!' + key + '!';
    }
  }
```

Confirmed against the working tree at the time: the key was absent from the installed jar, so
`logRestartFailure(...)` wrote the raw key into the restore log — and the suite was green, because
`EXPECTED_RESTART_FAILED_MESSAGE` is resolved from the same bundle as the production code, making
`verify( mockLogger, never() ).error( eq( EXPECTED_RESTART_FAILED_MESSAGE ), ... )` a comparison of
`"!key!"` with `"!key!"`.

`restartFailedMessageResolves()` now pins it:

```java
    Assert.assertNotEquals( "!" + RESTART_FAILED_KEY + "!", EXPECTED_RESTART_FAILED_MESSAGE );
```

This test was **observed failing** against the unpublished jar (`Values should be different. Actual:
!SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED!`) and passing after
`mvn -pl extensions install`, so it is a genuine guard rather than a tautology.

> **Merge-order constraint.** The `pentaho-platform` change must be merged **and published** before
> or together with the `pentaho-scheduler-plugin` change. The two jars are dropped independently into
> `WEB-INF/lib`, so a server with an older `pentaho-platform-extensions` will log the raw key. This
> is a degraded message only — no functional impact — but it defeats the purpose of the line.

#### R17 — the restore-log error could skip `endJob()` and mask the failure

The `finally` in `FileService.systemRestore()` called `importLogger.error(...)` directly, before
`endJob()` and before `importLoggerStream.writeTo( outputStream )`. If that call threw, the job was
never ended, the log was never flushed to disk, and the logging failure propagated in place of the
session-expiration failure — so the operator lost both the log *and* the diagnosis. The call is now
delegated to a total `logSessionExpirationToRestoreLog(...)` helper that reports a logging failure to
the server log and swallows it, mirroring `logRestartFailure(...)` in §7.9. Locked in by
`FileServiceTest.shouldNotLetTheRestoreLogFailureMaskTheSessionExpiration`, verified to fail when the
fix is reverted.

#### Documentation corrections

* The `## 8. Integration test coverage` heading and its separator had been deleted by the §7.10
  edit, so §8.1/§8.2 rendered as subsections of the review pass. Restored.
* §4.2 attributed the changed logging call to the "fallback branch". The space-retry `catch` already
  logged the cause at `HEAD`; the branch that changed is the `else` sibling below it. Corrected.
* §4.1's message-bundle list did not mention `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED`,
  even though §6 and §7.10 record it, and §4.1 is where a reader of the platform change looks. Added,
  together with the R16 merge-order note.
* §7's intro said "Four review passes" while enumerating five. Corrected.

---

### 7.12 Sixth review pass (2026-09-04)

A review of the R16/R17 fixes. One code finding: R17 was an incomplete fix.

#### R18 — the `finally` was still not total

R17 wrapped `importLogger.error(...)` but left the two statements around it exposed:

```java
        sessionExpired = sessionExpired || sessionExpirationDetector.isSessionExpired( ... ); // unguarded
        if ( sessionExpired ) {
          logSessionExpirationToRestoreLog( importLogger );                                   // guarded by R17
        }
        importLogger.endJob();                                                                // unguarded
        try ( FileOutputStream outputStream = fileOutputStream ) { ... }
```

Either unguarded call throwing skips the try-with-resources entirely, so the restore log is never
flushed **and its descriptor is never closed**, and the throwable replaces whatever the caller was
about to be told. That is the defect class R13/R14/R17 exist to remove, still present in the block
R17 had just been declared total.

`endJob()` is the sharper case. In `Log4JRepositoryImportLogger`, `error( String )` and `endJob()`
share a failure mode — `getLog4JRepositoryImportLog()` raising
`IllegalStateException( "No job started for current Thread" )` — so the scenario R17 guards against
is precisely the one in which `endJob()` fails too, and R17's guard could not help. With the shipped
logger this is unreachable (the `ThreadLocal` is never removed, and `endJob()` swallows its own
`IOException`), but `IPlatformImporter.getRepositoryImportLogger()` is Spring-pluggable.

The probe is a subtler case: `SessionExpirationDetector.isSessionExpired(...)` only classifies
`IllegalStateException` as an expiry signal, so any other failure escaped. It is now routed through
`probeSessionExpiration()`, which reports the failure to the server log and answers **`false`** —
inconclusive, not expired. Claiming expiry on the strength of an unrelated error would put a
misleading explanation into the restore log, which is the failure mode this whole ticket is about.

Both calls now go through total helpers (`probeSessionExpiration()`, `endRestoreLog(...)`) that
mirror `logSessionExpirationToRestoreLog(...)`. Locked in by
`FileServiceTest.shouldFlushTheRestoreLogWhenEndJobAndTheSessionProbeBothFail`, which fails a
propagating import with *both* the probe and `endJob()` throwing and asserts the caller still
receives the original `PlatformImportException`, that no expiry line is written, and that the log
file still exists. Verified to fail when **either** guard is reverted independently.

#### Documentation corrections

* §4.1 claimed "five" restore session-expiry tests and "two" asserting `SESSION_EXPIRED` and the
  cause; the actual counts were six and three, and §6 already said six — the document contradicted
  itself. Corrected, and updated again for R18.
* §4.2's list of added `ScheduleImportUtilTest` methods omitted `restartFailedMessageResolves`.
* §4.1's new bundle bullet pointed at §4.2 for the merge-order constraint, which lives in §7.11.
* §6's `FileService` row described the restore-log write as "total"; that was only true of one
  statement. Reworded to describe the whole `finally`.

---

### 7.13 Localization sweep (2026-09-04)

Every user-facing string introduced by this change set is now resolved from a message bundle.

Three messages added by R17/R18 were hardcoded English literals:

```java
      logger.error( "Unable to record the session expiration in the restore log", loggingFailure );
      logger.error( "Unable to determine whether the HTTP session expired during the restore", probeFailure );
      logger.error( "Unable to complete the restore log", endJobFailure );
```

That contradicts the convention of the file they sit in — the neighbouring
`FileService.ERROR_UNABLE_TO_GET_PLATFORM_EXPORTER`, `FileService.ERROR_UNABLE_TO_GET_EXPORT_LOGGER`
and `FileService.WARN_UNABLE_TO_DELETE_TEMP_FILE` calls all resolve through
`Messages.getInstance().getString(...)`. Three keys were added to
`org/pentaho/platform/web/http/messages/messages.properties` following that naming, and the three
calls now route through a single `reportRestoreLogFailure( String messageKey, Throwable failure )`.

That indirection is not cosmetic. The three call sites are the `catch` blocks of the total helpers
guarding the `systemRestore(...)` `finally`, so resolving a bundle message there adds a call that
could itself throw, escape the helper and mask the propagating failure — reintroducing R13/R17/R18
by the back door. `reportRestoreLogFailure(...)` wraps the lookup *and* the server-log write in its
own `try`/`catch ( Throwable )`, so the helpers stay total.

Each message is covered by its own test asserting it is reported **where it should be**, rather than
by one bulk assertion that the keys resolve. Because those three messages never reach the restore log
or the client — the server log is their only destination — the tests attach a `LogUtil` appender to
`LogManager.getLogger( FileService.class )` and assert the captured output:

| Test | Injected failure | Asserts |
| --- | --- | --- |
| `shouldReportUnableToRecordSessionExpirationWhenTheRestoreLogRejectsTheMessage` | `importLogger.error(...)` throws | the server log carries `ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION`, and the caller still receives the curated `SESSION_EXPIRED` failure |
| `shouldReportUnableToProbeSessionWhenTheProbeFails` | `isSessionExpired(...)` throws | the server log carries `ERROR_UNABLE_TO_PROBE_SESSION`, the restore still succeeds, and the inconclusive probe is **not** reported to the user as an expiry |
| `shouldReportUnableToCompleteRestoreLogWhenEndJobFails` | `endJob()` throws | the server log carries `ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG` and the restore still succeeds |

Each test also calls `assertLocalized( key, resolvedMessage )` for its own message. That guard is not
redundant: the assertions compare the server log against the *same* resolved constant the production
code uses, so a missing key would make both sides `!key!` and the test would pass on a message that
had silently become the raw key — the tautology R16 exposed. Verified by renaming a key (only the
owning test failed) and by suppressing each `reportRestoreLogFailure(...)` call in turn (only the
corresponding test failed).

**Deliberately left as English literals:** the `debug(...)` calls in `ScheduleImportUtil` — including
`"Successfully started the scheduler"` from R14 — because that file localizes every `error`, `warn`
and `info` message while its `debug` diagnostics (lines 85, 89, 95, 119, 143) are literals. Also
`SessionExpirationDetector.SESSION_INVALIDATED_MARKER` (`"session already invalidated"`), which is
not a message at all: it is the Tomcat text being *matched against*, and translating it would break
detection.

---

### 7.14 Translating the new keys into the shipped locales (2026-09-04)

§7.13 localized the code; the keys still existed only in the two default bundles. All eight are now
translated into every locale variant that ships alongside them — 19 files, four keys each:

| Bundle | Locale files | Keys added |
| --- | --- | --- |
| `…/web/http/messages/` | `_cn`, `_de`, `_en`, `_es`, `_fr`, `_it`, `_ja`, `_nl`, `_pt`, `_zh_CN` | `FileService.ERROR_RESTORE_SESSION_EXPIRED`, `ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION`, `ERROR_UNABLE_TO_PROBE_SESSION`, `ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG` |
| `…/plugin/services/messages/` | `_cn`, `_de`, `_es`, `_fr`, `_it`, `_ja`, `_nl`, `_pt`, `_zh_CN` (no `_en` exists) | `CommandLineProcessor.ERROR_0012_SERVER_ERROR`, `ERROR_0013_RESTORE_CONNECTION_LOST`, `ERROR_0014_SERVER_ERROR_NO_DETAIL`, `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` |

**This is a quality change, not a behavioural one.** Java's parent-bundle fallback already resolved
every one of these keys to the default English text, which was confirmed empirically by loading each
locale through `ResourceBundle.getBundle(...)` before the translations were written: all returned the
English string and none raised `MissingResourceException`. The repository precedent agrees — commit
`e47e4a28a` (BISERVER-15169), which added the neighbouring `ERROR_0012`/`ERROR_0013` keys, touched the
default bundle only, and no locale file contains `FileService.ERROR_UNABLE_TO_GET_PLATFORM_EXPORTER`
either. The value here is that a non-English server now logs these in the operator's language.

Constraints observed while writing them:

* **Encoding.** Every one of these files is US-ASCII with `\uXXXX` escapes, so all non-ASCII
  characters are escaped. Verified with `file -b --mime-encoding` after the edit.
* **Line endings.** `messages_de`, `_fr`, `_ja` and `_zh_CN` under `web/http/messages/` are CRLF while
  their siblings are LF. The first attempt normalised them to LF and produced a ~312-line diff per
  file; they were repaired so each file's staged diff is exactly the four added lines.
* **Chinese variants.** `messages_cn` is *Traditional* and `messages_zh_CN` is *Simplified* — verified
  by inspecting the existing glyphs, not by assuming from the file name.
* **No invented `[xx_NNN]` markers.** `_es`, `_it`, `_nl` and `_pt` are mostly English stubs carrying
  legacy translation-tracking markers that map to an external TMS. Real translations were written
  without fabricating identifiers in that namespace.

Verified by re-loading all nine/ten locales for each bundle and asserting the *translated* text comes
back rather than the English fallback, and that the `{0}` placeholder survives in all three
parameterised `CommandLineProcessor` messages.

**Pre-existing weakness, not introduced here and not fixed here:** `ERROR_0012_SERVER_ERROR` and
`ERROR_0014_SERVER_ERROR_NO_DETAIL` substitute `{0}` with
`requestType.toString().toLowerCase( Locale.ROOT )` — the English words `restore`/`import` — so a
translated sentence still contains one English noun. Fixing it properly means adding two more keys
for the request type; noted in §9.3 rather than folded into this pass.

---

## 8. Integration test coverage (`qa-automation`)

### 8.1 Current state — there is none

`qa-automation` (TestNG + REST Assured, `pentaho-api` module) has **no coverage of
`/api/repo/files/systemBackup` or `/api/repo/files/systemRestore`**. Confirmed by:

```bash
cd ~/github/qa-automation
grep -rn "systemBackup\|systemRestore" --include=*.java --include=*.xml . | grep -v target   # no hits
```

The `FileManagement` service façade
(`pentaho-api/src/main/java/com/hitachivantara/qa/automation/api/pentaho/services/FileManagement.java`)
exposes `importFile(...)` and `importZip(...)` — i.e. the *per-folder* import endpoint — but nothing
for full system backup/restore. The 21 file-resource suites in
`pentaho-api/src/test/resources/tk421/xmlsuites/file_resource/` likewise cover ACL, children,
metadata, viewer, `FilesImport`, etc., but not backup/restore.

**Consequence:** the contract this fix introduces — a restore that fails because its session expired
must return **HTTP 500 with a `text/plain` explanation**, while every other failure keeps an
empty-bodied 500 — is currently protected only by unit tests, and nothing at all guards against the
endpoint regressing back to `Response.ok()`.

### 8.2 Recommended additions

Reproducing a real session timeout in CI needs a server restart with a patched
`WEB-INF/web.xml`, which is outside what the `pentaho-api` suites do. So split the coverage:

**A. New service façade** — `pentaho-api/src/main/java/.../services/SystemBackupRestore.java`,
following the existing `FileManagement extends Request<T>` pattern, with `systemBackup()` and
`systemRestore( File bundle, boolean overwrite, String logFile, String logLevel )` (multipart POST,
same field names as `CommandLineProcessor`: `fileUpload`, `overwriteFile`, `applyAclSettings`,
`overwriteAclSettings`, `logFile`, `logLevel`, `backupBundlePath`).

**B. New functional test class** —
`pentaho-api/src/test/java/.../functional/fileresource/SystemBackupRestoreTests.java` plus
`pentaho-api/src/test/resources/tk421/xmlsuites/file_resource/SystemBackupRestore.xml`:

| Test | Assertion | Protects |
| --- | --- | --- |
| `backupAndRestoreRoundTrip` | backup → 200 + non-empty zip; restore → 200; log tail contains `Restore complete` | the happy path is unchanged by this fix (regression row 4 of §5) |
| `restoreOfCorruptBundleReturnsServerErrorWithoutInternalDetail` | restore a truncated/garbage zip → **500**, and the body is **not** the `text/plain` internal message | **locks in the narrowed `FileResource` change** (§7.5); guards against R7 being reintroduced |
| `restoreAsNonAdminIsForbidden` | 403 | unchanged `SecurityException` mapping |
| `restoreWithInvalidLogFileNameIsRejected` | 400 | unchanged `validateFilePath` mapping |

**C. Session-expiry scenario — nightly/manual, not in the functional suite.** It requires
`<session-timeout>1</session-timeout>` in the deployed descriptor and a server restart. Best owned by
the Jenkins job under `qa-automation/jenkins/` as a dedicated stage that (1) patches `web.xml`,
(2) restarts, (3) runs `import-export.sh --restore`, (4) asserts the log contains
`HTTP session expired while the restore was still running` and does **not** contain
`Restore complete`, (5) reverts. This is exactly the §5 runbook, so it is cheap to lift.
This stage is also the only place that exercises the `text/plain` 500 body end to end, since it is
the only failure mode that produces one.

**D. Scheduler-state assertion.** The `pentaho-scheduler-plugin` fix guarantees the scheduler is
restarted after a failed import. Add to test B row 2 a follow-up call to `GET /api/scheduler/state`
asserting `RUNNING` — that is the only end-to-end guard for §4.2 and it is cheap, since
`pentaho-api/src/main/java/.../services/Schedules.java` already exists.

> **Priority:** B row 2 and D are the high-value additions — they are the only tests that would fail
> if this fix were reverted. A and B row 1 are prerequisites/regression safety. C is best-effort.

---

## 9. Follow-up ideas (not implemented)

### 9.1 Removing this class of failure

* **Asynchronous restore — the real long-term fix.** The defect is not the *shape* of the error, it
  is that a multi-hour operation is bound to an HTTP request and its session. Expose the restore as a
  job: `POST /api/repo/files/systemRestore` → `202 Accepted` + `Location: …/restoreJobs/{id}`, with a
  polling status resource. Session expiry then stops being a failure mode at all, the outcome becomes
  a *field of a status resource* rather than an HTTP error body, and the command line gains real
  progress reporting. Everything in this ticket is mitigation by comparison.
* **Decouple the restore from the HTTP session** — swap in a detached `StandaloneSession` for the
  duration of the restore. Cheaper than the above and removes the same failure mode, but leaves the
  request thread blocked for hours.
* **Non-zero exit code** — `CommandLineProcessor.main()` never calls `System.exit`, so
  `import-export.sh` always exits `0`. Scripted restores cannot detect failures. Adding an exit code
  requires reworking `CommandLineProcessorTest`, which calls `main()` directly.

### 9.2 Platform-wide REST error handling

`pentaho-platform` has **no global error handling today**: `grep -rl "ExceptionMapper" --include="*.java"`
returns zero hits across the whole repository, and `org/pentaho/platform/web/http/api/resources`
contains at least five competing ad-hoc patterns — `throw new WebApplicationException( e, Status )`,
`buildServerErrorResponse( Throwable )`, `buildServerErrorResponse( String )`,
`buildSafeHtmlServerErrorResponse( Exception )` and raw `Response.serverError()`.

Meanwhile `com.pentaho:pentaho-problem-details-impl` (RFC 9457) is already the convention in the
app-shell-era backends — `pentaho-app-shell` `core`, `settings` and `browse-files` all depend on it,
and the front ends consume `@pentaho-apps/pentaho-problem-details-api`. It has **zero consumers in
`pentaho-platform`**. Note that it is used as a plain collaborator, not as a JAX-RS provider:

```java
    } catch ( ServiceException e ) {
      return exceptionHandler.handle( e );
    }
```

A single `@Provider ExceptionMapper<Throwable>` that delegates `ServiceException` to
`ServiceExceptionHandler` and sanitizes everything else would collapse those five patterns. It is
*technically trivial* to install — `JAXRSApplication` (`extensions/.../web/servlet/jersey/JAXRSApplication.java`)
does `packages( "com.pentaho", "org.pentaho", "com.hitachivantara", "org.hitachivantara" )`, so Jersey
auto-discovers any `@Provider` in those packages. That is precisely why it must be **its own ticket**:
one class would rewrite the error contract of every platform REST endpoint at once. Doing it
endpoint-by-endpoint here would also be pointless, because `ServiceExceptionHandler` renders HTTP 500
as `text/plain` + message — the same bytes `systemRestore` already returns (§4.4).

A staged path, in order:

1. Adopt `com.pentaho.service.exceptions.*` in the service layer (`FileService` and friends) so
   resources stop hand-translating exceptions.
2. Register one global `ExceptionMapper` and delete the ad-hoc `buildXxxErrorResponse` helpers.
3. Replace bespoke status codes such as `PlatformImportException.SESSION_EXPIRED` with Problem
   Details `type` URIs, which are the properly versioned form of the same machine-readable marker.

### 9.3 Housekeeping

* ~~**Localised bundles** — the new keys were added to the default `messages.properties` only; the
  `_ja`, `_de`, `_fr`, … bundles fall back to the default and should be translated.~~ Done in §7.14.
* **Request type is not translated** — `CommandLineProcessor.ERROR_0012_SERVER_ERROR` and
  `ERROR_0014_SERVER_ERROR_NO_DETAIL` fill `{0}` with `requestType.toString().toLowerCase( Locale.ROOT )`,
  so the English word `restore` or `import` appears inside an otherwise translated sentence (§7.14).
  Two additional keys for the request type would close it.

---

## 10. Changelog

| Date | Author | Change |
| --- | --- | --- |
| 2026-08-25 | @afrjorge (with GitHub Copilot CLI) | Initial version. Documented the root cause (Tomcat session expiry during a long synchronous restore), the reproduction with a 1-minute `<session-timeout>`, the code fix across `pentaho-platform` and `pentaho-scheduler-plugin`, the AI runbook, and the recorded verification results. |
| 2026-08-26 | @afrjorge (with GitHub Copilot CLI) | Post-implementation review. Made session detection locale-independent, preserved unrelated checked and runtime failures, closed the restore log stream, guaranteed a scheduler restart attempt after interrupted imports, expanded focused regression coverage, and documented the new error-handling and scheduler-lifecycle contracts in code. |
| 2026-08-28 | @afrjorge (with GitHub Copilot CLI) | Code review of the local working-tree changes. Re-ran the unit suites in both repositories (124 + 22 tests, all green). Added §7 with two should-fix findings (`ScheduleImportUtil` checked-exception masking in the new `finally`, locale-sensitive `toLowerCase()` in `CommandLineProcessor`), five secondary observations and three nits; added §8 documenting that `qa-automation` has zero backup/restore coverage today and proposing the service façade, functional suite and scheduler-state assertion needed to guard this fix; listed the missing `CommandLineProcessorTest` change in §6. |
| 2026-08-28 | @afrjorge (with GitHub Copilot CLI) | Applied the review fixes (§7.4). R1: `ScheduleImportUtil.doImport()` now guards with `catch ( Throwable t )` and a `Throwable importFailure`, so a checked `ImportException` or an `Error` can no longer be replaced by a scheduler-restart failure; added `testDoImport_restartFailureDoesNotMaskImportFailure` to lock it in. R2: `CommandLineProcessor` renders the request type with `toLowerCase( Locale.ROOT )`. Nits 2–3: `FileServiceTest` now uses `assertSame`/`never()` and asserts against the resolved `FileService.ERROR_RESTORE_SESSION_EXPIRED` bundle value instead of an English substring. Verified: platform 124 tests, scheduler 23 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Reviewed the REST error contract against `pentaho-problem-details` and resolved **R7** (§7.5). Added `PlatformImportException.SESSION_EXPIRED` as the machine-readable marker for "this message is safe to return to the client"; `FileService` tags its throw with it and the new `FileResource.buildRestoreFailureResponse(...)` returns the `text/plain` explanation only for that status, restoring the pre-fix empty-bodied 500 for every other import failure. `CommandLineProcessor` now appends a 500 body only when it is `text/plain` and falls back to the new `ERROR_0014_SERVER_ERROR_NO_DETAIL` when there is no usable explanation, so Tomcat HTML error pages are no longer pasted into `restore.log`. Added §4.4 (API compatibility matrix), rewrote §9 into 9.1/9.2/9.3 recording the async-restore and platform-wide `ExceptionMapper` follow-ups, and retargeted the §8.2 corrupt-bundle test. Verified: 225 tests, `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Final review pass over `pentaho-platform/extensions/` and `pentaho-scheduler-plugin/core/` (§7.6). Caught and fixed **R3**, a regression introduced by §7.5: the `ERROR_0014` fallback wiped datasource import diagnostics on an HTTP 500 for `--import`, because the datasource branch is evaluated first and never set the "detail appended" flag. Confirmed empirically with a throwaway test, then locked in with `logResponseMessageKeepsDatasourceDetailOnImportServerError`. Fixed **R8**: the scheduler restart guard in the new `finally` was `catch ( RuntimeException )` while the import guard is `catch ( Throwable )`, so an `Error` from `start()` masked the in-flight failure; added `testDoImport_restartErrorDoesNotMaskImportFailure`. Cleanups: merged the duplicated `instanceof IllegalStateException` guards in `SessionExpirationDetector` and documented the **R6** contract on `isSessionExpired(...)`; removed javadoc added to seven `ScheduleImportUtil` methods unrelated to the fix. Corrected the stale §5 runbook command/count, the §7 file count (10 → 11) and the §4.1/§6 test tallies. Both new tests verified to fail when their fix is reverted. Verified: platform 226 tests, scheduler 24 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Documented **R4** and **R6** in code and closed them (§7.7). R4: the unconditional session probe in the `systemRestore(...)` `finally` now carries a comment and a javadoc note explaining the split responsibility — the console reports what interrupted the operation, the restore log explains why the content is incomplete — so a future cleanup does not "simplify" it away. R6: `SessionExpirationDetector`'s class javadoc now states that detection is best-effort and one-sided (`true` is reliable, `false` is not proof), and enumerates both false-negative paths (a detached `StandaloneSession`, and a cause chain scrubbed by `ExceptionLoggingDecorator`/`SolutionImportHandler`); this is why `systemRestore(...)` uses both signals rather than either alone. Added §7.8 with a full analysis of **R5** — the restore-log descriptor leaks if `PentahoSystem.get( IPlatformImporter.class )` returns `null` or `startJob(...)` throws — including the proposed `openRestoreLog(...)` extraction, the three correctness constraints on it, and the suggested `FileServiceTest` case; recommended as a separate follow-up commit so the resource-management diff reviews cleanly. Verified: platform 226 tests, `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Final code, documentation and end-to-end review. **Live verification (§5.1):** re-ran the full runbook against the final change set with both patched jars deployed, producing a restore that fails with the session-expiry explanation (0/55 schedules, 970 `ERROR`s, no `Restore complete`) and a clean restore that succeeds (55/55 schedules, 0 `ERROR`s, `Restore complete`); `Successfully started the scheduler` appears in both, confirming the restart path is unbroken. **Fixed R9 (§7.9):** `FileService.systemRestore()` discarded the failure that revealed the expiration — it is now logged and chained into the `SESSION_EXPIRED` exception via the existing `( String, int, Throwable )` constructor, so the server log keeps the stack identifying which layer rejected the invalidated session; the client-facing response is unchanged because only `getLocalizedMessage()` is echoed. **Fixed R13 (§7.9):** `ScheduleImportUtil`'s restart guard logged before deciding what to do with the failure, so a throwing import logger — plausible precisely when the restore log is broken — escaped the `finally` and masked the in-flight failure; the suppression decision now happens first and `logRestartFailure(...)` is total. Added `testDoImport_restartFailureLoggingFailureDoesNotMaskImportFailure` and cause assertions to both `FileServiceTest` expiry tests; both new guards confirmed to fail when their fix is reverted. **Recorded as open in §7.2:** R10 (the detector's stack-frame fallback matches only `StandardSession.getAttribute`, missing `setAttribute` and friends on a localized Tomcat), R11 (a non-`text/plain` 500 body is dropped from the log file as well as the console, which will matter once §9.2 introduces `application/json` problem bodies) and R12 (the R4 combination documented in §7.7 has no test). Verified: platform 226 tests, scheduler 25 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Fourth review pass, over the §7.9 fixes themselves plus a documentation audit (§7.10). **Fixed R14:** the `ScheduleImportUtil` restart guard also covered the success `debug(...)` call, so a throwing import logger after a *successful* restart was reported as a restart failure — or rethrown, converting a completed restore into a failed one. The guard now wraps only `schedulerResource.start()`; a `schedulerRestarted` flag drives a new total `logSchedulerRestarted(...)` helper that runs after it. Locked in by `testDoImport_successLoggingFailureDoesNotFailTheImport`, verified to fail when the fix is reverted. **Fixed R15:** `logRestartFailure(...)` used a hardcoded English literal; it now resolves the new `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` key, added to the platform message bundle that backs all `SolutionImportHandler.*` keys. **Documentation:** corrected the §5.1 claim that `Successfully started the scheduler` was end-to-end evidence for the §4.2 fix (per-schedule failures never escape the loop, so the unpatched code reached `start()` too — the `try/finally` only matters when a throwable escapes the loop body, which the run did not exercise); qualified the §4.1 `text/plain` rule as applying to `--restore` only; corrected the §7 heading range, the §7.3 nit's stale line numbers and the §5 runbook log filename. Verified: platform 226 tests, scheduler 26 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Fifth review pass, over the §7.10 fixes and this document (§7.11). **Fixed R16:** the message key added by R15 is defined in `pentaho-platform` but consumed by `pentaho-scheduler-plugin`, so against the installed (pre-change) `pentaho-platform-extensions` jar `MessageUtil` silently degraded it to `!SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED!` — the raw key written into the restore log — and nothing failed, because the test constant resolved from the same bundle and so compared `"!key!"` with `"!key!"`. Added `restartFailedMessageResolves()`, observed red against the unpublished jar and green after `mvn -pl extensions install`, and recorded the resulting merge-order constraint (the platform change must be published before or with the scheduler change). **Fixed R17:** `FileService.systemRestore()`'s `finally` called `importLogger.error(...)` before `endJob()` and before the log was flushed, so a failing import logger skipped both and masked the session-expiration failure — the same defect class as R13/R14, in the block this fix introduced. Reporting is now delegated to a total `logSessionExpirationToRestoreLog(...)`, locked in by `shouldNotLetTheRestoreLogFailureMaskTheSessionExpiration` and verified to fail when reverted. **Documentation:** restored the `## 8. Integration test coverage` heading deleted by the §7.10 edit (which had left §8.1/§8.2 nested under the review pass); corrected §4.2, which attributed the changed logging call to the space-retry "fallback" branch when that branch already logged the cause at `HEAD` and the `else` sibling below it is the one that changed; added the new key to §4.1's bundle list; corrected "Four" to "Five" review passes. Verified: platform 227 tests, scheduler 27 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Sixth review pass (§7.12). **Fixed R18:** R17 had made only the middle statement of the `systemRestore(...)` `finally` total — the session probe and `importLogger.endJob()` were still unguarded, so either could skip the try-with-resources, leaving the restore log unflushed and its descriptor open, and replace the in-flight failure. `Log4JRepositoryImportLogger.error( String )` and `endJob()` share a failure mode (`IllegalStateException( "No job started for current Thread" )`), so the scenario R17 guarded against is exactly the one where `endJob()` fails too. Both now route through total helpers `probeSessionExpiration()` and `endRestoreLog(...)`; the probe deliberately answers `false` on an inconclusive failure, because claiming expiry on the strength of an unrelated error would write a misleading explanation into the restore log. Locked in by `shouldFlushTheRestoreLogWhenEndJobAndTheSessionProbeBothFail`, verified to fail when either guard is reverted independently. **Documentation:** corrected §4.1's test counts ("five"/"two" → seven/three, contradicting §6's own figure), added `restartFailedMessageResolves` to §4.2's list, repointed §4.1's merge-order cross-reference from §4.2 to §7.11, and reworded §6's `FileService` row, which described the restore-log write as "total" when only one statement was. Verified: platform 228 tests, scheduler 27 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Localization sweep (§7.13). The three server-log messages added by R17/R18 in `FileService` were hardcoded English literals, contradicting the convention of the surrounding code, where `FileService.ERROR_UNABLE_TO_GET_PLATFORM_EXPORTER`, `ERROR_UNABLE_TO_GET_EXPORT_LOGGER` and `WARN_UNABLE_TO_DELETE_TEMP_FILE` all resolve through `Messages.getInstance().getString(...)`. Added `FileService.ERROR_UNABLE_TO_RECORD_SESSION_EXPIRATION`, `ERROR_UNABLE_TO_PROBE_SESSION` and `ERROR_UNABLE_TO_COMPLETE_RESTORE_LOG`, routed through a new `reportRestoreLogFailure( String, Throwable )` helper — the indirection matters because those call sites are the `catch` blocks of the total helpers guarding the `finally`, so an unguarded bundle lookup there could throw, escape and mask the propagating failure, reintroducing R13/R17/R18. Added `restoreMessagesAreLocalizable()`, which pins all four restore keys using the `!key!` detection from R16 and was verified by renaming a key. Deliberately left literal: `ScheduleImportUtil`'s `debug(...)` diagnostics, which that file has always kept in English while localizing every `error`/`warn`/`info`, and `SessionExpirationDetector.SESSION_INVALIDATED_MARKER`, which is Tomcat text being matched against rather than a message. Verified: platform 229 tests, scheduler 27 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Replaced the bulk `restoreMessagesAreLocalizable()` assertion with one behavioural test per message (§7.13): `shouldReportUnableToRecordSessionExpirationWhenTheRestoreLogRejectsTheMessage`, `shouldReportUnableToProbeSessionWhenTheProbeFails` and `shouldReportUnableToCompleteRestoreLogWhenEndJobFails`. Each injects the specific failure and asserts, via a `LogUtil` appender on `LogManager.getLogger( FileService.class )`, that the corresponding message actually reaches the server log — which is its only destination, since these never reach the restore log or the client — while also pinning the surrounding behaviour (the curated `SESSION_EXPIRED` failure still propagates; a failing probe or `endJob()` does not fail an otherwise successful restore; an inconclusive probe is not reported as an expiry). Each retains a per-message `assertLocalized(...)` guard, which is not redundant because the log assertion compares against the same resolved constant the production code uses, so a missing key would make both sides `!key!`. Verified by renaming a key and by suppressing each `reportRestoreLogFailure(...)` call in turn — in every case only the owning test failed. Verified: platform 231 tests, scheduler 27 tests, both `BUILD SUCCESS`. |
| 2026-09-04 | @afrjorge (with GitHub Copilot CLI) | Translated the eight new message keys into every shipped locale (§7.14): the four `FileService.*` keys into the ten `web/http/messages/messages_*.properties` variants and the three `CommandLineProcessor.*` keys plus `SolutionImportHandler.ERROR_0002_SCHEDULER_RESTART_FAILED` into the nine `plugin/services/messages/messages_*.properties` variants — 19 files, four keys each. Established first that this is a quality change rather than a behavioural one: loading each locale through `ResourceBundle.getBundle(...)` before the edit returned the English default for all eight keys with no `MissingResourceException`, and the precedent commit `e47e4a28a` (BISERVER-15169), which added the neighbouring `ERROR_0012`/`ERROR_0013`, touched the default bundle only. Observed the constraints the existing files impose: all are US-ASCII, so every non-ASCII character is written as a `\uXXXX` escape; `messages_cn` is Traditional Chinese while `messages_zh_CN` is Simplified, confirmed by inspecting the existing glyphs; and the `_es`/`_it`/`_nl`/`_pt` stubs carry legacy `[xx_NNN]` markers belonging to an external TMS, which were deliberately not fabricated for the new entries. Caught and repaired a self-inflicted regression: `web/http/messages/messages_{de,fr,ja,zh_CN}.properties` are CRLF while their siblings are LF, and the first edit normalised them to LF, turning a four-line addition into a ~312-line rewrite; each file's staged diff is now exactly the four added lines. Verified by re-loading every locale and asserting the translated text — not the English fallback — comes back, and that the `{0}` placeholder survives in all three parameterised messages. Recorded in §9.3 the pre-existing weakness that `ERROR_0012`/`ERROR_0014` inject the English word `restore`/`import` into `{0}`. Verified: platform 231 tests, `BUILD SUCCESS`. |
