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

import org.junit.Before;
import org.junit.Test;
import org.pentaho.platform.api.engine.IPentahoSession;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SessionExpirationDetectorTest {

  private SessionExpirationDetector detector;

  @Before
  public void setUp() {
    detector = new SessionExpirationDetector();
  }

  //region isSessionExpired
  @Test
  public void shouldReturnFalseWhenSessionIsNull() {
    assertFalse( detector.isSessionExpired( null ) );
  }

  @Test
  public void shouldReturnFalseWhenSessionIsStillValid() {
    IPentahoSession session = mock( IPentahoSession.class );
    when( session.getAttribute( anyString() ) ).thenReturn( null );

    assertFalse( detector.isSessionExpired( session ) );
  }

  @Test
  public void shouldReturnTrueWhenSessionThrowsIllegalStateException() {
    IPentahoSession session = mock( IPentahoSession.class );
    when( session.getAttribute( anyString() ) ).thenThrow( new IllegalStateException( "localized container message" ) );

    assertTrue( detector.isSessionExpired( session ) );
  }
  //endregion

  //region isCausedBySessionExpiration
  @Test
  public void shouldReturnFalseForNullThrowable() {
    assertFalse( detector.isCausedBySessionExpiration( null ) );
  }

  @Test
  public void shouldDetectSessionExpirationDeepInTheCauseChain() {
    Throwable root = new IllegalStateException( "getAttribute: Session already invalidated" );
    Throwable wrapped = new RuntimeException( "General Error", new IllegalArgumentException( "wrapper", root ) );

    assertTrue( detector.isCausedBySessionExpiration( wrapped ) );
  }

  @Test
  public void shouldReturnFalseForUnrelatedCauseChain() {
    Throwable wrapped = new RuntimeException( "General Error", new IllegalStateException( "not related" ) );

    assertFalse( detector.isCausedBySessionExpiration( wrapped ) );
  }

  @Test
  public void shouldDetectLocalizedSessionExpirationFromTomcatStackTrace() {
    Throwable expiredSession = new IllegalStateException( "getAttribute: Session déjà invalidée" );
    expiredSession.setStackTrace( new StackTraceElement[] {
      new StackTraceElement( "org.apache.catalina.session.StandardSession", "getAttribute",
        "StandardSession.java", 876 )
    } );

    assertTrue( detector.isCausedBySessionExpiration( new RuntimeException( "General Error", expiredSession ) ) );
  }

  @Test
  public void shouldNotLoopForeverOnCircularCauseChain() {
    Throwable first = new RuntimeException( "first" );
    Throwable second = new RuntimeException( "second", first );
    first.initCause( second );

    assertFalse( detector.isCausedBySessionExpiration( first ) );
  }
  //endregion
}
