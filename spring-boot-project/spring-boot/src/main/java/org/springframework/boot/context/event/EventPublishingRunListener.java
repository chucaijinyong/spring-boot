/*
 * Copyright 2012-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.boot.context.event;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringApplicationRunListener;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ApplicationEventMulticaster;
import org.springframework.context.event.SimpleApplicationEventMulticaster;
import org.springframework.context.support.AbstractApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ErrorHandler;

/**
 * {@link SpringApplicationRunListener} to publish {@link SpringApplicationEvent}s.
 * <p>
 * Uses an internal {@link ApplicationEventMulticaster} for the events that are fired
 * before the context is actually refreshed.
 *
 * @author Phillip Webb
 * @author Stephane Nicoll
 * @author Andy Wilkinson
 * @author Artsiom Yudovin
 */
public class EventPublishingRunListener implements SpringApplicationRunListener, Ordered {

    /**
     * Spring 应用【这个属性很重要，因为该属性中有从spring.factories中收集的ApplicationContextInitializer上下文初始化器和事件监听器ApplicationListener的集合】
     */
	private final SpringApplication application;
    /**
     * 参数集合
     */
	private final String[] args;
    /**
     * 简单应用事件广播器-这个是spring的组件
     */
	private final SimpleApplicationEventMulticaster initialMulticaster;

	/**
	* 你知道这个地方的初始化时机吗？在这里SpringApplication#getSpringFactoriesInstances(java.lang.Class, java.lang.Class[], java.lang.Object...)
	 * 这里有个注意点，如果实现了SpringApplicationRunListener，需要初始化构造起的，因为创建该实现类对象时是基于构造器创建的
	*/
	public EventPublishingRunListener(SpringApplication application, String[] args) {
		this.application = application;
		this.args = args;
		// 创建 SimpleApplicationEventMulticaster 对象
		this.initialMulticaster = new SimpleApplicationEventMulticaster();
		// 从SpringApplication中获取事件监听器，添加到广播器initialMulticaster中。这个步骤非常重要，因为以后的事件监听器遍历都是基于此的。
//		该事件广播器是spring的,通过spring的事件广播器发送Springboot的定义的事件，不过springBoot的事件实现了Spring的ApplicationEvent顶层接口
//		SpringBoot的很多事件都是基于事件监听来完成的,比如说文件编码,配置文件的处理【处理application.yml和application.properties】,日志的处理等等
//		org.springframework.context.ApplicationListener=\
//		org.springframework.boot.ClearCachesApplicationListener,\
//		org.springframework.boot.builder.ParentContextCloserApplicationListener,\
//		org.springframework.boot.context.FileEncodingApplicationListener,\
//		org.springframework.boot.context.config.AnsiOutputApplicationListener,\
//		org.springframework.boot.context.config.ConfigFileApplicationListener,\
//		org.springframework.boot.context.config.DelegatingApplicationListener,\
//		org.springframework.boot.context.logging.ClasspathLoggingApplicationListener,\
//		org.springframework.boot.context.logging.LoggingApplicationListener,\
//		org.springframework.boot.liquibase.LiquibaseServiceLocatorApplicationListener
		for (ApplicationListener<?> listener : application.getListeners()) {
			this.initialMulticaster.addApplicationListener(listener);
		}
	}

	@Override
	public int getOrder() {
		return 0;
	}

	@Override
	public void starting() {
		this.initialMulticaster.multicastEvent(new ApplicationStartingEvent(this.application, this.args));
	}

	/**
	* 通过spring的事件多播器initialMulticaster来发布广播事件ApplicationEnvironmentPreparedEvent，只要我们实现ApplicationListener监听器，重写其OnApplicationListener方法就能监听，
	 * 【这里我们得出结论，SpringBoot的事件监听最终是通过Spring事件监听来实现】
	 * 这样ConfigFileApplicationListener#onApplicationEvent() 方法就可以从监听事件中获取ConfigurableEnvironment环境变量，对环境变量的属性进行增加或更新
	*/
	@Override
	public void environmentPrepared(ConfigurableEnvironment environment) {
		this.initialMulticaster.multicastEvent(new ApplicationEnvironmentPreparedEvent(this.application, this.args, environment));
	}

	@Override // ApplicationContextInitializedEvent
	public void contextPrepared(ConfigurableApplicationContext context) {
		this.initialMulticaster.multicastEvent(new ApplicationContextInitializedEvent(this.application, this.args, context));
	}

	@Override // ApplicationPreparedEvent
	public void contextLoaded(ConfigurableApplicationContext context) {
		for (ApplicationListener<?> listener : this.application.getListeners()) {
			if (listener instanceof ApplicationContextAware) {
				((ApplicationContextAware) listener).setApplicationContext(context);
			}
			context.addApplicationListener(listener);
		}
		this.initialMulticaster.multicastEvent(new ApplicationPreparedEvent(this.application, this.args, context));
	}

	@Override // ApplicationStartedEvent
	public void started(ConfigurableApplicationContext context) {
		context.publishEvent(new ApplicationStartedEvent(this.application, this.args, context));
	}

	@Override
	public void running(ConfigurableApplicationContext context) {
		context.publishEvent(new ApplicationReadyEvent(this.application, this.args, context));
	}

	@Override // ApplicationFailedEvent
	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		ApplicationFailedEvent event = new ApplicationFailedEvent(this.application, this.args, context, exception);
		if (context != null && context.isActive()) {
			// Listeners have been registered to the application context so we should
			// use it at this point if we can
			context.publishEvent(event);
		} else {
			// An inactive context may not have a multicaster so we use our multicaster to
			// call all of the context's listeners instead
			if (context instanceof AbstractApplicationContext) {
				for (ApplicationListener<?> listener : ((AbstractApplicationContext) context)
						.getApplicationListeners()) {
					this.initialMulticaster.addApplicationListener(listener);
				}
			}
			this.initialMulticaster.setErrorHandler(new LoggingErrorHandler());
			this.initialMulticaster.multicastEvent(event);
		}
	}

	private static class LoggingErrorHandler implements ErrorHandler {

		private static Log logger = LogFactory.getLog(EventPublishingRunListener.class);

		@Override
		public void handleError(Throwable throwable) {
			logger.warn("Error calling ApplicationEventListener", throwable);
		}

	}

}
