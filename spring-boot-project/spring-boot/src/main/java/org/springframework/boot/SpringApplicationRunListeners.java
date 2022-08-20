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

package org.springframework.boot;

import org.apache.commons.logging.Log;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.util.ReflectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * SpringApplicationRunListener 数组的封装
 * 实现了运行监听器的类，可以在事件发布之前做一些操作，这也是扩展点之一哦
 * 运行监听器的作用我的理解其实是桥接，桥接事件监听器ApplicationListener和Spring的广播器SimpleApplicationEventMulticaster，
 * 它通过集成广播器，从而通过广播器发送 ApplicationEvent事件，使得能够对环境准备和上下文的整个生命周期进行监听
 * A collection of {@link SpringApplicationRunListener}.
 *
 * @author Phillip Webb
 */
class SpringApplicationRunListeners {

	private final Log log;

    /**
     * SpringApplicationRunListener 数组
     */
	private final List<SpringApplicationRunListener> listeners;

	SpringApplicationRunListeners(Log log,
			Collection<? extends SpringApplicationRunListener> listeners) {
		this.log = log;
		this.listeners = new ArrayList<>(listeners);
	}

	public void starting() {
		for (SpringApplicationRunListener listener : this.listeners) {
			listener.starting();
		}
	}

	/**
	* 环境准备好后，可以对环境中的变量进行增删改查，时机有多个
	*/
	public void environmentPrepared(ConfigurableEnvironment environment) {
		for (SpringApplicationRunListener listener : this.listeners) {
			// 可以使用来实现SpringApplicationRunListener从而实现environmentPrepared方法往ConfigurableEnvironment更新属性信息
//			比如加载properties和yaml文件就是这样实现的，通过调用ConfigFileApplicationListener.onApplicationEvent方法，从而调用onApplicationEnvironmentPreparedEvent方法
			listener.environmentPrepared(environment);
		}
	}

	/**
	* 此处会发布容器准备好的事件，可以做扩展点，和上面的思路是一样的
	*/
	public void contextPrepared(ConfigurableApplicationContext context) {
		for (SpringApplicationRunListener listener : this.listeners) {
			listener.contextPrepared(context);
		}
	}

	public void contextLoaded(ConfigurableApplicationContext context) {
		for (SpringApplicationRunListener listener : this.listeners) {
			listener.contextLoaded(context);
		}
	}

	public void started(ConfigurableApplicationContext context) {
		for (SpringApplicationRunListener listener : this.listeners) {
			listener.started(context);
		}
	}

	public void running(ConfigurableApplicationContext context) {
		for (SpringApplicationRunListener listener : this.listeners) {
			listener.running(context);
		}
	}

	public void failed(ConfigurableApplicationContext context, Throwable exception) {
		for (SpringApplicationRunListener listener : this.listeners) {
			callFailedListener(listener, context, exception);
		}
	}

	private void callFailedListener(SpringApplicationRunListener listener, ConfigurableApplicationContext context, Throwable exception) {
		try {
			listener.failed(context, exception);
		} catch (Throwable ex) {
			if (exception == null) {
				ReflectionUtils.rethrowRuntimeException(ex);
			}
			if (this.log.isDebugEnabled()) {
				this.log.error("Error handling failed", ex);
			} else {
				String message = ex.getMessage();
				message = (message != null) ? message : "no error message";
				this.log.warn("Error handling failed (" + message + ")");
			}
		}
	}

}
