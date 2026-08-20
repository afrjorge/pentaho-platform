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

import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.InvalidSyntaxException;
import org.osgi.framework.ServiceFactory;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;
import org.pentaho.platform.api.engine.IPentahoObjectReference;
import org.pentaho.platform.api.engine.IPentahoObjectRegistration;
import org.pentaho.platform.engine.core.system.objfac.references.SingletonPentahoObjectReference;
import org.pentaho.platform.engine.core.system.objfac.spring.SpringPentahoObjectReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Created by nbaker on 4/27/15.
 */
public class OSGIRuntimeObjectFactory extends RuntimeObjectFactory {
  public static final String REFERENCE_CLASS = "reference_class";
  private volatile BundleContext bundleContext;
  private AtomicBoolean osgiInitialized = new AtomicBoolean( false );
  private List<OSGIPentahoObjectRegistration> deferredRegistrations = new ArrayList<OSGIPentahoObjectRegistration>();
  private Logger logger = LoggerFactory.getLogger( getClass() );

  public OSGIRuntimeObjectFactory() {
  }

  public void setBundleContext( BundleContext bundleContext ) {

    this.bundleContext = bundleContext;
    // Migrate previously registered entries to OSGI

    List<OSGIPentahoObjectRegistration> pending;
    synchronized ( deferredRegistrations ) {
      pending = new ArrayList<OSGIPentahoObjectRegistration>( deferredRegistrations );
      deferredRegistrations.clear();
    }

    // Replayed outside of the lock: a replay may itself defer again (if the new context is already
    // invalid), which would otherwise modify the list being iterated.
    for ( OSGIPentahoObjectRegistration osgiPentahoObjectRegistration : pending ) {
      ObjectRegistration deferredRegistration = osgiPentahoObjectRegistration.iPentahoObjectRegistration;
      Class<?>[] classes = deferredRegistration.getPublishedClasses()
          .toArray( new Class<?>[ deferredRegistration.getPublishedClasses().size() ] );
      this.registerReference( deferredRegistration.getReference(), osgiPentahoObjectRegistration, classes );
    }
    osgiInitialized.set( true );


  }

  /**
   * [PDI-20686] Answers whether the current {@link BundleContext} can still be used.
   * <p>
   * The context is a static, process-wide reference handed over once by the bundle that bridges the
   * PentahoSystem to OSGI. When that bundle is stopped - which happens on every bundle refresh, for
   * instance while a KAR is being un/redeployed into a running instance - its context is invalidated,
   * but this factory keeps holding it until the bundle comes back and hands over a new one. Using the
   * stale context throws {@code IllegalStateException: Invalid BundleContext}, which, when it happens
   * inside a bundle activator, aborts that bundle's activation.
   */
  private boolean isBundleContextUsable() {
    BundleContext context = this.bundleContext;
    if ( context == null ) {
      return false;
    }
    try {
      context.getBundle();
      return true;
    } catch ( IllegalStateException e ) {
      return false;
    }
  }

  public <T> IPentahoObjectRegistration registerReference( final IPentahoObjectReference<?> reference,
                                                           OSGIPentahoObjectRegistration existingRegistration,
                                                           Class<?>... classes ) {

    if ( !isBundleContextUsable() ) {
      return deferRegistration( reference, existingRegistration, classes );
    }
    Hashtable<String, Object> hashtable = new Hashtable<String, Object>();
    hashtable.putAll( reference.getAttributes() );

    List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();
    for ( Class<?> aClass : classes ) {
      try {

        // When OSGI R6 is released we can use the PrototypeServiceFactory. Until then we can't support factory
        // references unless the IPentahoObjectReference is a Singleton scope
        if ( reference instanceof SingletonPentahoObjectReference
            || reference instanceof SpringPentahoObjectReference && ( hashtable.get( "scope" ).equals( "singleton" ) ) ) {
          ServiceFactory<Object> factory = new ServiceFactory<Object>() {
            @Override
            public Object getService( Bundle bundle, ServiceRegistration<Object> serviceRegistration ) {
              return reference.getObject();
            }

            @Override
            public void ungetService( Bundle bundle, ServiceRegistration<Object> serviceRegistration, Object o ) {

            }
          };
          if ( hashtable.containsKey( "priority" ) ) {
            hashtable.put( Constants.SERVICE_RANKING, hashtable.get( "priority" ) );
          }
          ServiceRegistration<?> serviceRegistration =
              bundleContext.registerService( aClass.getName(), factory, hashtable );
          registrations.add( serviceRegistration );
        } else {

          // Publish it as an IPentahoObjectReference instead
          Hashtable<String, Object> referenceHashTable = new Hashtable<>( hashtable );
          referenceHashTable.put( REFERENCE_CLASS, aClass.getName() );
          ServiceRegistration<?> serviceRegistration =
              bundleContext.registerService( IPentahoObjectReference.class.getName(), reference,
                  referenceHashTable );
          registrations.add( serviceRegistration );
        }
      } catch ( ClassCastException e ) {
        logger.error( "Error Retriving object from OSGI, Class is not as expected", e );
      } catch ( IllegalStateException e ) {
        // [PDI-20686] The context was invalidated while we were registering. Undo whatever made it in and
        // hold the registration until a valid context is handed over.
        logger.warn( "The OSGI BundleContext is no longer valid. Deferring the registration of "
            + aClass.getName() + " until it is restored.", e );
        unregisterQuietly( registrations );
        return deferRegistration( reference, existingRegistration, classes );
      }
    }
    if ( existingRegistration != null ) {
      existingRegistration.setRegistrations( registrations );
      return existingRegistration;
    } else {
      return new OSGIPentahoObjectRegistration( registrations );
    }

  }

