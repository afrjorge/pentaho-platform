/*! ******************************************************************************
 *
 * Pentaho
 *
 * Copyright (C) 2024 by Hitachi Vantara, LLC : http://www.pentaho.com
 *
 * Use of this software is governed by the Business Source License included
 * in the LICENSE.TXT file.
 *
 * Change Date: 2029-07-20
 ******************************************************************************/


package org.pentaho.platform.engine.core.system.objfac.spring;

import org.apache.commons.lang.ClassUtils;
import org.pentaho.platform.api.engine.IPluginManager;
import org.pentaho.platform.engine.core.system.PentahoSystem;
import org.springframework.beans.PropertyValue;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.xml.BeanDefinitionDecorator;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Parses the publish tag of a bean. Exposing the bean to the PentahoSystem as an implementation of the given type.
 * Beans can be published by a given type, as all implemented interfaces, as all inherited classes, a combination of all
 * classes and interfaces, or by default as the class of the bean itself.
 * <p/>
 * Attributes embedded in the publish tag become available to the PentahoSystem to allow for querying of registered
 * implementations. An attribute of "priority" is used to determine the order of registered implementations.
 * <p/>
 * <pen:bean class="com.foo.Clazz"> <pen:publish as-type="[ INTERFACES | CLASSES | ALL | Classname ]> <pen:attributes>
 * <pen:attr key="priority" value="50"/> </pen:attributes> </pen:publish> </pen:bean>
 * <p/>
 * When the declared bean class is a Spring AOP {@code ProxyFactoryBean} (or any
 * {@code AbstractSingletonProxyFactoryBean}), {@code INTERFACES}, {@code CLASSES} and {@code ALL} are resolved against
 * the proxy's target type (or the explicit {@code proxyInterfaces}/{@code interfaces} property, when present) rather
 * than against the factory class itself. This avoids publishing the bean under Spring AOP internal markers
 * (FactoryBean, Advised, TargetClassAware, ...) and ensures the bean is registered under the business interfaces that
 * consumers actually look up via {@code PublishedBeanRegistry}.
 * <p/>
 * User: nbaker Date: 3/27/13
 */
public class BeanPublishParser implements BeanDefinitionDecorator {
  private static String ATTR = "as-type";

  private static final String PROXY_FACTORY_BEAN_CLASS =
    "org.springframework.aop.framework.ProxyFactoryBean";
  private static final String ABSTRACT_SINGLETON_PROXY_FACTORY_BEAN_CLASS =
    "org.springframework.aop.framework.AbstractSingletonProxyFactoryBean";

  private IPluginManager pluginManager;

  private static enum specialPublishTypes {
    INTERFACES, CLASSES, ALL
  }

