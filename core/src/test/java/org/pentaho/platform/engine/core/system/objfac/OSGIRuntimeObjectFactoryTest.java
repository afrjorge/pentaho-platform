/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2028-08-13
 ******************************************************************************/


package org.pentaho.platform.engine.core.system.objfac;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.pentaho.platform.api.engine.IPentahoObjectReference;
import org.pentaho.platform.api.engine.IPentahoObjectRegistration;
import org.pentaho.platform.engine.core.system.objfac.references.SingletonPentahoObjectReference;

import java.util.Collections;
import java.util.Dictionary;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Created by nbaker on 5/3/15.
 */
public class OSGIRuntimeObjectFactoryTest {

  private OSGIRuntimeObjectFactory objectFactory;

  @Mock
  private BundleContext bundleContext;

  @Before
  public void setup() {
    objectFactory = new OSGIRuntimeObjectFactory();
    MockitoAnnotations.initMocks( this );
  }


  @Test
  public void testRegisterReferenceHeldUntilOSGIReady() throws Exception {

    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );
    String s = objectFactory.get( String.class, null );
    assertEquals( "Testing", s );
    objectFactory.setBundleContext( bundleContext );

    ArgumentCaptor<ServiceFactory> serviceFactoryArgumentCaptor = ArgumentCaptor.forClass( ServiceFactory.class );
    verify( bundleContext ).registerService( eq( String.class.getName() ), serviceFactoryArgumentCaptor.capture(),
        any( Dictionary.class ) );
    Object service = serviceFactoryArgumentCaptor.getValue().getService( null, null );
    assertEquals( "Testing", service );

  }


  @Test
  public void testRegisterReferencePassesToOSGI() {
    objectFactory.setBundleContext( bundleContext );
    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );
    ArgumentCaptor<ServiceFactory> serviceFactoryArgumentCaptor = ArgumentCaptor.forClass( ServiceFactory.class );
    verify( bundleContext ).registerService( eq( String.class.getName() ), serviceFactoryArgumentCaptor.capture(),
        any( Dictionary.class ) );
    Object service = serviceFactoryArgumentCaptor.getValue().getService( null, null );
    assertEquals( "Testing", service );

  }

  @Test
  public void testObjectDefined() throws Exception {

    assertFalse( objectFactory.objectDefined( String.class ) );

    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    IPentahoObjectRegistration iPentahoObjectRegistration = objectFactory.registerReference( ref, String.class );
    String s = objectFactory.get( String.class, null );
    assertEquals( "Testing", s );
    assertTrue( objectFactory.objectDefined( String.class ) );
    ServiceRegistration registration = mock( ServiceRegistration.class );
    ServiceRegistration registration2 = mock( ServiceRegistration.class );
    ServiceReference mockRef = mock( ServiceReference.class );

    when( bundleContext.registerService( eq( String.class.getName() ), any(), any( Dictionary.class ) ) ).thenReturn(
      registration );
    when( bundleContext.registerService( eq( IPentahoObjectReference.class.getName() ), any(), any( Dictionary.class ) ) ).thenReturn( registration2 );

    objectFactory.setBundleContext( bundleContext );
    when( bundleContext.getServiceReference( String.class ) ).thenReturn( mockRef );
    assertTrue( objectFactory.objectDefined( String.class ) );
    iPentahoObjectRegistration.remove();
    verify( registration, times( 1 ) ).unregister();

  }

  /**
   * [PDI-20686] A bundle refresh invalidates the BundleContext this factory holds. Registering against it
   * must not propagate the IllegalStateException - that aborts the activation of whichever bundle happens
   * to be registering - it must fall back to the non-OSGI factory instead.
   */
  @Test
  public void testRegisterReferenceHeldWhenBundleContextIsInvalid() throws Exception {
    objectFactory.setBundleContext( bundleContext );
    when( bundleContext.getBundle() ).thenThrow( new IllegalStateException( "Invalid BundleContext." ) );

    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );

    verify( bundleContext, never() ).registerService( anyString(), any(), any( Dictionary.class ) );
    assertEquals( "Testing", objectFactory.get( String.class, null ) );
  }

  /**
   * [PDI-20686] And once the bundle that owns the context comes back, the held registration is published.
   */
  @Test
  public void testRegistrationHeldWhileInvalidIsReplayedOnTheNextBundleContext() {
    objectFactory.setBundleContext( bundleContext );
    when( bundleContext.getBundle() ).thenThrow( new IllegalStateException( "Invalid BundleContext." ) );

    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );

    BundleContext newBundleContext = mock( BundleContext.class );
    objectFactory.setBundleContext( newBundleContext );

    ArgumentCaptor<ServiceFactory> serviceFactoryArgumentCaptor = ArgumentCaptor.forClass( ServiceFactory.class );
    verify( newBundleContext ).registerService( eq( String.class.getName() ), serviceFactoryArgumentCaptor.capture(),
        any( Dictionary.class ) );
    assertEquals( "Testing", serviceFactoryArgumentCaptor.getValue().getService( null, null ) );
  }

  /**
   * [PDI-20686] The context may also be invalidated between the check and the call.
   */
  @Test
  public void testRegisterReferenceHeldWhenBundleContextGoesInvalidMidRegistration() throws Exception {
    objectFactory.setBundleContext( bundleContext );
    when( bundleContext.registerService( eq( String.class.getName() ), any(), any( Dictionary.class ) ) )
        .thenThrow( new IllegalStateException( "Invalid BundleContext." ) );

    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );

    BundleContext newBundleContext = mock( BundleContext.class );
    objectFactory.setBundleContext( newBundleContext );
    verify( newBundleContext ).registerService( eq( String.class.getName() ), any( ServiceFactory.class ),
        any( Dictionary.class ) );
  }

  /**
   * [PDI-20686] Lookups must degrade to the non-OSGI factory while the context is invalid, rather than
   * throwing. {@code getServiceReference} used to sit outside the guarded block.
   */
  @Test
  public void testObjectDefinedFallsBackWhenBundleContextIsInvalid() throws Exception {
    SingletonPentahoObjectReference<String> ref = new SingletonPentahoObjectReference<>( String.class, "Testing",
      Collections.singletonMap( "foo", "bar" ), 10 );
    objectFactory.registerReference( ref, String.class );
    objectFactory.setBundleContext( bundleContext );

    when( bundleContext.getBundle() ).thenThrow( new IllegalStateException( "Invalid BundleContext." ) );

    assertTrue( objectFactory.objectDefined( String.class ) );
    assertFalse( objectFactory.objectDefined( Integer.class ) );
    assertEquals( "Testing", objectFactory.get( String.class, null ) );
  }

}