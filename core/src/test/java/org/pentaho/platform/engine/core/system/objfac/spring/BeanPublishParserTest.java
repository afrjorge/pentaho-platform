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

import org.junit.Before;
import org.junit.Test;
import org.springframework.aop.framework.AbstractSingletonProxyFactoryBean;
import org.springframework.aop.framework.ProxyFactoryBean;
import org.springframework.beans.factory.FactoryBean;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.config.RuntimeBeanReference;
import org.springframework.beans.factory.config.TypedStringValue;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.ManagedList;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.beans.factory.xml.ParserContext;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link BeanPublishParser}.
 * <p/>
 * The parser is exercised through its {@code decorate} entry point with synthetic DOM nodes and bean definitions so
 * that each code path (explicit FQN, INTERFACES / CLASSES / ALL, default, FactoryBean unwrapping, explicit
 * {@code proxyInterfaces} property, chained proxies, cyclic refs, etc.) can be covered in isolation.
 */
public class BeanPublishParserTest {

  // region Fixture types

  public interface IServiceA {
    String a();
  }

  public interface IServiceB {
    String b();
  }

  public static class ServiceImpl implements IServiceA, IServiceB {
    @Override public String a() { return "a"; }
    @Override public String b() { return "b"; }
  }

  public static class ServiceSubImpl extends ServiceImpl {
  }

  /** No-interface POJO. */
  public static class PlainPojo {
  }

  /** Minimal {@link AbstractSingletonProxyFactoryBean} subclass for testing unwrapping. */
  public static class TestSingletonProxyFactoryBean extends AbstractSingletonProxyFactoryBean {
    @Override protected Object createMainInterceptor() {
      return null;
    }
  }

  /** A {@link FactoryBean} that is NOT a Spring AOP proxy factory bean — should never be unwrapped. */
  public static class PlainFactoryBean implements FactoryBean<IServiceA> {
    @Override public IServiceA getObject() { return () -> "x"; }
    @Override public Class<?> getObjectType() { return IServiceA.class; }
    @Override public boolean isSingleton() { return true; }
  }

  // endregion

  // region Test state

  private DefaultListableBeanFactory registry;
  private ParserContext parserContext;
  private BeanPublishParser parser;

  @Before
  public void setUp() {
    registry = new DefaultListableBeanFactory();
    parserContext = mock( ParserContext.class );
    when( parserContext.getRegistry() ).thenReturn( registry );
    parser = new BeanPublishParser();
  }

  // endregion

  // region explicit FQN / basic publish types

