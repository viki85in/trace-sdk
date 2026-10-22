package org.util.trace.spring;

import org.springframework.aop.config.AopConfigUtils;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar;
import org.springframework.core.type.AnnotationMetadata;

/**
 * Makes sure an auto-proxy creator exists, so plain Spring (no Boot) apps get proxies too. Class
 * (CGLIB) proxying is forced, as Spring Boot does by default, so traced beans stay injectable by
 * their concrete class even when they implement interfaces.
 */
final class TraceAutoProxyRegistrar implements ImportBeanDefinitionRegistrar {

    @Override
    public void registerBeanDefinitions(AnnotationMetadata metadata, BeanDefinitionRegistry registry) {
        AopConfigUtils.registerAutoProxyCreatorIfNecessary(registry);
        AopConfigUtils.forceAutoProxyCreatorToUseClassProxying(registry);
    }
}
