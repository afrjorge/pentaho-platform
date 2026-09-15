/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 - 2026 by Pentaho Canada Inc. : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2030-06-15
 ******************************************************************************/


package org.pentaho.platform.plugin.services.importexport;

import org.pentaho.platform.api.engine.IPentahoSession;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Set;

/**
 * Detects whether the HTTP session backing a long-running import/export request has already been invalidated by the
 * servlet container.
 * <p>
 * Long-running operations such as a full system restore are executed synchronously on the request thread. The servlet
 * container only refreshes the session access time when a request starts, so a restore that runs for longer than the
 * configured {@code <session-timeout>} has its session expired while it is still in progress. Every subsequent
 * repository, security or scheduler call then fails with an unrelated looking error, which makes the resulting restore
 * log very hard to interpret.
 * <p>
 * This detector gives callers a cheap way to distinguish "the session expired" from genuine content failures so that
 * an actionable message can be reported to the user. Direct probes do not inspect exception text, because Tomcat
 * localizes its invalid-session messages. Cause-chain inspection supports both the English message and Tomcat's
 * locale-independent stack frame.
 * <p>
 * <b>Detection is best-effort and deliberately one-sided.</b> A {@code true} result is reliable: something really did
 * touch an invalidated session. A {@code false} result only means no evidence was found, and must never be read as
 * proof that the session is alive. Both methods can miss an expiration, for different reasons:
 * <ul>
 *   <li>{@link #isSessionExpired(IPentahoSession)} depends on being handed the container-backed session for the
 *       request thread. A detached implementation such as {@code StandaloneSession} answers attribute reads normally
 *       and therefore always reports {@code false}.</li>
 *   <li>{@link #isCausedBySessionExpiration(Throwable)} depends on the container error still being reachable in the
 *       cause chain. Layers that log and swallow — {@code ExceptionLoggingDecorator} rewrapping into a generic
 *       {@code UnifiedRepositoryException}, or {@code SolutionImportHandler} continuing past per-item failures — can
 *       drop it entirely.</li>
 * </ul>
 * Callers should therefore combine both signals, as {@code FileService.systemRestore(...)} does: inspect the failure
 * that escaped, and additionally probe the session once the operation has finished.
 */
public class SessionExpirationDetector {

  /**
   * Attribute used only to probe the underlying {@code HttpSession}. Reading an attribute of an invalidated session
   * throws {@link IllegalStateException}; the attribute does not need to exist.
   */
  private static final String SESSION_LIVENESS_PROBE_ATTRIBUTE =
    "org.pentaho.platform.importexport.sessionLivenessProbe";

  private static final String SESSION_INVALIDATED_MARKER = "session already invalidated";

  /**
   * Checks whether the given session has already been invalidated by the servlet container. Any
   * {@link IllegalStateException} from this private attribute read is treated as expiration because a valid
   * {@link IPentahoSession} permits attribute reads regardless of whether the attribute exists.
   * <p>
   * <b>Contract:</b> the caller must pass the session actually bound to the request thread, normally
   * {@code PentahoSessionHolder.getSession()}. A detached session implementation such as
   * {@code StandaloneSession} never throws on attribute reads, so it always reports {@code false} and the caller
   * cannot rely on this probe alone.
   *
   * @param session the session to probe, may be {@code null}
   * @return {@code true} when the session is known to have been invalidated, {@code false} otherwise
   */
  public boolean isSessionExpired( IPentahoSession session ) {
    if ( session == null ) {
      return false;
    }

    try {
      session.getAttribute( SESSION_LIVENESS_PROBE_ATTRIBUTE );
      return false;
    } catch ( IllegalStateException e ) {
      return true;
    }
  }

  /**
   * Walks the cause chain looking for the servlet container error raised when an invalidated session is accessed.
   * Detection uses either the English invalid-session marker or Tomcat's {@code StandardSession.getAttribute} stack
   * frame, which remains stable when the exception message is localized. Circular cause chains are traversed safely.
   *
   * @param throwable the throwable to inspect, may be {@code null}
   * @return {@code true} when the throwable was caused by an expired session; {@code false} when no evidence was
   *         found, which is not proof that the session is alive
   */
  public boolean isCausedBySessionExpiration( Throwable throwable ) {
    Set<Throwable> visited = Collections.newSetFromMap( new IdentityHashMap<>() );
    Throwable current = throwable;
    while ( current != null && visited.add( current ) ) {
      if ( current instanceof IllegalStateException
        && ( hasInvalidatedSessionMessage( current ) || originatesFromTomcatSessionAttributeAccess( current ) ) ) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private boolean hasInvalidatedSessionMessage( Throwable throwable ) {
    String message = throwable.getMessage();
    return message != null && message.toLowerCase( Locale.ROOT ).contains( SESSION_INVALIDATED_MARKER );
  }

  private boolean originatesFromTomcatSessionAttributeAccess( Throwable throwable ) {
    for ( StackTraceElement stackTraceElement : throwable.getStackTrace() ) {
      if ( stackTraceElement.getClassName().startsWith( "org.apache.catalina.session.StandardSession" )
        && "getAttribute".equals( stackTraceElement.getMethodName() ) ) {
        return true;
      }
    }
    return false;
  }
}