  @Override
  public <T> IPentahoObjectRegistration registerReference( IPentahoObjectReference<T> reference, Class<?>... classes ) {
    return this.registerReference( reference, null, classes );
  }

  /**
   * Registers the reference on the plain (non-OSGI) factory and queues it, so that it is published to OSGI
   * by the next {@link #setBundleContext(BundleContext)}. Used both before OSGI is available and, since
   * [PDI-20686], whenever the context in hand has been invalidated by a bundle refresh.
   */
  private IPentahoObjectRegistration deferRegistration( IPentahoObjectReference<?> reference,
                                                        OSGIPentahoObjectRegistration existingRegistration,
                                                        Class<?>... classes ) {
    OSGIPentahoObjectRegistration osgiPentahoObjectRegistration = existingRegistration;
    if ( osgiPentahoObjectRegistration == null ) {
      osgiPentahoObjectRegistration =
          new OSGIPentahoObjectRegistration( (ObjectRegistration) super.registerReference( reference, classes ) );
    } else if ( osgiPentahoObjectRegistration.iPentahoObjectRegistration == null ) {
      // Already published to OSGI once: it is only held on the non-OSGI factory again if that publication
      // has since been undone.
      osgiPentahoObjectRegistration
          .setDeferredRegistration( (ObjectRegistration) super.registerReference( reference, classes ) );
    }
    synchronized ( deferredRegistrations ) {
      deferredRegistrations.add( osgiPentahoObjectRegistration );
    }
    return osgiPentahoObjectRegistration;
  }

  private void unregisterQuietly( List<ServiceRegistration<?>> registrations ) {
    for ( ServiceRegistration<?> registration : registrations ) {
      try {
        registration.unregister();
      } catch ( IllegalStateException e ) {
        logger.debug( "Error on Unregistering the service, it seems already be unregistered", e );
      }
    }
  }

  @Override public boolean objectDefined( Class<?> clazz ) {
    if ( !osgiInitialized.get() || !isBundleContextUsable() ) {
      return super.objectDefined( clazz );
    }
    // Look for IPentahoObjectReference first
    try {
      Collection<ServiceReference<IPentahoObjectReference>> serviceReferences = this.bundleContext
          .getServiceReferences( IPentahoObjectReference.class,
              ( "(" + REFERENCE_CLASS + "=" + clazz.getName() + ")" ) );
      if ( serviceReferences != null && serviceReferences.size() > 0 ) {
        return true;
      }
      // try by the classname
      return this.bundleContext.getServiceReference( clazz ) != null;
    } catch ( IllegalStateException ise ) {
      // caused by the bundleContext being invalid
      return false;
    } catch ( InvalidSyntaxException e ) {
      throw new IllegalStateException( "Error finding reference in OSGI" );
    }
  }

  @Override
  protected <T> List<IPentahoObjectReference<?>> getReferencesByQuery( Class<T> type, Map<String, String> query ) {
    if ( !osgiInitialized.get() || !isBundleContextUsable() ) {
      return super.getReferencesByQuery( type, query );
    }
    return Collections.emptyList();
  }

  private class OSGIPentahoObjectRegistration implements IPentahoObjectRegistration {
    private List<ServiceRegistration<?>> registrations = new ArrayList<ServiceRegistration<?>>();
    private ObjectRegistration iPentahoObjectRegistration;

    public OSGIPentahoObjectRegistration(
        List<ServiceRegistration<?>> registrations ) {
      this.registrations.addAll( registrations );
    }

    public OSGIPentahoObjectRegistration( ObjectRegistration iPentahoObjectRegistration ) {

      this.iPentahoObjectRegistration = iPentahoObjectRegistration;
    }

    @Override public void remove() {
      if ( iPentahoObjectRegistration != null ) {
        iPentahoObjectRegistration.remove();
      }

      unregisterQuietly( registrations );

    }

    public void setRegistrations( List<ServiceRegistration<?>> registrations ) {
      this.registrations = registrations;
      this.iPentahoObjectRegistration = null;
    }

    public void setDeferredRegistration( ObjectRegistration iPentahoObjectRegistration ) {
      this.iPentahoObjectRegistration = iPentahoObjectRegistration;
      this.registrations = new ArrayList<ServiceRegistration<?>>();
    }
  }
}