  @Override
  public BeanDefinitionHolder decorate( Node node, BeanDefinitionHolder beanDefinitionHolder,
                                        ParserContext parserContext ) {

    String publishType = null;
    String beanClassName;

    // If this is a republish of a pen:bean, the class will be a FactoryBean and the actual class returned back the
    // factory will be stored as an attribute.
    if ( beanDefinitionHolder.getBeanDefinition().getAttribute( "originalClassName" ) != null ) {
      beanClassName = beanDefinitionHolder.getBeanDefinition().getAttribute( "originalClassName" ).toString();
    } else {
      beanClassName = beanDefinitionHolder.getBeanDefinition().getBeanClassName();
    }

    if ( node.getAttributes().getNamedItem( ATTR ) != null ) { // publish as type if specified
      publishType = node.getAttributes().getNamedItem( ATTR ).getNodeValue();
    } else { // fallback to publish as itself
      publishType = beanClassName;
    }

    // capture the "ID" of the bean as an attribute so it can be queried for
    beanDefinitionHolder.getBeanDefinition().setAttribute( "id", beanDefinitionHolder.getBeanName() );
    NodeList nodes = node.getChildNodes();

    for ( int i = 0; i < nodes.getLength(); i++ ) {
      Node n = nodes.item( i );
      if ( stripNamespace( n.getNodeName() ).equals( Const.ATTRIBUTES ) ) {
        NodeList attrnodes = n.getChildNodes();

        for ( int y = 0; y < attrnodes.getLength(); y++ ) {
          Node an = attrnodes.item( y );
          if ( stripNamespace( an.getNodeName() ).equals( Const.ATTR ) ) {
            beanDefinitionHolder.getBeanDefinition().setAttribute(
              an.getAttributes().getNamedItem( Const.KEY ).getNodeValue(),
              an.getAttributes().getNamedItem( Const.VALUE ).getNodeValue() );
          }
        }
      }
    }

    try {
      List<Class<?>> classesToPublish = new ArrayList<Class<?>>();

      Class<?> declaredClass = findClass( beanClassName );

      // If the declared class is a Spring AOP ProxyFactoryBean, resolve the real target type so that
      // INTERFACES/CLASSES/ALL publish the business interfaces/classes instead of the factory's own.
      Class<?> clazz = resolveEffectiveClass(
        beanDefinitionHolder.getBeanDefinition(), declaredClass, parserContext );

      if ( specialPublishTypes.INTERFACES.name().equals( publishType ) ) {
        // Honor an explicit proxyInterfaces/interfaces list on the ProxyFactoryBean, when present.
        List<Class<?>> explicitInterfaces = isSpringProxyFactoryBean( declaredClass )
          ? resolveExplicitProxyInterfaces( beanDefinitionHolder.getBeanDefinition() )
          : null;

        if ( explicitInterfaces != null && !explicitInterfaces.isEmpty() ) {
          classesToPublish.addAll( explicitInterfaces );
        } else if ( clazz.isInterface() ) { // publish as self if interface already (re-publish flow)
          classesToPublish.add( clazz );
        } else {
          classesToPublish.addAll( ClassUtils.getAllInterfaces( clazz ) );
        }
      } else if ( specialPublishTypes.CLASSES.name().equals( publishType ) ) {

        classesToPublish.addAll( ClassUtils.getAllSuperclasses( clazz ) );
        classesToPublish.add( clazz );
      } else if ( specialPublishTypes.ALL.name().equals( publishType ) ) {

        classesToPublish.addAll( ClassUtils.getAllInterfaces( clazz ) );
        classesToPublish.addAll( ClassUtils.getAllSuperclasses( clazz ) );
        classesToPublish.add( clazz );
      } else {
        classesToPublish.add( getClass().getClassLoader().loadClass( publishType ) );
      }

      String beanFactoryId = null;

      if ( parserContext.getRegistry().containsBeanDefinition( Const.FACTORY_MARKER ) == false ) {
        beanFactoryId = UUID.randomUUID().toString();
        parserContext.getRegistry().registerBeanDefinition(
          Const.FACTORY_MARKER,
          BeanDefinitionBuilder.genericBeanDefinition( Marker.class ).setScope( BeanDefinition.SCOPE_PROTOTYPE )
            .addConstructorArgValue( beanFactoryId ).getBeanDefinition() );
      } else {
        beanFactoryId =
          (String) parserContext.getRegistry().getBeanDefinition( Const.FACTORY_MARKER )
            .getConstructorArgumentValues().getArgumentValue( 0, String.class ).getValue();
      }

      for ( Class cls : classesToPublish ) {
        PublishedBeanRegistry.registerBean( beanDefinitionHolder.getBeanName(), cls, beanFactoryId );
      }

    } catch ( ClassNotFoundException e ) {
      throw new RuntimeException( "Cannot find class for publish type: " + publishType
        + " specified on publish of bean id: " + beanDefinitionHolder.getBeanName(), e );
    }
    return beanDefinitionHolder;
  }

  /**
   * Resolves the effective class used to compute the published types. If the declared class is a Spring AOP proxy
   * factory bean, the target's class is returned instead. Falls back to the declared class when the target cannot be
   * determined at parse time.
   */
  private Class<?> resolveEffectiveClass( BeanDefinition bd, Class<?> declaredClass, ParserContext parserContext ) {
    if ( declaredClass == null || !isSpringProxyFactoryBean( declaredClass ) ) {
      return declaredClass;
    }
    Class<?> targetClass = resolveProxyTargetClass( bd, parserContext, new HashSet<String>() );
    return targetClass != null ? targetClass : declaredClass;
  }

  /**
   * Walks the {@code target} / {@code targetName} properties of a {@code ProxyFactoryBean} definition until a
   * non-proxy target class is resolved. Uses a visited-set to guard against cycles.
   */
  private Class<?> resolveProxyTargetClass( BeanDefinition bd, ParserContext ctx, Set<String> visited ) {
    PropertyValue targetPv = bd.getPropertyValues().getPropertyValue( "target" );
    if ( targetPv != null ) {
      Class<?> c = resolveValueClass( targetPv.getValue(), ctx, visited );
      if ( c != null ) {
        return c;
      }
    }
    PropertyValue targetNamePv = bd.getPropertyValues().getPropertyValue( "targetName" );
    if ( targetNamePv != null && targetNamePv.getValue() instanceof String ) {
      Class<?> c = resolveBeanClassByName( (String) targetNamePv.getValue(), ctx, visited );
      if ( c != null ) {
        return c;
      }
    }
    return null;
  }

  private Class<?> resolveValueClass( Object value, ParserContext ctx, Set<String> visited ) {
    if ( value instanceof RuntimeBeanReference ) {
      return resolveBeanClassByName( ( (RuntimeBeanReference) value ).getBeanName(), ctx, visited );
    }
    if ( value instanceof BeanDefinitionHolder ) {
      return resolveBeanDefClass( ( (BeanDefinitionHolder) value ).getBeanDefinition(), ctx, visited );
    }
    if ( value instanceof BeanDefinition ) {
      return resolveBeanDefClass( (BeanDefinition) value, ctx, visited );
    }
    return null;
  }

