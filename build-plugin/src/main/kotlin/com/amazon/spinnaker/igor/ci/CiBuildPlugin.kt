package com.amazon.spinnaker.igor.ci

import com.netflix.spinnaker.kork.plugins.api.spring.PrivilegedSpringPlugin
import mu.KotlinLogging
import org.pf4j.PluginWrapper
import org.springframework.beans.factory.support.BeanDefinitionRegistry

class CiBuildPlugin(wrapper: PluginWrapper) : PrivilegedSpringPlugin(wrapper) {
    private val logger = KotlinLogging.logger {}

    override fun start() {
        logger.info("starting CiBuild plugin.")
    }

    override fun stop() {
        logger.info("stopping CiBuild plugin.")
    }

    override fun registerBeanDefinitions(registry: BeanDefinitionRegistry?) {
        listOf(
            beanDefinitionFor(JenkinsCiBuildService::class.java)
        ).forEach {
            registerBean(it, registry)
        }
    }


}