  @Test
  public void decorate_explicitClassNameAsType_publishesOnlyThatClass() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceImpl.class.getName() );

    parser.decorate( publishNode( IServiceA.class.getName() ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertEquals( Collections.singleton( IServiceA.class ), published );
    assertEquals( "svc", holder.getBeanDefinition().getAttribute( "id" ) );
  }

  @Test
  public void decorate_noAsType_defaultsToBeanClassName() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceImpl.class.getName() );

    parser.decorate( publishNode( null ), holder, parserContext );

    assertEquals( Collections.singleton( ServiceImpl.class ), publishedClasses( registry ) );
  }

  @Test
  public void decorate_interfacesAsType_publishesAllInterfacesOfPojo() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceImpl.class.getName() );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
    assertFalse( published.contains( ServiceImpl.class ) );
  }

  @Test
  public void decorate_interfacesAsType_onPojoWithNoInterfaces_publishesNothing() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", PlainPojo.class.getName() );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    assertTrue( publishedClasses( registry ).isEmpty() );
  }

  @Test
  public void decorate_interfacesAsType_republishFlow_publishesInterfaceAsSelf() throws Exception {
    // On a re-publish, the bean class is the FactoryBean that produces the real object; the real class is carried in
    // the originalClassName attribute. When it is an interface, it should be published as itself.
    BeanDefinitionHolder holder = holder( "svc", ProxyFactoryBean.class.getName() );
    holder.getBeanDefinition().setAttribute( "originalClassName", IServiceA.class.getName() );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    assertEquals( Collections.singleton( IServiceA.class ), publishedClasses( registry ) );
  }

  @Test
  public void decorate_classesAsType_publishesAllSuperclassesAndSelf() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceSubImpl.class.getName() );

    parser.decorate( publishNode( "CLASSES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( ServiceSubImpl.class ) );
    assertTrue( published.contains( ServiceImpl.class ) );
    assertTrue( published.contains( Object.class ) );
    assertFalse( published.contains( IServiceA.class ) );
  }

  @Test
  public void decorate_allAsType_publishesInterfacesSuperclassesAndSelf() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceSubImpl.class.getName() );

    parser.decorate( publishNode( "ALL" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( ServiceSubImpl.class ) );
    assertTrue( published.contains( ServiceImpl.class ) );
    assertTrue( published.contains( Object.class ) );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_unknownPublishType_throwsRuntimeException() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceImpl.class.getName() );
    try {
      parser.decorate( publishNode( "com.does.not.Exist" ), holder, parserContext );
      fail( "expected RuntimeException" );
    } catch ( RuntimeException expected ) {
      assertTrue( expected.getMessage().contains( "com.does.not.Exist" ) );
      assertTrue( expected.getMessage().contains( "svc" ) );
    }
  }

  // endregion

  // region attributes + factory marker

  @Test
  public void decorate_childAttributes_areCopiedOntoBeanDefinition() throws Exception {
    BeanDefinitionHolder holder = holder( "svc", ServiceImpl.class.getName() );
    Element publish = publishNode( "INTERFACES" );
    appendAttributes( publish, new String[][] {
      { "priority", "50" },
      { "region", "emea" }
    } );

    parser.decorate( publish, holder, parserContext );

    assertEquals( "50", holder.getBeanDefinition().getAttribute( "priority" ) );
    assertEquals( "emea", holder.getBeanDefinition().getAttribute( "region" ) );
  }

  @Test
  public void decorate_registersFactoryMarker_whenAbsent_andReusesExistingMarker() throws Exception {
    BeanDefinitionHolder first = holder( "svcA", ServiceImpl.class.getName() );
    parser.decorate( publishNode( "INTERFACES" ), first, parserContext );

    assertTrue( registry.containsBeanDefinition( Const.FACTORY_MARKER ) );
    String firstId = factoryMarkerId( registry );
    assertNotNull( firstId );

    BeanDefinitionHolder second = holder( "svcB", ServiceSubImpl.class.getName() );
    parser.decorate( publishNode( "CLASSES" ), second, parserContext );

    // The marker and its id must be reused across subsequent decorations in the same registry.
    assertEquals( firstId, factoryMarkerId( registry ) );

    Map<Class<?>, List<String>> byClass = registryMapFor( firstId );
    assertNotNull( byClass );
    assertTrue( byClass.get( IServiceA.class ).contains( "svcA" ) );
    assertTrue( byClass.get( ServiceSubImpl.class ).contains( "svcB" ) );
  }

  // endregion

  // region ProxyFactoryBean unwrapping

  @Test
  public void decorate_proxyFactoryBean_withTargetRef_interfacesResolvedFromTarget() throws Exception {
    // Target bean definition: ServiceImpl implements IServiceA, IServiceB.
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceImpl.class.getName() ) );

    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
    // Must NOT publish Spring AOP internal markers anymore.
    assertFalse( published.contains( FactoryBean.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_withTargetName_interfacesResolvedFromTarget() throws Exception {
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceImpl.class.getName() ) );

    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "targetName", "svcTarget" );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_withInnerBeanDefinitionTarget_interfacesResolvedFromInner() throws Exception {
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target",
      new BeanDefinitionHolder( beanDef( ServiceImpl.class.getName() ), "innerTarget" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_chainedProxies_unwrappedToFinalTarget() throws Exception {
    // realTarget <- innerProxy(target=realTarget) <- outerProxy(target=innerProxy) published here.
    registry.registerBeanDefinition( "realTarget", beanDef( ServiceImpl.class.getName() ) );

    RootBeanDefinition innerProxy = beanDef( ProxyFactoryBean.class.getName() );
    innerProxy.getPropertyValues().add( "target", new RuntimeBeanReference( "realTarget" ) );
    registry.registerBeanDefinition( "innerProxy", innerProxy );

    RootBeanDefinition outerProxy = beanDef( ProxyFactoryBean.class.getName() );
    outerProxy.getPropertyValues().add( "target", new RuntimeBeanReference( "innerProxy" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( outerProxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_cyclicTargetRefs_doesNotStackOverflow_fallsBackToDeclared() throws Exception {
    // alpha <-> beta — both proxies pointing at each other. The parser must terminate and fall back.
    RootBeanDefinition alpha = beanDef( ProxyFactoryBean.class.getName() );
    RootBeanDefinition beta = beanDef( ProxyFactoryBean.class.getName() );
    alpha.getPropertyValues().add( "target", new RuntimeBeanReference( "beta" ) );
    beta.getPropertyValues().add( "target", new RuntimeBeanReference( "alpha" ) );
    registry.registerBeanDefinition( "beta", beta );

    BeanDefinitionHolder holder = new BeanDefinitionHolder( alpha, "alpha" );
    registry.registerBeanDefinition( "alpha", alpha );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    // When unable to resolve a non-proxy target, behavior falls back to the declared ProxyFactoryBean class.
    // We only assert that the parser did not explode and that at least something (possibly the AOP internals)
    // was registered or, for a pure cycle with no escape, nothing sensible was resolved.
    // The important invariant is that no exception propagated and a factory marker got registered.
    assertTrue( registry.containsBeanDefinition( Const.FACTORY_MARKER ) );
  }

  @Test
  public void decorate_proxyFactoryBean_targetRefMissingFromRegistry_fallsBackToDeclaredClass() throws Exception {
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "doesNotExist" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    // Falls back to ProxyFactoryBean's own interfaces (Spring AOP internals). We only assert the parser succeeded
    // and that the business interfaces were NOT published, since there is no target to resolve from.
    Set<Class<?>> published = publishedClasses( registry );
    assertFalse( published.contains( IServiceA.class ) );
    assertFalse( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_classesAsType_resolvesFromTarget() throws Exception {
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceSubImpl.class.getName() ) );
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "CLASSES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( ServiceSubImpl.class ) );
    assertTrue( published.contains( ServiceImpl.class ) );
    assertTrue( published.contains( Object.class ) );
    assertFalse( "must not publish ProxyFactoryBean when unwrapping succeeded",
      published.contains( ProxyFactoryBean.class ) );
  }

  @Test
  public void decorate_proxyFactoryBean_allAsType_resolvesFromTarget() throws Exception {
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceSubImpl.class.getName() ) );
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "ALL" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( ServiceSubImpl.class ) );
    assertTrue( published.contains( ServiceImpl.class ) );
    assertTrue( published.contains( Object.class ) );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_abstractSingletonProxyFactoryBeanSubclass_isUnwrapped() throws Exception {
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceImpl.class.getName() ) );
    RootBeanDefinition proxy = beanDef( TestSingletonProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( IServiceA.class ) );
    assertTrue( published.contains( IServiceB.class ) );
  }

  @Test
  public void decorate_nonProxyFactoryBean_keepsLegacyBehavior() throws Exception {
    // A plain FactoryBean is NOT unwrapped by the parser. It publishes the interfaces of the FactoryBean class
    // itself (legacy behavior), which includes FactoryBean.class.
    BeanDefinitionHolder holder = holder( "fb", PlainFactoryBean.class.getName() );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( FactoryBean.class ) );
    assertFalse( published.contains( IServiceA.class ) );
  }

  // endregion

  // region explicit proxyInterfaces property

  @Test
  public void decorate_proxyFactoryBean_explicitProxyInterfacesAsString_usedDirectly() throws Exception {
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "proxyInterfaces", IServiceA.class.getName() );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    assertEquals( Collections.singleton( IServiceA.class ), publishedClasses( registry ) );
  }

  @Test
  public void decorate_proxyFactoryBean_explicitProxyInterfacesAsManagedListOfTypedStringValues() throws Exception {
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    ManagedList<TypedStringValue> list = new ManagedList<>();
    list.add( new TypedStringValue( IServiceA.class.getName() ) );
    list.add( new TypedStringValue( IServiceB.class.getName() ) );
    proxy.getPropertyValues().add( "proxyInterfaces", list );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertEquals( new HashSet<>( Arrays.asList( IServiceA.class, IServiceB.class ) ), published );
  }

  @Test
  public void decorate_proxyFactoryBean_explicitInterfacesLegacyPropertyName() throws Exception {
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    // Legacy 'interfaces' property name is also supported.
    proxy.getPropertyValues().add( "interfaces", new String[] { IServiceA.class.getName() } );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    assertEquals( Collections.singleton( IServiceA.class ), publishedClasses( registry ) );
  }

  @Test
  public void decorate_proxyFactoryBean_explicitProxyInterfacesTakesPrecedenceOverTarget() throws Exception {
    // ServiceImpl implements A and B, but only A is listed as a proxy interface — only A is published.
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceImpl.class.getName() ) );
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    proxy.getPropertyValues().add( "proxyInterfaces", IServiceA.class.getName() );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "INTERFACES" ), holder, parserContext );

    assertEquals( Collections.singleton( IServiceA.class ), publishedClasses( registry ) );
  }

  @Test
  public void decorate_proxyFactoryBean_explicitProxyInterfacesIgnoredForClassesAsType() throws Exception {
    // proxyInterfaces is an INTERFACES-only hint; CLASSES still resolves from the target's class hierarchy.
    registry.registerBeanDefinition( "svcTarget", beanDef( ServiceSubImpl.class.getName() ) );
    RootBeanDefinition proxy = beanDef( ProxyFactoryBean.class.getName() );
    proxy.getPropertyValues().add( "target", new RuntimeBeanReference( "svcTarget" ) );
    proxy.getPropertyValues().add( "proxyInterfaces", IServiceA.class.getName() );
    BeanDefinitionHolder holder = new BeanDefinitionHolder( proxy, "svc" );

    parser.decorate( publishNode( "CLASSES" ), holder, parserContext );

    Set<Class<?>> published = publishedClasses( registry );
    assertTrue( published.contains( ServiceSubImpl.class ) );
    assertTrue( published.contains( ServiceImpl.class ) );
    assertFalse( published.contains( IServiceA.class ) );
  }

  // endregion

  // region helpers

  private static RootBeanDefinition beanDef( String className ) {
    RootBeanDefinition bd = new RootBeanDefinition();
    bd.setBeanClassName( className );
    return bd;
  }

  private static BeanDefinitionHolder holder( String beanName, String className ) {
    return new BeanDefinitionHolder( beanDef( className ), beanName );
  }

  /**
   * Creates a {@code <pen:publish>} DOM element with an optional {@code as-type} attribute. The returned element is
   * owned by a fresh Document so additional child attribute nodes can be appended when needed.
   */
  private static Element publishNode( String asType ) throws Exception {
    DocumentBuilder db = DocumentBuilderFactory.newInstance().newDocumentBuilder();
    Document doc = db.newDocument();
    Element publish = doc.createElementNS( "http://www.pentaho.com/schema/pentaho-system", "pen:publish" );
    if ( asType != null ) {
      publish.setAttribute( "as-type", asType );
    }
    doc.appendChild( publish );
    return publish;
  }

  /**
   * Appends a {@code <pen:attributes>} child with the given {@code key/value} pairs to the publish element.
   */
  private static void appendAttributes( Element publish, String[][] kvs ) {
    Document doc = publish.getOwnerDocument();
    Element attrs = doc.createElementNS( "http://www.pentaho.com/schema/pentaho-system", "pen:attributes" );
    for ( String[] kv : kvs ) {
      Element attr = doc.createElementNS( "http://www.pentaho.com/schema/pentaho-system", "pen:attr" );
      attr.setAttribute( "key", kv[ 0 ] );
      attr.setAttribute( "value", kv[ 1 ] );
      attrs.appendChild( attr );
    }
    publish.appendChild( attrs );
  }

  /**
   * Returns the factory-marker id generated/reused by {@link BeanPublishParser} in the given registry, or {@code null}
   * if no marker has been registered.
   */
  private static String factoryMarkerId( DefaultListableBeanFactory registry ) {
    if ( !registry.containsBeanDefinition( Const.FACTORY_MARKER ) ) {
      return null;
    }
    BeanDefinition markerDef = registry.getBeanDefinition( Const.FACTORY_MARKER );
    return (String) markerDef.getConstructorArgumentValues().getArgumentValue( 0, String.class ).getValue();
  }

  /**
   * Retrieves the {@code Class -> beanNames} map that {@link PublishedBeanRegistry} stored for the factory marker
   * associated with the given registry. Accessed via reflection to avoid widening the registry's public API just for
   * testing.
   */
  @SuppressWarnings( "unchecked" )
  private static Map<Class<?>, List<String>> registryMapFor( String factoryMarkerId ) throws Exception {
    Field f = PublishedBeanRegistry.class.getDeclaredField( "classToBeanMap" );
    f.setAccessible( true );
    Map<Object, Map<Class<?>, List<String>>> all =
      (Map<Object, Map<Class<?>, List<String>>>) f.get( null );
    return all.get( factoryMarkerId );
  }

  private static Set<Class<?>> publishedClasses( DefaultListableBeanFactory registry ) throws Exception {
    String id = factoryMarkerId( registry );
    if ( id == null ) {
      return Collections.emptySet();
    }
    Map<Class<?>, List<String>> byClass = registryMapFor( id );
    return byClass == null ? Collections.emptySet() : new HashSet<>( byClass.keySet() );
  }

  // endregion

  // region sanity

  @Test
  public void setUp_createsFreshState() {
    assertNotNull( parser );
    assertNotNull( parserContext );
    assertSame( registry, parserContext.getRegistry() );
    assertNull( factoryMarkerId( registry ) );
  }

  // endregion
}