  private Class<?> resolveBeanClassByName( String name, ParserContext ctx, Set<String> visited ) {
    if ( name == null || !visited.add( name ) ) {
      return null;
    }
    if ( !ctx.getRegistry().containsBeanDefinition( name ) ) {
      return null;
    }
    return resolveBeanDefClass( ctx.getRegistry().getBeanDefinition( name ), ctx, visited );
  }

  private Class<?> resolveBeanDefClass( BeanDefinition bd, ParserContext ctx, Set<String> visited ) {
    String cn = bd.getBeanClassName();
    if ( cn == null ) {
      return null;
    }
    Class<?> c;
    try {
      c = findClass( cn );
    } catch ( ClassNotFoundException e ) {
      return null;
    }
    // If the target is itself a proxy factory bean, keep unwrapping.
    if ( isSpringProxyFactoryBean( c ) ) {
      Class<?> inner = resolveProxyTargetClass( bd, ctx, visited );
      if ( inner != null ) {
        return inner;
      }
    }
    return c;
  }

  /**
   * Reads an explicitly declared {@code proxyInterfaces} (or legacy {@code interfaces}) property from a
   * {@code ProxyFactoryBean} definition and returns the corresponding list of {@code Class} objects. Returns
   * {@code null} if no such property is present.
   */
  private List<Class<?>> resolveExplicitProxyInterfaces( BeanDefinition bd ) {
    PropertyValue pv = bd.getPropertyValues().getPropertyValue( "proxyInterfaces" );
    if ( pv == null ) {
      pv = bd.getPropertyValues().getPropertyValue( "interfaces" );
    }
    if ( pv == null ) {
      return null;
    }
    Object val = pv.getValue();
    List<Class<?>> result = new ArrayList<Class<?>>();
    collectInterfaceNames( val, result );
    return result.isEmpty() ? null : result;
  }

  /**
   * Recursively walks the property value extracting interface FQN strings. Handles raw {@code String}, {@code String[]},
   * {@code Collection} (e.g. {@code ManagedList}), arbitrary arrays, and Spring's {@link TypedStringValue} which is the
   * shape XML {@code <value>...</value>} entries take after parsing.
   */
  private void collectInterfaceNames( Object val, List<Class<?>> out ) {
    if ( val == null ) {
      return;
    }
    if ( val instanceof TypedStringValue ) {
      addInterfaceByName( ( (TypedStringValue) val ).getValue(), out );
    } else if ( val instanceof String ) {
      addInterfaceByName( (String) val, out );
    } else if ( val instanceof String[] ) {
      for ( String s : (String[]) val ) {
        addInterfaceByName( s, out );
      }
    } else if ( val instanceof Collection ) {
      for ( Object o : (Collection<?>) val ) {
        collectInterfaceNames( o, out );
      }
    } else if ( val.getClass().isArray() ) {
      int len = java.lang.reflect.Array.getLength( val );
      for ( int i = 0; i < len; i++ ) {
        collectInterfaceNames( java.lang.reflect.Array.get( val, i ), out );
      }
    }
  }

  private void addInterfaceByName( String name, List<Class<?>> out ) {
    if ( name == null ) {
      return;
    }
    try {
      out.add( findClass( name.trim() ) );
    } catch ( ClassNotFoundException e ) {
      // ignore; the interface is not available on any known classloader
    }
  }

  /**
   * Returns {@code true} when {@code clazz} is (a subclass of) Spring's {@code ProxyFactoryBean} or
   * {@code AbstractSingletonProxyFactoryBean}. Matched by name to avoid a hard compile-time dependency.
   */
  private boolean isSpringProxyFactoryBean( Class<?> clazz ) {
    for ( Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass() ) {
      String n = c.getName();
      if ( PROXY_FACTORY_BEAN_CLASS.equals( n )
        || ABSTRACT_SINGLETON_PROXY_FACTORY_BEAN_CLASS.equals( n ) ) {
        return true;
      }
    }
    return false;
  }

  private Class<?> findClass( String beanClassName ) throws ClassNotFoundException {
    // try main classloader
    // getClass().getClassLoader()
    Class<?> clazz = loadClassFromClassloader( getClass().getClassLoader(), beanClassName );
    if ( clazz != null ) {
      return clazz;
    }
    if (getPluginManager() != null) {
      for ( String s : getPluginManager().getRegisteredPlugins() ) {
        clazz = loadClassFromClassloader( getPluginManager().getClassLoader( s ), beanClassName );
        if ( clazz != null ) {
          return clazz;
        }
      }
    }

    throw new ClassNotFoundException( beanClassName );
  }

  private IPluginManager getPluginManager() {
    if ( pluginManager == null ) {
      pluginManager = PentahoSystem.get( IPluginManager.class );
    }
    return pluginManager;
  }

  private Class<?> loadClassFromClassloader( ClassLoader loader, String beanClassName ) {
    try {
      return loader.loadClass( beanClassName );
    } catch ( ClassNotFoundException e ) {
      //ignored
    }
    return null;
  }

  private static String stripNamespace( String s ) {
    if ( s.indexOf( ':' ) > 0 ) {
      return s.substring( s.indexOf( ':' ) + 1 );
    }
    return s;
  }
}
